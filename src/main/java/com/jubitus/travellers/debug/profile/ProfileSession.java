package com.jubitus.travellers.debug.profile;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.follow.EntityAIFollowLeader;
import com.jubitus.travellers.entity.entityai.travel.EntityAITravel;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.world.mill.MillSettlements;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** All counters of one profiling run. Server thread only. */
final class ProfileSession {

    static final int SAMPLE_TICKS = 20;
    /** A sample gap longer than this means the entity was not ticking (frozen, unloaded) - rebase instead of measuring. */
    private static final int MAX_SAMPLE_GAP = 60;
    /** Faster than this over one sample is a teleport, not walking. */
    private static final double TELEPORT_BLOCKS_PER_SECOND = 15.0D;
    private static final double STALL_BLOCKS_PER_SECOND = 0.3D;
    private static final int FORGET_AFTER_TICKS = 100;
    private static final int AFTER_MATERIALIZE_TICKS = 600;
    private static final double PARTIAL_PATH_TOLERANCE_SQ = 3.0D * 3.0D;

    final long startTick;
    final long startMillis;
    long lastCleanupTick;

    static final class Tally {
        long count;
        double sum;
        double max;

        void add(double v) {
            count++;
            sum += v;
            if (v > max) max = v;
        }

        double avg() {
            return count == 0 ? 0.0D : sum / count;
        }
    }

    static final class Motion {
        double seconds;
        double walked;
        double progress;
    }

    static final class FollowMotion {
        double seconds;
        double walked;
        long slotSamples;
        double slotSum;
        long lagging;
    }

    static final class PathStat {
        long count;
        long nanos;
        long maxNanos;
        long over5ms;
        long over20ms;
        long nulls;
        long partials;
    }

    static final class GroupStats {
        String label = "?";
        final Set<UUID> members = new HashSet<>();
        long lastSeenTick;
        long samples;
        long mountedSamples;
        long slotSamples;
        double slotSum;
        long lagging;
        long stallEpisodes;
        double stallSeconds;
        long rescues;
        long anomalies;
        long schedulerDenials;
    }

    static final class Hotspot {
        final int cellX;
        final int cellZ;
        String biome = "?";
        long events;
        double stallSeconds;
        final Map<String, Integer> kinds = new HashMap<>();

        Hotspot(int cellX, int cellZ) {
            this.cellX = cellX;
            this.cellZ = cellZ;
        }

        double score() {
            return events + stallSeconds / 5.0D;
        }
    }

    static final class Episode {
        String name;
        String unit;
        String role;
        String mount;
        String ai;
        String biome;
        String place;
        boolean water;
        boolean combat;
        String groupSize;
        String groupLabel;
        int x, y, z;
        double seconds;
        boolean afterMaterialize;
        boolean ongoing;
    }

    static final class EntityState {
        long lastSeenTick;
        long lastSampleTick;
        double lastX, lastZ;
        String ai = "none";
        String mount = "on foot";
        @Nullable BlockPos goal;
        double goalDist;
        double stallSeconds;
        @Nullable Episode episode;
        @Nullable UUID groupKey;
        int groupSize = 1;
        long materializedTick = -1;
        @Nullable MovementAnomaly.LedReason ledReason;
        long ledStartTick;
    }

    final Map<UUID, EntityState> states = new HashMap<>();
    final Map<UUID, Long> recentMaterialize = new HashMap<>();

    final Map<String, Double> presence = new TreeMap<>();
    final Map<String, Motion> travelMotion = new TreeMap<>();
    final Map<String, FollowMotion> followMotion = new TreeMap<>();

    final Tally stallEpisodes = new Tally();
    double stalledSeconds;
    final long[] stallHistogram = new long[4];
    long stallsAfterMaterialize;
    final Map<String, Map<String, Tally>> stallBy = new java.util.LinkedHashMap<>();
    final List<Episode> worst = new ArrayList<>();

    final EnumMap<MovementAnomaly, Long> anomalies = new EnumMap<>(MovementAnomaly.class);
    final EnumMap<MovementAnomaly, Map<String, Integer>> anomalySplit = new EnumMap<>(MovementAnomaly.class);

    final EnumMap<MovementAnomaly.LedReason, Long> ledStarts = new EnumMap<>(MovementAnomaly.LedReason.class);
    final EnumMap<MovementAnomaly.LedReason, Tally> ledDurations = new EnumMap<>(MovementAnomaly.LedReason.class);

