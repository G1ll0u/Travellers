package com.jubitus.travellers.debug.path;

import com.jubitus.travellers.debug.trace.TraceSession;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.command.CommandException;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player state for {@code /travellersdebug path ...}: the two comparison points, the
 * hand-marked "ideal" route, and whichever throwaway test entity is currently running. pos1/pos2/
 * marks are backed by {@link PathTestSavedData} and survive a reload, so the same route can be
 * retested after each fix attempt; the active test entity, its trace file and whether it was mounted
 * are session-only, same as a trace session never surviving a restart.
 */
public final class PathTestManager {

    private PathTestManager() {
    }

    /** Hard ceiling so a route that never converges doesn't leave a test entity wandering forever. */
    private static final int MAX_TEST_TICKS = 20 * 60 * 10; // 10 minutes

    public static final class State {
        public BlockPos pos1;
        public BlockPos pos2;
        public final List<BlockPos> marks = new ArrayList<>();
        public UUID activeEntityUuid;
        public File lastTraceFile;
        public boolean lastMounted;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    /** Active test entity uuid -> owning player uuid, so the watchdog AI (which only has the mob)
     * can find its way back to that player's state. */
    private static final Map<UUID, UUID> OWNER_OF_ENTITY = new HashMap<>();

    /**
     * In-memory cache, hydrated once per player from {@link PathTestSavedData} on first access after
     * (re)start. pos1/pos2/marks are the route definition and are written straight back to that save
     * on every change, so the same comparison route survives a reload - the whole point of the tool
     * being to test one fix attempt after another against an unchanged route. activeEntityUuid/
     * lastTraceFile/lastMounted stay session-only: a saved reference to a test entity or trace file
     * from a previous server run would just be dangling.
     */
    public static State state(EntityPlayer player) {
        return STATES.computeIfAbsent(player.getUniqueID(), k -> {
            State st = new State();
            PathTestSavedData.Entry saved = PathTestSavedData.get(player.world).getFor(player.getUniqueID());
            st.pos1 = saved.pos1;
            st.pos2 = saved.pos2;
            st.marks.addAll(saved.marks);
            return st;
        });
    }

    public static void setPos1(EntityPlayer player, BlockPos pos) {
        state(player).pos1 = pos;
        PathTestSavedData.get(player.world).setPos1(player.getUniqueID(), pos);
    }

    public static void setPos2(EntityPlayer player, BlockPos pos) {
        state(player).pos2 = pos;
        PathTestSavedData.get(player.world).setPos2(player.getUniqueID(), pos);
    }

    public static void addMark(EntityPlayer player, BlockPos pos) {
        State st = state(player);
        st.marks.add(pos);
        PathTestSavedData.get(player.world).setMarks(player.getUniqueID(), st.marks);
    }

    public static void clearMarks(EntityPlayer player) {
        State st = state(player);
        st.marks.clear();
        PathTestSavedData.get(player.world).setMarks(player.getUniqueID(), st.marks);
    }

    /** Default name for {@code path save} when none is given. */
    private static final String DEFAULT_ROUTE_NAME = "default";

    /**
     * Snapshots the current pos1/pos2 under a name, separate from the live pos1/pos2 (which keep
     * changing as the player re-marks points). Marks are deliberately not part of this - "save" is
     * about the two comparison points, not the hand-walked ideal route.
     */
    public static String saveRoute(EntityPlayer player, @Nullable String name) throws CommandException {
        State st = state(player);
        if (st.pos1 == null || st.pos2 == null) {
            throw new CommandException("Set both pos1 and pos2 first (/travellersdebug path pos1 / pos2).");
        }
        String routeName = (name == null || name.isEmpty()) ? DEFAULT_ROUTE_NAME : name;
        PathTestSavedData.get(player.world).saveRoute(player.getUniqueID(), routeName, st.pos1, st.pos2);
        return "Saved route '" + routeName + "': " + posString(st.pos1) + " -> " + posString(st.pos2);
    }

    /** Loads a previously {@code path save}d route back into the live pos1/pos2, ready for 'path test'. */
    public static String loadRoute(EntityPlayer player, @Nullable String name) throws CommandException {
        String routeName = (name == null || name.isEmpty()) ? DEFAULT_ROUTE_NAME : name;
        PathTestSavedData.Entry saved = PathTestSavedData.get(player.world).getFor(player.getUniqueID());
        BlockPos[] route = saved.savedRoutes.get(routeName);
        if (route == null) {
            throw new CommandException("No saved route named '" + routeName + "'"
                    + (saved.savedRoutes.isEmpty() ? "." : " - known: " + String.join(", ", saved.savedRoutes.keySet())));
        }
        setPos1(player, route[0]);
        setPos2(player, route[1]);
        return "Loaded route '" + routeName + "': " + posString(route[0]) + " -> " + posString(route[1]);
    }

    /** Names of every route this player has saved, for tab-completion and listing. */
    public static java.util.Set<String> savedRouteNames(EntityPlayer player) {
        return PathTestSavedData.get(player.world).getFor(player.getUniqueID()).savedRoutes.keySet();
    }

