package com.jubitus.travellers.debug;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.worldmap.stream.ForeignUnitMapStream;
import com.jubitus.travellers.worldmap.stream.PopulationMapStream;
import com.jubitus.travellers.worldmap.stream.WorkerMapStream;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * While any worldmap is open, samples every unit (loaded or virtual) once per map update and writes
 * to logs/travellers/mapjumps.log each move faster than any unit may legitimately go, with the
 * loaded/virtual hand-off and the materialize placement that explain it.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class MapJumpLog {

    private static final int EVERY_TICKS = 20;
    /** Margin over the fastest configured pace before a move counts as a jump. */
    private static final double SPEED_MARGIN = 1.15;
    /** Map positions are whole blocks: allow that much rounding on top of the pace. */
    private static final double ROUNDING_SLACK_BLOCKS = 1.5;
    /** The client glides anything shorter than this; longer jumps are shown at once. */
    private static final double CLIENT_SNAP_BLOCKS = 48.0;
    private static final long PLACEMENT_MEMORY_TICKS = 60L;
    private static final int MAX_LINES_PER_MINUTE = 200;

    private static final class Sample {
        int dim;
        double x, z;
        boolean virtual;
        String name;
        String tags;
        boolean onBoat;
        UUID leaderId;
    }

    private static final class Placement {
        long tick;
        double fromX, fromZ, toX, toZ;
        String via;
    }

    private static Map<UUID, Sample> last = new HashMap<>();
    private static final Map<UUID, Placement> placements = new HashMap<>();
    private static BufferedWriter writer;
    private static long minuteStartTick;
    private static int linesThisMinute;

    private MapJumpLog() {
    }

    private static boolean active() {
        return WorkerMapStream.hasSubscribers() || PopulationMapStream.hasSubscribers()
                || ForeignUnitMapStream.hasSubscribers();
    }

    /** A record has just been put into the world at {@code to}, having been virtual at {@code from}. */
    public static void materialized(UUID id, long serverTick, double fromX, double fromZ,
                                    double toX, double toZ, String via) {
        if (!active()) return;
        Placement p = new Placement();
        p.tick = serverTick;
        p.fromX = fromX;
        p.fromZ = fromZ;
        p.toX = toX;
        p.toZ = toZ;
        p.via = via;
        placements.put(id, p);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        if (!active()) {
            if (!last.isEmpty()) last = new HashMap<>();
            placements.clear();
            closeWriter();
            return;
        }
        long tick = server.getTickCounter();
        if (tick % EVERY_TICKS != 0) return;

        Map<UUID, Sample> now = new HashMap<>();
        for (WorldServer ws : server.worlds) {
            for (EntityTraveller t : ws.getEntities(EntityTraveller.class, x -> x != null && x.isEntityAlive())) {
                Sample s = new Sample();
                s.dim = ws.provider.getDimension();
                s.x = t.posX;
                s.z = t.posZ;
                s.virtual = false;
                s.name = t.getName();
                s.tags = loadedTags(t);
                s.onBoat = t.getRidingEntity() instanceof EntityTravellerBoat;
                now.put(t.getUniqueID(), s);
            }
        }
        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            for (VirtualTravellerRecord r : VirtualTravellerManager.get(overworld).getAllRecords()) {
                Sample s = new Sample();
                s.dim = r.dimension;
                s.x = r.posX;
                s.z = r.posZ;
                s.virtual = true;
                s.name = r.name;
                s.tags = virtualTags(r);
                Itinerary it = r.itinerary.get();
                s.onBoat = it != null && r.itinerary.cursor.kind(it) == Itinerary.BOAT;
                s.leaderId = r.groupLeaderUUID != null ? r.groupLeaderUUID : r.escortLeaderUUID;
                if (now.put(r.uuid, s) != null) write(server, tick, "DUPLICATE " + r.name + " " + r.uuid
                        + " is both loaded and virtual");
            }
        }

        // A follower keeps up with a leader crossing by boat.
        for (Sample s : now.values()) {
            Sample leader = s.leaderId == null ? null : now.get(s.leaderId);
            if (leader != null && leader.onBoat && !s.onBoat) {
                s.onBoat = true;
                s.tags += ",leaderAfloat";
            }
        }
        double maxPercent = Math.max(TravellersConfig.SPEED.attackPercent,
                Math.max(TravellersConfig.SPEED.mountedPercent, TravellersConfig.SPEED.followMaxPercent));
        double limit = UnitSpeed.blocksPerTick(maxPercent * SPEED_MARGIN) * EVERY_TICKS + ROUNDING_SLACK_BLOCKS;
        // Offscreen boats legitimately outrun everything on foot.
        double boatLimit = Math.max(limit, UnitSpeed.blocksPerTick(UnitSpeed.travelPercent()
                * TravellersConfig.ROUTING.boatVirtualSpeedMultiplier * SPEED_MARGIN) * EVERY_TICKS + ROUNDING_SLACK_BLOCKS);
        for (Map.Entry<UUID, Sample> en : now.entrySet()) {
            Sample before = last.get(en.getKey());
            Sample after = en.getValue();
            if (before == null || before.dim != after.dim) continue;
            double dx = after.x - before.x;
            double dz = after.z - before.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= (before.onBoat || after.onBoat ? boatLimit : limit)) continue;
            write(server, tick, describe(en.getKey(), before, after, dist, tick));
        }
        last = now;
        placements.values().removeIf(p -> tick - p.tick > PLACEMENT_MEMORY_TICKS);
        flush();
    }

    private static String describe(UUID id, Sample before, Sample after, double dist, long tick) {
        StringBuilder sb = new StringBuilder();
        double percent = UnitSpeed.percentOfBlocksPerTick(dist / EVERY_TICKS);
        sb.append("JUMP ").append(after.name).append(' ').append(id)
                .append(String.format(" dist=%.1f speed=%.0f%%", dist, percent))
                .append(" from=").append((int) Math.floor(before.x)).append(',').append((int) Math.floor(before.z))
                .append(" to=").append((int) Math.floor(after.x)).append(',').append((int) Math.floor(after.z))
                .append(" hand=").append(before.virtual ? "virtual" : "loaded").append('>')
                .append(after.virtual ? "virtual" : "loaded")
                .append(" map=").append(dist > CLIENT_SNAP_BLOCKS ? "snapped" : "glided")
                .append(" before=[").append(before.tags).append(']')
                .append(" after=[").append(after.tags).append(']');
        Placement p = placements.get(id);
        if (p != null) {
            double px = p.toX - p.fromX, pz = p.toZ - p.fromZ;
            sb.append(String.format(" materialize=[via=%s moved=%.1f ticksAgo=%d]",
                    p.via, Math.sqrt(px * px + pz * pz), tick - p.tick));
        }
        return sb.toString();
    }

    private static String loadedTags(EntityTraveller t) {
        StringBuilder sb = new StringBuilder(t instanceof EntityTravellerWorker ? "worker" : t.getClass().getSimpleName());
        if (t.getAttackTarget() != null) sb.append(",fighting");
        if (t.isMountedOnBondedMount()) sb.append(",mounted");
        if (t.getRidingEntity() instanceof EntityTravellerBoat) sb.append(",boat");
        if (t.isElytraFlying()) sb.append(",flying");
        if (t.getGroupLeaderUUID() != null) sb.append(",groupFollower");
        if (t instanceof EntityTravellerWorker && ((EntityTravellerWorker) t).getEscortLeaderUUID() != null) {
            sb.append(",escortFollower");
        }
        if (t.isHiredFollower()) sb.append(",hiredFollower");
        if (t.isMountedOnBondedMount() && t.getRidingEntity() instanceof net.minecraft.entity.EntityLivingBase) {
            net.minecraft.entity.EntityLivingBase horse = (net.minecraft.entity.EntityLivingBase) t.getRidingEntity();
            double hv = Math.sqrt(horse.motionX * horse.motionX + horse.motionZ * horse.motionZ);
            sb.append(String.format(",riderAi=%.3f,riderFwd=%.2f,horseAttr=%.3f,horseNow=%.0f%%,horseGround=%b",
                    t.getAIMoveSpeed(), t.moveForward,
                    horse.getEntityAttribute(net.minecraft.entity.SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue(),
                    UnitSpeed.percentOfBlocksPerTick(hv), horse.onGround));
        }
        return sb.toString();
    }

    private static String virtualTags(VirtualTravellerRecord r) {
        StringBuilder sb = new StringBuilder(r.entityClass == null ? "?" : r.entityClass.toLowerCase());
        if (r.battleId != null) sb.append(",battle");
        if (r.campSiteId != null) sb.append(",camp");
        if (r.mounted) sb.append(",mounted:").append(r.mountMode == null ? "MOUNTED" : r.mountMode);
        Itinerary it = r.itinerary.get();
        if (it != null && r.itinerary.cursor.kind(it) == Itinerary.BOAT) sb.append(",boatLeg");
        if (r.groupLeaderUUID != null) sb.append(",groupFollower");
        if (r.escortLeaderUUID != null) sb.append(",escortFollower");
        if (r.goal != null) {
            double gx = r.goal.getX() + 0.5 - r.posX, gz = r.goal.getZ() + 0.5 - r.posZ;
            sb.append(String.format(",goalDist=%.0f", Math.sqrt(gx * gx + gz * gz)));
        }
        return sb.toString();
    }

    private static void write(MinecraftServer server, long tick, String line) {
        if (tick - minuteStartTick >= 1200L) {
            minuteStartTick = tick;
            linesThisMinute = 0;
        }
        if (++linesThisMinute > MAX_LINES_PER_MINUTE) return;
        try {
            if (writer == null) {
                File file = com.jubitus.travellers.debug.DebugFiles.file("mapjumps.log");
                file.getParentFile().mkdirs();
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
                writer.write("# map opened " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                        + String.format(" limit=%.0f%% of a walking player\n",
                        Math.max(TravellersConfig.SPEED.attackPercent, Math.max(TravellersConfig.SPEED.mountedPercent,
                                TravellersConfig.SPEED.followMaxPercent)) * SPEED_MARGIN));
            }
            writer.write(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " t=" + tick + " " + line + "\n");
        } catch (IOException ex) {
            closeWriter();
        }
    }

    private static void flush() {
        if (writer == null) return;
        try {
            writer.flush();
        } catch (IOException ex) {
            closeWriter();
        }
    }

    private static void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
        writer = null;
    }
}