    /** [priority 0..2][result 0..4], see MovementProfiler.TRAVEL_* */
    final long[][] travelSchedule = new long[3][5];
    long travelFullTicks;
    private long lastTravelFullTick = -1;

    final Map<String, Long> followSchedule = new TreeMap<>();
    long followCapTicks;
    private long lastFollowCapTick = -1;

    final Map<String, PathStat> paths = new TreeMap<>();
    private long pathTick = -1;
    private long pathTickNanos;
    long pathMaxTickNanos;
    long pathTicksWithSearch;
    long pathTotalNanos;

    long materialized;
    long materializeRefused;
    final Map<String, Long> virtualizeReasons = new TreeMap<>();

    final Map<UUID, GroupStats> groups = new HashMap<>();
    final Map<Long, Hotspot> hotspots = new HashMap<>();

    ProfileSession(long startTick) {
        this.startTick = startTick;
        this.startMillis = System.currentTimeMillis();
        this.lastCleanupTick = startTick;
        for (String dim : new String[]{"movement AI", "biome", "unit", "role", "mount", "place",
                "in water", "in combat", "group size"}) {
            stallBy.put(dim, new TreeMap<>());
        }
    }

    // ---- per-entity sampling ----

    void tick(EntityTraveller e, long now) {
        UUID id = e.getUniqueID();
        EntityState st = states.get(id);
        if (st == null) {
            st = new EntityState();
            st.lastSampleTick = now;
            st.lastX = e.posX;
            st.lastZ = e.posZ;
            Long mat = recentMaterialize.remove(id);
            if (mat != null) st.materializedTick = mat;
            states.put(id, st);
        }
        st.lastSeenTick = now;
        if (now - lastCleanupTick >= FORGET_AFTER_TICKS) cleanup(now);
        if (now - st.lastSampleTick >= SAMPLE_TICKS) sample(e, st, now);
    }

    private void sample(EntityTraveller e, EntityState st, long now) {
        long dt = now - st.lastSampleTick;
        double dx = e.posX - st.lastX;
        double dz = e.posZ - st.lastZ;
        double disp = Math.sqrt(dx * dx + dz * dz);
        st.lastSampleTick = now;
        st.lastX = e.posX;
        st.lastZ = e.posZ;
        if (dt > MAX_SAMPLE_GAP) {
            closeEpisode(st, now);
            st.stallSeconds = 0.0D;
            st.goal = null;
            return;
        }
        double secs = dt / 20.0D;
        boolean teleported = disp > TELEPORT_BLOCKS_PER_SECOND * secs;
        if (teleported) anomaly(e, MovementAnomaly.TELEPORT_SEEN, now);

        EntityAIBase moveTask = e.getRunningMovementTask();
        st.ai = moveTask == null ? "none" : shortName(moveTask);

        boolean mounted = e.isMountedOnBondedMount();
        boolean led = !mounted && e.getMountUUID() != null && e.getMountMode() == MountMode.LED;
        st.mount = mounted ? "mounted" : led ? "leading horse" : "on foot";
        String unit = unitOf(e);
        presence.merge(unit + " / " + st.mount, secs, Double::sum);

        st.groupKey = null;
        st.groupSize = 1;
        if (moveTask instanceof EntityAIFollowLeader) {
            sampleFollower(e, st, (EntityAIFollowLeader) moveTask, mounted, secs, teleported ? 0.0D : disp, now);
            st.goal = null;
        } else if (moveTask instanceof EntityAITravel) {
            sampleTravel(st, ((EntityAITravel) moveTask).getProfileGoal(), e, secs, disp, teleported);
        } else {
            st.goal = null;
        }

        boolean intent = e.moveForward > 0.02F || !e.getNavigator().noPath();
        EntityLivingBase target = e.getAttackTarget();
        if (target != null && e.getDistanceSq(target) < 16.0D) intent = false;
        boolean stalled = intent && !teleported && disp < STALL_BLOCKS_PER_SECOND * secs;

        if (!stalled) {
            closeEpisode(st, now);
            st.stallSeconds = 0.0D;
            return;
        }
        st.stallSeconds += secs;
        if (st.episode == null) {
            if (st.stallSeconds < TravellersConfig.TRACE.profileStallSeconds) return;
            st.episode = openEpisode(e, st, unit, target != null, mounted, now);
            stalledSeconds += st.stallSeconds;
        } else {
            stalledSeconds += secs;
        }
        st.episode.seconds = st.stallSeconds;
    }

