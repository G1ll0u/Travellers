package com.jubitus.travellers.debug.trace;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.util.math.BlockPos;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The entry point for per-traveller tracing: who is being traced, the rolling pre-incident buffer
 * kept for everyone else, and the detection that promotes a rolling buffer into a real trace.
 *
 * <p><b>Why a rolling buffer rather than just a command.</b> The problems worth diagnosing here only
 * show up on some individuals out of many, at a moment nobody is watching - so a tool that requires
 * catching one in the act mostly records the ninety-nine that were fine. Every loaded traveller
 * therefore keeps the last few seconds of compact samples ({@link TraceSample}, primitives, no
 * formatting), and when one crosses an incident threshold that buffer is written out ahead of the
 * live trace. What you get is the approach to the problem, not just the aftermath.
 *
 * <p>Costs nothing when disabled - {@link #tick} returns on a boolean, and nothing is allocated for
 * an entity that never has anything happen to it beyond its own small ring of reused sample objects.
 */
public final class TravellerTracer {

    private TravellerTracer() {
    }

    // One unit stuck for minutes must not take every automatic slot: other incidents get captured too.
    private static final long AUTO_RECAPTURE_TICKS = 10L * 60L * 20L;

    /** Samples of context kept before an incident, at {@link #RING_SAMPLE_INTERVAL} ticks apart. */
    private static final int RING_SAMPLES = 60;
    /** Every loaded traveller is sampled this often for the rolling buffer - cheap, and still dense
     * enough to see how a stall built up. */
    private static final int RING_SAMPLE_INTERVAL = 4;

    private static final Map<UUID, TraceSession> SESSIONS = new HashMap<>();
    private static final Map<UUID, Deque<TraceSample>> RINGS = new HashMap<>();
    private static final Map<UUID, Integer> STALL_STREAK = new HashMap<>();
    private static final Map<UUID, Long> AUTO_COOLDOWN = new HashMap<>();
    /** Per entity: {x, z, tick, lastComputedDxz} at the previous sample. The only reliable source of
     * real displacement, see {@link TraceSample#capture}. */
    private static final Map<UUID, double[]> LAST_POS = new HashMap<>();

    /** Reused for every capture that will not be stored, so sampling allocates nothing in the common case. */
    private static final TraceSample SCRATCH = new TraceSample();

    private static EnumSet<TraceChannel> defaultChannels = TraceChannel.defaults();
    private static File folder;

    public static boolean isActive() {
        return !SESSIONS.isEmpty();
    }

    public static boolean isTraced(UUID id) {
        return SESSIONS.containsKey(id);
    }

    public static Collection<TraceSession> sessions() {
        return SESSIONS.values();
    }

    public static EnumSet<TraceChannel> getDefaultChannels() {
        return defaultChannels;
    }

    public static void setDefaultChannels(EnumSet<TraceChannel> channels) {
        defaultChannels = channels;
    }

    /**
     * Called once per traveller tick. Keeps the rolling buffer current, feeds any live session, and
     * decides whether an incident has just started.
     */
    public static void tick(EntityTraveller entity) {
        if (entity.world.isRemote) return;
        TravellersConfig.Trace cfg = TravellersConfig.TRACE;
        if (!cfg.enabled) return;

        UUID id = entity.getUniqueID();
        TraceSession session = SESSIONS.get(id);
        int tick = entity.ticksExisted;

        if (session != null) {
            if (tick % Math.max(1, cfg.sampleEveryTicks) == 0) {
                SCRATCH.capture(entity, tick, measureDxz(id, entity, tick));
                session.sample(SCRATCH);
            }
            if (tick % 100 == 0) session.flush();
            if (session.isExpired(entity.world.getTotalWorldTime())) {
                stop(entity.world.getMinecraftServer(), id, "auto capture window ended");
            }
            return;
        }

        if (!cfg.autoCapture) return;
        if (tick % RING_SAMPLE_INTERVAL != 0) return;

        SCRATCH.capture(entity, tick, measureDxz(id, entity, tick));
        pushRing(id, SCRATCH);
        checkForIncident(entity, cfg);
    }

    /**
     * Horizontal blocks covered per tick since this entity was last sampled. Normalised by the
     * elapsed ticks so the rolling buffer (sampled every {@link #RING_SAMPLE_INTERVAL} ticks) and a
     * live session (usually every tick) produce numbers on the same scale.
     */
    private static double measureDxz(UUID id, EntityTraveller entity, int tick) {
        double[] last = LAST_POS.get(id);
        double result = 0.0D;
        if (last != null) {
            double elapsed = tick - last[2];
            if (elapsed > 0.0D) {
                double dx = entity.posX - last[0];
                double dz = entity.posZ - last[1];
                result = Math.sqrt(dx * dx + dz * dz) / elapsed;
            }
        }
        LAST_POS.put(id, new double[]{entity.posX, entity.posZ, tick, result});
        return result;
    }

    /** The last displacement {@link #measureDxz} computed, without disturbing its baseline - for
     * readers like the live HUD that run on their own schedule and must not consume a sample. */
    private static double peekDxz(UUID id) {
        double[] last = LAST_POS.get(id);
        return last == null ? 0.0D : last[3];
    }

    private static void pushRing(UUID id, TraceSample sample) {
        Deque<TraceSample> ring = RINGS.get(id);
        if (ring == null) {
            ring = new ArrayDeque<>(RING_SAMPLES);
            RINGS.put(id, ring);
        }
        TraceSample slot = ring.size() >= RING_SAMPLES ? ring.removeFirst() : new TraceSample();
        slot.copyFrom(sample);
        ring.addLast(slot);
    }

    /**
     * Promotes a traveller to a real trace once it has been trying to move and getting nowhere for
     * long enough. Deliberately the only trigger for now: every mount problem found so far has had
     * exactly this signature, and a detector that fires on more things than it can explain just fills
     * the folder.
     */
    private static void checkForIncident(EntityTraveller entity, TravellersConfig.Trace cfg) {
        UUID id = entity.getUniqueID();
        if (!SCRATCH.isStalled()) {
            STALL_STREAK.remove(id);
            return;
        }

        int streak = STALL_STREAK.getOrDefault(id, 0) + RING_SAMPLE_INTERVAL;
        STALL_STREAK.put(id, streak);
        if (streak < cfg.incidentStallSeconds * 20.0D) return;
        STALL_STREAK.remove(id);

        long now = entity.world.getTotalWorldTime();
        Long cooldownUntil = AUTO_COOLDOWN.get(id);
        if (cooldownUntil != null && now < cooldownUntil) return;

        int running = 0;
        for (TraceSession s : SESSIONS.values()) if (s.automatic) running++;
        if (running >= cfg.autoCaptureMaxConcurrent) return;

        TraceSession session = open(entity, defaultChannels, true);
        if (session == null) return;

        Deque<TraceSample> ring = RINGS.get(id);
        if (ring != null) session.replay(ring);
        session.incident(entity.ticksExisted, "stall",
                "trying to move and going nowhere for " + String.format("%.1f", cfg.incidentStallSeconds) + "s"
                        + " wedged=" + (SCRATCH.mountWedged ? 1 : 0)
                        + " coll=" + (SCRATCH.collided || SCRATCH.horseCollided ? 1 : 0)
                        + " path=" + (SCRATCH.hasPath ? "yes" : "none")
                        + " ai=" + SCRATCH.runningTasks
                        + (entity.isMountedOnBondedMount() ? " " + MountWedgeWatch.describeFor(entity) : ""));
        writeTerrainSnapshot(session, entity);
        session.setAutoStopTick(now + cfg.autoCaptureSeconds * 20L);
        AUTO_COOLDOWN.put(id, now + AUTO_RECAPTURE_TICKS);

        FMLLog.log(Level.INFO, "[Travellers/Trace] auto-captured %s (stuck) -> %s",
                entity.getName(), session.getFile().getName());
    }

    /** Starts a manual trace. Returns null if one is already running for this entity, or on IO failure. */
    @Nullable
    public static TraceSession start(EntityTraveller entity, EnumSet<TraceChannel> channels) {
        if (SESSIONS.containsKey(entity.getUniqueID())) return null;
        return open(entity, channels, false);
    }

    @Nullable
    private static TraceSession open(EntityTraveller entity, EnumSet<TraceChannel> channels, boolean automatic) {
        try {
            File dir = resolveFolder(entity.world.getMinecraftServer());
            if (dir == null) return null;
            TraceSession session = new TraceSession(entity, channels, dir, automatic,
                    entity.world.getTotalWorldTime());
            SESSIONS.put(entity.getUniqueID(), session);
            return session;
        } catch (IOException e) {
            FMLLog.log(Level.WARN, "[Travellers/Trace] could not open a trace for %s: %s", entity.getName(), e);
            return null;
        }
    }

    /** Ends a trace and returns its summary, or null if that entity was not being traced. */
    @Nullable
    public static String stop(@Nullable MinecraftServer server, UUID id, String reason) {
        TraceSession session = SESSIONS.remove(id);
        if (session == null) return null;
        long now = server != null && server.getEntityWorld() != null
                ? server.getEntityWorld().getTotalWorldTime() : 0L;
        session.event((int) (now % Integer.MAX_VALUE), "trace", "stopped: " + reason);
        String summary = session.finish(now);
        FMLLog.log(Level.INFO, "[Travellers/Trace] %s -> %s\n%s",
                session.entityName, session.getFile().getName(), summary);
        return summary;
    }

    public static List<String> stopAll(@Nullable MinecraftServer server, String reason) {
        List<String> summaries = new ArrayList<>();
        for (UUID id : new ArrayList<>(SESSIONS.keySet())) {
            String s = stop(server, id, reason);
            if (s != null) summaries.add(s);
        }
        return summaries;
    }

    /** Records a one-off event on whichever trace is following this entity, if any. Safe to call from
     * anywhere - it is a no-op for an untraced entity, which is the overwhelming majority. */
    public static void event(EntityTraveller entity, String category, String detail) {
        TraceSession session = SESSIONS.get(entity.getUniqueID());
        if (session != null) session.event(entity.ticksExisted, category, detail);
    }

    /** As {@link #event}, but marked as something that went wrong rather than something that happened.
     * Every incident is followed by a picture of the ground around the entity - see
     * {@link #writeTerrainSnapshot}. */
    public static void incident(EntityTraveller entity, String category, String detail) {
        TraceSession session = SESSIONS.get(entity.getUniqueID());
        if (session == null) return;
        session.incident(entity.ticksExisted, category, detail);
        writeTerrainSnapshot(session, entity);
    }

    /**
     * Writes the local terrain as two small grids, because "it stopped here and there was no reason
     * to" is not answerable from positions and flags alone - every remaining mount problem has turned
     * out to be about a specific block somewhere near the entity, and reading the world back out of a
     * screenshot after the fact does not work. Only ever written on an incident, so its cost is
     * irrelevant. Shared with {@code /travellersdebug path export} via {@link TerrainSnapshot}.
     */
    private static void writeTerrainSnapshot(TraceSession session, EntityTraveller entity) {
        net.minecraft.entity.Entity body = com.jubitus.travellers.pathing.PathBody.of(entity);
        for (String line : TerrainSnapshot.lines(entity.world, body, entity.rotationYaw)) {
            session.raw("~" + line);
        }
    }

    /** Drops per-entity state for travellers that no longer exist, so a long session cannot grow the
     * ring map without bound. Called from the world tick hub. */
    public static void pruneStale(WorldServer world) {
        Iterator<Map.Entry<UUID, Deque<TraceSample>>> it = RINGS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Deque<TraceSample>> e = it.next();
            if (world.getEntityFromUuid(e.getKey()) == null) {
                it.remove();
                STALL_STREAK.remove(e.getKey());
                LAST_POS.remove(e.getKey());
            }
        }
        MountWedgeWatch.pruneStale(world);
        long now = world.getTotalWorldTime();
        AUTO_COOLDOWN.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** Everything stops when the server does, so no trace is ever left with an unwritten summary. */
    public static void shutdown(@Nullable MinecraftServer server) {
        stopAll(server, "server shutting down");
        RINGS.clear();
        STALL_STREAK.clear();
        AUTO_COOLDOWN.clear();
        folder = null;
    }

    @Nullable
    private static File resolveFolder(@Nullable MinecraftServer server) {
        if (folder != null) return folder;
        if (server == null) return null;
        File dir = com.jubitus.travellers.debug.DebugFiles.dir(com.jubitus.travellers.debug.DebugFiles.TRACE);
        if (!dir.exists() && !dir.mkdirs()) {
            FMLLog.log(Level.WARN, "[Travellers/Trace] could not create %s", dir.getAbsolutePath());
            return null;
        }
        folder = dir;
        return folder;
    }

    /**
     * Builds the on-screen panel's text for every traced traveller currently loaded in this world,
     * and collects their entity ids for the in-world markers. Server-side on purpose: navigator
     * state, the running AI task and the horse's collision flags simply do not exist on the client.
     */
    public static List<String> buildHudLines(WorldServer world, int[][] outIds) {
        List<String> lines = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (TraceSession session : SESSIONS.values()) {
            net.minecraft.entity.Entity found = world.getEntityFromUuid(session.entityId);
            if (!(found instanceof EntityTraveller)) continue;
            EntityTraveller entity = (EntityTraveller) found;
            ids.add(entity.getEntityId());

            SCRATCH.capture(entity, entity.ticksExisted, peekDxz(session.entityId));
            lines.add(session.entityName + (session.automatic ? " [auto]" : ""));
            lines.add(String.format(java.util.Locale.ROOT, "  pos %.1f,%.1f,%.1f  dxz %.2f  yaw %.0f%s",
                    SCRATCH.x, SCRATCH.y, SCRATCH.z, SCRATCH.dxz, SCRATCH.yaw,
                    SCRATCH.isStalled() ? "  STALLED" : ""));
            lines.add(SCRATCH.hasPath
                    ? String.format(java.util.Locale.ROOT, "  path %d/%d -> %d,%d,%d",
                    SCRATCH.pathIdx, SCRATCH.pathLen, SCRATCH.destX, SCRATCH.destY, SCRATCH.destZ)
                    : "  path none");
            if (SCRATCH.mounted) {
                lines.add(String.format(java.util.Locale.ROOT, "  mount %s attr %.3f%s%s",
                        SCRATCH.mountMode, SCRATCH.horseAttr,
                        SCRATCH.horseCollided ? " hitting" : "", SCRATCH.mountWedged ? " WEDGED" : ""));
            }
            lines.add("  ai " + SCRATCH.runningTasks
                    + (SCRATCH.steeringBlockedTicks >= 0 ? "  blocked " + SCRATCH.steeringBlockedTicks : ""));
        }
        outIds[0] = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) outIds[0][i] = ids.get(i);
        return lines;
    }
}