    /**
     * Spawns a throwaway {@link EntityTravellerWorker} at pos1, points it at pos2 via the same
     * {@code EntityAITravel} every real worker uses, optionally bonds it a mount, and starts tracing
     * it automatically. Returns a chat-ready status line.
     *
     * @param forceWaypoints when true and marks exist, the entity is force-fed the hand-marked route
     *                       instead of letting the automatic picker choose - see
     *                       {@link com.jubitus.travellers.entity.entityai.travel.EntityAITravel#setForcedWaypoints}.
     */
    public static String startTest(EntityPlayerMP player, boolean mount, boolean forceWaypoints) throws CommandException {
        State st = state(player);
        if (st.pos1 == null || st.pos2 == null) {
            throw new CommandException("Set both pos1 and pos2 first (/travellersdebug path pos1 / pos2).");
        }
        if (st.activeEntityUuid != null) {
            throw new CommandException("A path test is already running - use 'path stop' first.");
        }
        if (forceWaypoints && st.marks.isEmpty()) {
            throw new CommandException("No marks - use 'path mark' while walking the ideal route first.");
        }

        WorldServer ws = player.getServerWorld();
        EntityTravellerWorker worker = new EntityTravellerWorker(ws);
        worker.setDebugPathTest(true);
        worker.setLocationAndAngles(st.pos1.getX() + 0.5, st.pos1.getY(), st.pos1.getZ() + 0.5, 0.0F, 0.0F);
        worker.onInitialSpawn(ws.getDifficultyForLocation(st.pos1), null);

        if (!ws.spawnEntity(worker)) {
            throw new CommandException("Could not spawn the test worker at " + posString(st.pos1) + ".");
        }

        worker.setTargetVillage(st.pos2);
        // A real worker only ever gets EntityAITravel.shouldBeUsingTravelAI() == true through a route
        // being planned or readyToDepart being set elsewhere first - setTargetVillage() alone leaves
        // both false. Without this, EntityTravellerWorker.onLivingUpdate() calls
        // setUsingTravelAI(shouldBeUsingTravelAI()) every tick and forces it back to false the instant
        // after EntityAITravel.startExecuting() sets it true, so shouldContinueExecuting() fails,
        // vanilla stops and immediately restarts the task, and the entity never gets past its first
        // path node - spinning in place instead of travelling. Measured: a debug-test worker cycling
        // through the same ~9-tick position loop for 500 ticks straight, on foot and mounted alike.
        worker.getTravelCommon().setReadyToDepart(true);
        if (forceWaypoints) {
            worker.getTravelAI().setForcedWaypoints(st.marks);
        }

        boolean mounted = false;
        if (mount) {
            mounted = TravellerMountSupport.bondNewMount(worker);
        }

        int arriveRadius = Math.max(3, worker.arrivalRadiusBlocks(st.pos2));
        worker.tasks.addTask(0, new PathTestWatchdogAI(worker, st.pos2, arriveRadius, MAX_TEST_TICKS));

        TraceSession session = TravellerTracer.start(worker, TravellerTracer.getDefaultChannels());

        st.activeEntityUuid = worker.getUniqueID();
        st.lastMounted = mounted;
        st.lastTraceFile = session != null ? session.getFile() : null;
        OWNER_OF_ENTITY.put(worker.getUniqueID(), player.getUniqueID());

        return "Path test started: " + posString(st.pos1) + " -> " + posString(st.pos2)
                + (mount ? (mounted ? " (mounted)" : " (mount request FAILED, testing on foot)") : "")
                + (forceWaypoints ? " (following " + st.marks.size() + " hand-marked waypoint(s))" : "")
                + (session != null
                        ? ", tracing to logs/travellers-trace/" + session.getFile().getName()
                        : " - WARNING: trace could not start");
    }

    /** Stops the current test entity and its trace, if any. Returns null if nothing was running. */
    @Nullable
    public static String stopTest(EntityPlayerMP player) {
        State st = state(player);
        if (st.activeEntityUuid == null) return null;
        UUID id = st.activeEntityUuid;

        String summary = TravellerTracer.stop(player.getServer(), id, "stopped by command");
        Entity e = player.getServerWorld().getEntityFromUuid(id);
        if (e != null) e.setDead();

        OWNER_OF_ENTITY.remove(id);
        st.activeEntityUuid = null;
        return summary != null ? summary : "Test entity removed (trace had already ended).";
    }

    /** Called by {@link PathTestWatchdogAI} when the test entity arrives or times out. */
    public static void finishActiveTest(EntityTraveller mob, String reason) {
        UUID mobId = mob.getUniqueID();
        UUID ownerId = OWNER_OF_ENTITY.remove(mobId);

        MinecraftServer server = mob.world.getMinecraftServer();
        String summary = TravellerTracer.stop(server, mobId, reason);
        mob.setDead();

        if (ownerId == null) return;
        State st = STATES.get(ownerId);
        if (st != null && mobId.equals(st.activeEntityUuid)) {
            st.activeEntityUuid = null;
        }

        if (server == null) return;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(ownerId);
        if (p == null) return;
        p.sendMessage(new TextComponentString(TextFormatting.GREEN + "Path test finished: " + reason));
        if (summary != null) {
            for (String line : summary.split("\n")) {
                p.sendMessage(new TextComponentString(TextFormatting.GRAY + line));
            }
        }
    }

    public static String posString(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