    private void sampleTravel(EntityState st, @Nullable BlockPos goal, EntityTraveller e,
                              double secs, double disp, boolean teleported) {
        if (goal == null) {
            st.goal = null;
            return;
        }
        double gx = goal.getX() + 0.5D - e.posX;
        double gz = goal.getZ() + 0.5D - e.posZ;
        double dist = Math.sqrt(gx * gx + gz * gz);
        if (!teleported && goal.equals(st.goal)) {
            Motion m = travelMotion.computeIfAbsent("travel / " + st.mount, k -> new Motion());
            m.seconds += secs;
            m.walked += disp;
            m.progress += st.goalDist - dist;
        }
        st.goal = goal;
        st.goalDist = dist;
    }

    private void sampleFollower(EntityTraveller e, EntityState st, EntityAIFollowLeader task, boolean mounted,
                                double secs, double disp, long now) {
        double slot = task.getProfileSlotDistance();
        boolean lagging = slot >= TravellersConfig.TRACE.profileFollowerLagBlocks;
        FollowMotion fm = followMotion.computeIfAbsent("follow / " + st.mount, k -> new FollowMotion());
        fm.seconds += secs;
        fm.walked += disp;
        if (slot >= 0.0D) {
            fm.slotSamples++;
            fm.slotSum += slot;
            if (lagging) fm.lagging++;
        }

        EntityLivingBase leader = e.getEffectiveFollowTarget();
        if (leader == null) return;
        GroupStats g = groups.computeIfAbsent(leader.getUniqueID(), k -> new GroupStats());
        g.label = labelOf(leader);
        g.lastSeenTick = now;
        g.members.add(e.getUniqueID());
        g.samples++;
        if (mounted) g.mountedSamples++;
        if (slot >= 0.0D) {
            g.slotSamples++;
            g.slotSum += slot;
            if (lagging) g.lagging++;
        }
        st.groupKey = leader.getUniqueID();
        st.groupSize = Math.max(e.getCachedGroupSize(), g.members.size());
    }

    private Episode openEpisode(EntityTraveller e, EntityState st, String unit, boolean combat, boolean mounted,
                                long now) {
        Episode ep = new Episode();
        BlockPos pos = e.getPosition();
        ep.name = e.getName();
        ep.unit = unit;
        ep.role = roleOf(e, st, now);
        ep.mount = st.mount;
        ep.ai = st.ai;
        ep.biome = biomeAt(e, pos);
        ep.place = MillSettlements.isInside(e.world, pos) ? "in settlement" : "outside settlements";
        Entity body = mounted && e.getRidingEntity() != null ? e.getRidingEntity() : e;
        ep.water = body.isInWater();
        ep.combat = combat;
        ep.groupSize = groupSizeBucket(st);
        GroupStats g = st.groupKey == null ? null : groups.get(st.groupKey);
        ep.groupLabel = g == null ? null : g.label;
        ep.x = pos.getX();
        ep.y = pos.getY();
        ep.z = pos.getZ();
        long startedTick = now - (long) (st.stallSeconds * 20.0D);
        ep.afterMaterialize = st.materializedTick >= 0 && startedTick - st.materializedTick <= AFTER_MATERIALIZE_TICKS;
        return ep;
    }

    private void closeEpisode(EntityState st, long now) {
        Episode ep = st.episode;
        if (ep == null) return;
        st.episode = null;
        ep.ongoing = false;
        double s = ep.seconds;
        stallEpisodes.add(s);
        stallHistogram[s < 5 ? 0 : s < 15 ? 1 : s < 60 ? 2 : 3]++;
        if (ep.afterMaterialize) stallsAfterMaterialize++;
        by("movement AI", ep.ai, s);
        by("biome", ep.biome, s);
        by("unit", ep.unit, s);
        by("role", ep.role, s);
        by("mount", ep.mount, s);
        by("place", ep.place, s);
        by("in water", ep.water ? "yes" : "no", s);
        by("in combat", ep.combat ? "yes" : "no", s);
        by("group size", ep.groupSize, s);

        Hotspot h = hotspot(ep.x, ep.z, ep.biome);
        h.stallSeconds += s;
        h.kinds.merge("stall", 1, Integer::sum);

        if (st.groupKey != null) {
            GroupStats g = groups.get(st.groupKey);
            if (g != null) {
                g.stallEpisodes++;
                g.stallSeconds += s;
            }
        }
        insertWorst(ep);
    }

