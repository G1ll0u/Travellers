package com.jubitus.travellers.debug.profile;

import com.jubitus.travellers.follow.PathScheduler;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.pathfinding.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.io.File;
import java.util.UUID;

/**
 * Long-session movement profiler: aggregates stalls, rescues, LED switches, path scheduler verdicts,
 * A* cost and the virtual/loaded boundary into one short report. Every hook is a single null check
 * while no session is running. Server thread only.
 */
public final class MovementProfiler {

    public static final int TRAVEL_GRANTED = 0;
    public static final int TRAVEL_NOT_ITS_TURN = 1;
    public static final int TRAVEL_FULL_NORMAL = 2;
    public static final int TRAVEL_FULL_URGENT = 3;

    @Nullable
    private static ProfileSession session;
    @Nullable
    private static MinecraftServer server;

    private MovementProfiler() {
    }

    public static boolean isActive() {
        return session != null;
    }

    public static boolean start(MinecraftServer srv) {
        if (session != null) return false;
        server = srv;
        session = new ProfileSession(srv.getTickCounter());
        return true;
    }

    /** Writes an intermediate report without stopping. */
    @Nullable
    public static File report() {
        ProfileSession s = session;
        if (s == null || server == null) return null;
        return ProfileReport.write(server, s, now(), false);
    }

    @Nullable
    public static File stop() {
        ProfileSession s = session;
        if (s == null || server == null) return null;
        long now = now();
        s.closeAll(now);
        File f = ProfileReport.write(server, s, now, true);
        session = null;
        server = null;
        return f;
    }

    public static void shutdown() {
        if (session == null) return;
        File f = stop();
        if (f != null) FMLLog.log(Level.INFO, "[Travellers/Profile] server stopping, report written to %s", f.getName());
    }

    @Nullable
    public static String status() {
        ProfileSession s = session;
        if (s == null) return null;
        long ticks = now() - s.startTick;
        return String.format("running for %s, %d units tracked, %d stall episodes so far",
                ProfileReport.duration(ticks / 20L), s.states.size(), s.stallEpisodes.count);
    }

    private static long now() {
        MinecraftServer srv = server != null ? server : FMLCommonHandler.instance().getMinecraftServerInstance();
        return srv == null ? 0L : srv.getTickCounter();
    }

    // ---- hooks ----

    public static void tick(EntityTraveller e) {
        if (session == null || e.world.isRemote) return;
        session.tick(e, now());
    }

    public static void anomaly(EntityTraveller e, MovementAnomaly anomaly) {
        if (session == null || e.world.isRemote) return;
        session.anomaly(e, anomaly, now());
    }

    public static void wedge(EntityTraveller e, com.jubitus.travellers.debug.trace.MountObstacle.Kind kind, double seconds) {
        if (session == null || e.world.isRemote) return;
        session.wedge(e, kind, seconds, now());
    }

    public static void ledStarted(EntityTraveller e, MovementAnomaly.LedReason reason) {
        if (session == null || e.world.isRemote) return;
        session.ledStarted(e, reason, now());
    }

    public static void ledEnded(EntityTraveller e) {
        if (session == null || e.world.isRemote) return;
        session.ledEnded(e, now());
    }

    public static void travelSchedule(int priority, com.jubitus.travellers.pathing.PathBudget.Result result) {
        if (session == null) return;
        int code = result == com.jubitus.travellers.pathing.PathBudget.Result.GRANTED ? TRAVEL_GRANTED
                : result == com.jubitus.travellers.pathing.PathBudget.Result.NOT_ITS_TURN ? TRAVEL_NOT_ITS_TURN
                : priority >= 2 ? TRAVEL_FULL_URGENT : TRAVEL_FULL_NORMAL;
        session.travelSchedule(priority, code, now());
    }

    public static void followSchedule(PathScheduler.AcquireResult result, boolean mounted,
                                      @Nullable EntityLivingBase owner) {
        if (session == null) return;
        boolean globalCap = result == PathScheduler.AcquireResult.DENIED_GLOBAL_LIMIT
                || result == PathScheduler.AcquireResult.DENIED_GLOBAL_MOUNTED_LIMIT;
        UUID ownerId = owner == null ? null : owner.getUniqueID();
        session.followSchedule(result.name(), mounted, globalCap, ownerId,
                PathScheduler.wasSchedulerDenial(result), now());
    }

    public static void pathSearch(EntityLivingBase e, long nanos, @Nullable Path path,
                                  double tx, double ty, double tz, boolean probe) {
        if (session == null || e.world.isRemote) return;
        session.pathSearch(e, nanos, path, tx, ty, tz, probe, now());
    }

    /** One tick of {@code steerAround}: straight, around an obstacle, or refused (with the straight leg's reason). */
    public static void directOutcome(EntityLivingBase e, String outcome) {
        if (session == null || e.world.isRemote) return;
        session.directOutcome(e, outcome);
    }

    /** A path request answered by direct steering instead of an A* search. */
    public static void searchAvoided(EntityLivingBase e) {
        if (session == null || e.world.isRemote) return;
        session.searchAvoided(e);
    }

    public static void raidScan(int inBox, int preferred, int rays, boolean found, long nanos) {
        if (session == null) return;
        session.raidScan(inBox, preferred, rays, found, nanos);
    }

    /** One attack-target decision: "adopted", "refused" (lock held) or the verdict that allowed a switch. */
    public static void targetChange(String kind) {
        if (session == null) return;
        session.targetChange(kind);
    }

    /** A live attack target dropped, with the reason noted by the code that dropped it. */
    public static void targetDrop(String reason) {
        if (session == null) return;
        session.targetDrop(reason);
    }

    /** A target dropped as too far, keyed by who gave it and whether it was already beyond the limit then. */
    public static void tooFarDrop(String adoptedBy) {
        if (session == null) return;
        session.tooFarDrops.merge(adoptedBy, 1L, Long::sum);
    }

    /** A target task another mod injected into one of our units, removed by ForeignTargetTaskScrubber. */
    public static void foreignTargetTask(String className) {
        if (session == null) return;
        session.foreignTargetTask(className);
    }

    /** One help call (call for help, worker rally) and how many idle allies it pulled in. */
    public static void helpCall(String kind, int recruited) {
        if (session == null) return;
        session.helpCall(kind, recruited);
    }

    /** One world query for threats: entities returned and its cost. */
    public static void threatScan(String kind, int found, long nanos) {
        if (session == null) return;
        session.threatScan(kind, found, nanos);
    }

    /** A unit starts a village stay (loaded or offscreen); entityClass is the record's TRAVELLER / RAIDER / WORKER. */
    public static void villageVisit(net.minecraft.world.World world, UUID unit, net.minecraft.util.math.BlockPos stop, boolean lone,
                                    String entityClass, @Nullable String culture, boolean follower,
                                    boolean mounted, boolean virtual) {
        if (session == null || world.isRemote) return;
        String name = com.jubitus.travellers.village.MillSettlements.nameOrNull(world, stop);
        session.villageVisit(unit, stop, name != null ? name : stop.getX() + "," + stop.getZ(), lone, entityClass, culture,
                follower, mounted, virtual, now());
    }

    /** A recruiter asked a group: "joined", or why not ("quota", "purse", "culture", "refused" on the roll). */
    public static void recruitAsk(String outcome) {
        if (session == null) return;
        session.recruitAsk(outcome);
    }

    public static void materialized(UUID id, boolean ok) {
        if (session == null) return;
        session.materialized(id, ok, now());
    }

    public static void virtualized(String reason) {
        if (session == null) return;
        session.virtualized(reason);
    }
}