    private void by(String dim, String key, double seconds) {
        stallBy.get(dim).computeIfAbsent(key, k -> new Tally()).add(seconds);
    }

    private void insertWorst(Episode ep) {
        int limit = TravellersConfig.TRACE.profileWorstCases;
        int i = 0;
        while (i < worst.size() && worst.get(i).seconds >= ep.seconds) i++;
        if (i >= limit) return;
        worst.add(i, ep);
        while (worst.size() > limit) worst.remove(worst.size() - 1);
    }

    private void cleanup(long now) {
        lastCleanupTick = now;
        Iterator<Map.Entry<UUID, EntityState>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            EntityState st = it.next().getValue();
            if (now - st.lastSeenTick < FORGET_AFTER_TICKS) continue;
            closeEpisode(st, now);
            it.remove();
        }
        recentMaterialize.values().removeIf(t -> now - t > AFTER_MATERIALIZE_TICKS);
    }

    /** Closes every open episode, for the final report. */
    void closeAll(long now) {
        for (EntityState st : states.values()) closeEpisode(st, now);
    }

    List<Episode> openEpisodes() {
        List<Episode> out = new ArrayList<>();
        for (EntityState st : states.values()) {
            if (st.episode == null) continue;
            st.episode.ongoing = true;
            out.add(st.episode);
        }
        return out;
    }

    // ---- event hooks ----

    EntityState state(EntityTraveller e, long now) {
        EntityState st = states.get(e.getUniqueID());
        if (st == null) {
            st = new EntityState();
            st.lastSampleTick = now;
            st.lastSeenTick = now;
            st.lastX = e.posX;
            st.lastZ = e.posZ;
            states.put(e.getUniqueID(), st);
        }
        return st;
    }

    void anomaly(EntityTraveller e, MovementAnomaly a, long now) {
        EntityState st = state(e, now);
        anomalies.merge(a, 1L, Long::sum);
        anomalySplit.computeIfAbsent(a, k -> new TreeMap<>())
                .merge(unitOf(e) + " / " + st.mount, 1, Integer::sum);
        BlockPos pos = e.getPosition();
        hotspot(pos.getX(), pos.getZ(), biomeAt(e, pos)).kinds.merge(a.name().toLowerCase(), 1, Integer::sum);
        if (st.groupKey != null) {
            GroupStats g = groups.get(st.groupKey);
            if (g != null) {
                g.anomalies++;
                if (a == MovementAnomaly.FOLLOW_CAVE_RESCUE || a == MovementAnomaly.TRAVEL_RESCUE_TP) {
                    g.rescues++;
                }
            }
        }
    }

    /** Called just before the switch to LED; an entity already leading its horse is not a new LED. */
    void ledStarted(EntityTraveller e, MovementAnomaly.LedReason reason, long now) {
        if (e.getMountUUID() == null || e.getMountMode() == MountMode.LED) return;
        EntityState st = state(e, now);
        ledStarts.merge(reason, 1L, Long::sum);
        st.ledReason = reason;
        st.ledStartTick = now;
        BlockPos pos = e.getPosition();
        hotspot(pos.getX(), pos.getZ(), biomeAt(e, pos)).kinds.merge("led_" + reason.name().toLowerCase(), 1, Integer::sum);
    }

    void ledEnded(EntityTraveller e, long now) {
        EntityState st = states.get(e.getUniqueID());
        if (st == null || st.ledReason == null) return;
        ledDurations.computeIfAbsent(st.ledReason, k -> new Tally()).add((now - st.ledStartTick) / 20.0D);
        st.ledReason = null;
    }

    void travelSchedule(int priority, int result, long now) {
        travelSchedule[Math.max(0, Math.min(2, priority))][result]++;
        if (result >= MovementProfiler.TRAVEL_FULL_NORMAL && lastTravelFullTick != now) {
            lastTravelFullTick = now;
            travelFullTicks++;
        }
    }

    void followSchedule(String result, boolean mounted, boolean globalCap, @Nullable UUID owner,
                        boolean denied, long now) {
        followSchedule.merge(result + " / " + (mounted ? "mounted" : "on foot"), 1L, Long::sum);
        if (globalCap && lastFollowCapTick != now) {
            lastFollowCapTick = now;
            followCapTicks++;
        }
        if (denied && owner != null) {
            GroupStats g = groups.get(owner);
            if (g != null) g.schedulerDenials++;
        }
    }

    void pathSearch(EntityLivingBase e, long nanos, @Nullable Path path, double tx, double ty, double tz,
                    boolean probe, long now) {
        EntityState st = states.get(e.getUniqueID());
        String ai = st == null ? "unknown" : st.ai;
        boolean mounted = e instanceof EntityTraveller && ((EntityTraveller) e).isMountedOnBondedMount();
        String key = ai + " / " + (mounted ? "mounted" : "on foot") + (probe ? " / reachability probe" : "");
        PathStat ps = paths.computeIfAbsent(key, k -> new PathStat());
        ps.count++;
        ps.nanos += nanos;
        if (nanos > ps.maxNanos) ps.maxNanos = nanos;
        if (nanos > 5_000_000L) ps.over5ms++;
        if (nanos > 20_000_000L) ps.over20ms++;
        if (path == null) {
            ps.nulls++;
        } else {
            PathPoint end = path.getFinalPathPoint();
            if (end != null) {
                double dx = end.x + 0.5D - tx;
                double dz = end.z + 0.5D - tz;
                if (dx * dx + dz * dz > PARTIAL_PATH_TOLERANCE_SQ) ps.partials++;
            }
        }
        pathTotalNanos += nanos;
        if (pathTick != now) {
            pathTick = now;
            pathTickNanos = 0L;
            pathTicksWithSearch++;
        }
        pathTickNanos += nanos;
        if (pathTickNanos > pathMaxTickNanos) pathMaxTickNanos = pathTickNanos;
    }

    void materialized(UUID id, boolean ok, long now) {
        if (!ok) {
            materializeRefused++;
            return;
        }
        materialized++;
        recentMaterialize.put(id, now);
    }

    void virtualized(String reason) {
        virtualizeReasons.merge(reason, 1L, Long::sum);
    }

    // ---- helpers ----

    private Hotspot hotspot(int x, int z, @Nullable String biome) {
        int cx = x >> 6;
        int cz = z >> 6;
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        Hotspot h = hotspots.computeIfAbsent(key, k -> new Hotspot(cx, cz));
        h.events++;
        if (biome != null) h.biome = biome;
        return h;
    }

    private String roleOf(EntityTraveller e, EntityState st, long now) {
        if (st.groupKey != null) return "follower";
        GroupStats g = groups.get(e.getUniqueID());
        if (g != null && now - g.lastSeenTick < FORGET_AFTER_TICKS) return "group leader";
        return "solo";
    }

    private static String groupSizeBucket(EntityState st) {
        int n = st.groupSize;
        if (st.groupKey == null) return "not following";
        if (n <= 5) return "2-5";
        if (n <= 20) return "6-20";
        if (n <= 50) return "21-50";
        return "51+";
    }

    static String unitOf(EntityTraveller e) {
        if (e instanceof EntityTravellerRaider) return "raider";
        if (e instanceof EntityTravellerWorker) {
            return ((EntityTravellerWorker) e).isHiredFollower() ? "hired worker" : "worker";
        }
        return "traveller";
    }

    private static String labelOf(EntityLivingBase leader) {
        if (leader instanceof EntityPlayer) return "player " + leader.getName();
        if (leader instanceof EntityTraveller) {
            return leader.getName() + " (" + unitOf((EntityTraveller) leader) + ")";
        }
        return leader.getName();
    }

    private static String shortName(EntityAIBase task) {
        String n = task.getClass().getSimpleName();
        return n.startsWith("EntityAI") ? n.substring("EntityAI".length()) : n;
    }

    private static String biomeAt(EntityTraveller e, BlockPos pos) {
        Biome b = e.world.getBiome(pos);
        return b.getRegistryName() == null ? "?" : b.getRegistryName().getPath();
    }
}
