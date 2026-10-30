package com.jubitus.travellers.follow;

import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.follow.formations.WorkerFormationStyle;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.pathing.PathBody;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lighter, loaded-side counterpart to {@link FollowFormationCoordinator} for plain traveller/raider
 * group followers - a real formation shape (see {@link WorkerFormationStyle}), without any of the
 * concerns that only apply to a player-owned worker escort: no cave/tunnel geometry (a group never
 * used it before this class existed), no escort slots, no GUI-configurable settings (a group's style
 * is rolled once at creation, see {@code TravellerSoftSpawner}), no persistent-ordinal stability
 * pass. Groups are small (militia/convoy, not a 40-member escort), so a full dense-rank recompute
 * every refresh is cheap enough that the stability machinery workers need has no equivalent here.
 * <p>
 * It also owns what both kinds of groups share on a route, per leader: the route anchor, the road file and
 * the leader's pace (worker escorts reach it through {@link #routeSlotForEscort}).
 */
public final class GroupFormationTracker {

    private static final long MEMBER_EXPIRY_TICKS = 40L;
    private static final long REFRESH_INTERVAL_TICKS = 20L;

    /** A leader that hasn't displaced at least this far (squared) since the last refresh is treated
     *  as stationary - the heading is held at its last real value rather than re-derived from a
     *  near-zero displacement vector, whose direction is noise (same trap the mounted-follow pacer
     *  documents for the identical reason). */
    private static final double HEADING_MOVE_EPSILON_SQ = 0.05D * 0.05D;

    private static final class LeaderContext {
        final Map<UUID, Long> registered = new HashMap<>();
        List<UUID> orderedIds = Collections.emptyList();
        double headingX = 1.0D;
        double headingZ = 0.0D;
        boolean hasHeading = false;
        long nextRefreshTick = 0L;
        long lastActiveTick = 0L;
        final GroupRouteAnchor anchor = new GroupRouteAnchor();
        final GroupRoadFile file = new GroupRoadFile();
        long fileTick = Long.MIN_VALUE;
        // The formation's distances and its escorts, set by whoever computes the formation (one tick behind).
        GroupRoadFile.Shape shape = GroupRoadFile.Shape.of(0.0D, WorkerFormationSettings.defaults(), 1.0D);
        final Set<UUID> escorts = new HashSet<>();
        long settingsTick = Long.MIN_VALUE;
        // Per follower: distance from the leader its place should be, plus slack (read by leaderPace).
        final Map<UUID, Double> expected = new HashMap<>();
        // Pace: recomputed every REFRESH_INTERVAL_TICKS; per lagging follower {best lag, tick it last improved}.
        double pace = 1.0D;
        long nextPaceTick = 0L;
        final Map<UUID, double[]> lagWatch = new HashMap<>();
    }

    private static final double[] ROUTE_HEADING = new double[2];
    private static final double FILE_ARRIVE_RADIUS = 0.9D;
    /**
     * Lag (blocks beyond a follower's place, on foot; scaled up on horseback) the leader ignores, and the lag at
     * which it is slowest. A walking follower normally trails its moving place by a few blocks: only a real gap
     * slows the leader, and only mildly.
     */
    private static final double LAG_OK = 8.0D;
    private static final double LAG_FULL_WAIT = 20.0D;
    private static final double MIN_PACE = 0.6D;
    private static final long STUCK_LAG_TICKS = 200L;

    private static void cleanupIfDue(long now) {
        if (now - lastCleanupTick < CLEANUP_INTERVAL_TICKS) return;
        lastCleanupTick = now;
        CONTEXTS.entrySet().removeIf(e -> now - e.getValue().lastActiveTick > CONTEXT_EXPIRY_TICKS);
    }

    // A leader whose context has had no active follower for this long is dropped outright - without
    // this, CONTEXTS grows by one entry per distinct group-leader UUID ever seen for the life of the
    // world (leaders die, groups dissolve, but nothing else here ever removes the map entry).
    private static final long CONTEXT_EXPIRY_TICKS = 6000L; // 5 minutes
    private static final long CLEANUP_INTERVAL_TICKS = 1200L; // 1 minute
    private static long lastCleanupTick = Long.MIN_VALUE;

    private static final Map<UUID, LeaderContext> CONTEXTS = new HashMap<>();

    private GroupFormationTracker() {
    }

    public static FollowFormationCoordinator.SlotAssignment getGroupSlot(EntityTraveller follower,
                                                                          EntityLivingBase leader,
                                                                          WorkerFormationStyle style,
                                                                          double baseFollowDistance,
                                                                          boolean mounted,
                                                                          long now) {
        LeaderContext ctx = touch(follower, leader, now);

        double leaderDx = com.jubitus.travellers.unit.EntityMotion.velX(leader);
        double leaderDz = com.jubitus.travellers.unit.EntityMotion.velZ(leader);
        double leaderSpeed = Math.sqrt(leaderDx * leaderDx + leaderDz * leaderDz);
        boolean leaderActuallyMoving = leaderSpeed > 0.005D;
        double bodyScale = mounted ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D;
        if (ctx.settingsTick != now) {
            ctx.settingsTick = now;
            ctx.shape = GroupRoadFile.Shape.of(baseFollowDistance, WorkerFormationSettings.defaults(), bodyScale);
        }
        FollowFormationCoordinator.SlotAssignment slot = formationSlot(ctx, follower, leader, style,
                baseFollowDistance, bodyScale, leaderSpeed, leaderActuallyMoving);
        recordExpected(ctx, follower, leader, slot);
        return slot;
    }

    private static FollowFormationCoordinator.SlotAssignment formationSlot(LeaderContext ctx, EntityTraveller follower,
                                                                           EntityLivingBase leader,
                                                                           WorkerFormationStyle style,
                                                                           double baseFollowDistance, double bodyScale,
                                                                           double leaderSpeed,
                                                                           boolean leaderActuallyMoving) {
        UUID leaderId = leader.getUniqueID();
        FollowFormationCoordinator.SlotAssignment file = fileSlot(ctx, follower, leader);
        if (file != null) return file;

        if (style == WorkerFormationStyle.RING) {
            FormationGeometry.SlotOffset off = FormationGeometry.computeRingOffset(follower.getUniqueID());
            if (ctx.anchor.isFrozen()) return pinned(ctx, Math.max(1, ctx.orderedIds.size()), off);
            return FollowFormationCoordinator.buildFixedSlot(Math.max(1, ctx.orderedIds.size()),
                    leaderSpeed, leaderActuallyMoving,
                    leader.posX + off.dx, leader.posY, leader.posZ + off.dz,
                    off, ctx.headingX, ctx.headingZ);
        }

        int ordinal = Math.max(0, ctx.orderedIds.indexOf(follower.getUniqueID()));
        int groupSize = Math.max(1, ctx.orderedIds.size());

        WorkerFormationSettings settings = WorkerFormationSettings.defaults();
        settings.style = style;
        settings.numEscorts = 0;

        FormationGeometry.SlotOffset off = FormationGeometry.computeSlotOffset(
                ordinal, groupSize, ctx.headingX, ctx.headingZ, baseFollowDistance, leaderId,
                settings, true, bodyScale);

        if (ctx.anchor.isFrozen()) return pinned(ctx, groupSize, off);
        return FollowFormationCoordinator.buildFixedSlot(groupSize, leaderSpeed, leaderActuallyMoving,
                leader.posX + off.dx, leader.posY, leader.posZ + off.dz,
                off, ctx.headingX, ctx.headingZ);
    }

    /**
     * Registers a follower with its leader's context and brings the shared per-leader state up to date (once
     * per tick or per refresh, whichever applies): member order, heading, route anchor, road file.
     */
    private static LeaderContext touch(EntityTraveller follower, EntityLivingBase leader, long now) {
        cleanupIfDue(now);
        LeaderContext ctx = CONTEXTS.computeIfAbsent(leader.getUniqueID(), k -> new LeaderContext());
        ctx.registered.put(follower.getUniqueID(), now);
        ctx.lastActiveTick = now;
        EntityTraveller travellerLeader = leader instanceof EntityTraveller ? (EntityTraveller) leader : null;
        if (travellerLeader != null) ctx.anchor.update(travellerLeader, now);

        if (now >= ctx.nextRefreshTick) {
            ctx.nextRefreshTick = now + REFRESH_INTERVAL_TICKS;
            ctx.registered.entrySet().removeIf(e -> now - e.getValue() > MEMBER_EXPIRY_TICKS);
            ctx.expected.keySet().retainAll(ctx.registered.keySet());
            List<UUID> ids = new ArrayList<>(ctx.registered.keySet());
            Collections.sort(ids);
            ctx.orderedIds = ids;

            double dx = com.jubitus.travellers.unit.EntityMotion.velX(leader);
            double dz = com.jubitus.travellers.unit.EntityMotion.velZ(leader);
            if (travellerLeader != null && ctx.anchor.routeHeading(travellerLeader, ROUTE_HEADING)) {
                // Walking a route: face along it, not along the leader's own steps.
                ctx.headingX = ROUTE_HEADING[0];
                ctx.headingZ = ROUTE_HEADING[1];
                ctx.hasHeading = true;
            } else if (dx * dx + dz * dz > HEADING_MOVE_EPSILON_SQ) {
                double len = Math.sqrt(dx * dx + dz * dz);
                ctx.headingX = dx / len;
                ctx.headingZ = dz / len;
                ctx.hasHeading = true;
            } else if (!ctx.hasHeading) {
                ctx.headingX = 1.0D;
                ctx.headingZ = 0.0D;
            }
            // else: leader is effectively stationary this refresh - keep the last real heading.
        }
        if (travellerLeader != null && ctx.fileTick != now && leader.world instanceof WorldServer) {
            ctx.fileTick = now;
            ctx.escorts.retainAll(ctx.registered.keySet());
            ctx.file.update((WorldServer) leader.world, leader, ctx.anchor.baseItinerary(), ctx.anchor.baseAlong(),
                    ctx.orderedIds, ctx.escorts, ctx.shape);
        }
        return ctx;
    }

    /**
     * Hired escorts of a worker leader on a route (a Go order): their road-file place, or their own formation
     * place moved to the route anchor while the group waits for a strayed leader. Null when the leader is not
     * walking a route, so the escort keeps the coordinator's formation (and returns to it after the road).
     */
    @Nullable
    public static FollowFormationCoordinator.SlotAssignment routeSlotForEscort(EntityTravellerWorker follower,
                                                                               EntityTravellerWorker leader,
                                                                               FollowFormationCoordinator.SlotAssignment formationSlot,
                                                                               double baseFollowDistance, long now) {
        LeaderContext ctx = touch(follower, leader, now);
        if (ctx.settingsTick != now) {
            ctx.settingsTick = now;
            WorkerFormationSettings settings = WorkerFormationSavedData.get(leader.world).getSettings(
                    new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, leader.getUniqueID()));
            ctx.shape = GroupRoadFile.Shape.of(baseFollowDistance, settings,
                    PathBody.isMounted(leader) ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D);
        }
        if (formationSlot.isEscort) ctx.escorts.add(follower.getUniqueID());
        else ctx.escorts.remove(follower.getUniqueID());
        FollowFormationCoordinator.SlotAssignment slot = fileSlot(ctx, follower, leader);
        if (slot == null && ctx.anchor.isFrozen()) {
            double offX = formationSlot.idealX - leader.posX, offZ = formationSlot.idealZ - leader.posZ;
            slot = FollowFormationCoordinator.buildPinnedSlot(formationSlot.groupSize,
                    ctx.anchor.x() + offX, ctx.anchor.y(), ctx.anchor.z() + offZ,
                    new FormationGeometry.SlotOffset(offX, offZ, formationSlot.preferredRadius,
                            formationSlot.arriveRadius, formationSlot.frontPenaltyStrength),
                    formationSlot.leaderDirX, formationSlot.leaderDirZ, 0.0D, false);
        }
        recordExpected(ctx, follower, leader, slot != null ? slot : formationSlot);
        return slot;
    }

    /** The route direction a worker leader's escort formation should face, or false when not on a route. */
    static boolean escortRouteHeading(EntityTravellerWorker leader, double[] out) {
        LeaderContext ctx = CONTEXTS.get(leader.getUniqueID());
        return ctx != null && !ctx.anchor.isFrozen() && ctx.anchor.routeHeading(leader, out);
    }

    @Nullable
    private static FollowFormationCoordinator.SlotAssignment fileSlot(LeaderContext ctx, EntityTraveller follower,
                                                                      EntityLivingBase leader) {
        int rank = ctx.file.rankOf(follower.getUniqueID());
        if (rank < 0) return null;
        boolean frozen = ctx.anchor.isFrozen();
        double leaderDx = com.jubitus.travellers.unit.EntityMotion.velX(leader), leaderDz = com.jubitus.travellers.unit.EntityMotion.velZ(leader);
        double leaderSpeed = Math.sqrt(leaderDx * leaderDx + leaderDz * leaderDz);
        FormationGeometry.SlotOffset fileOff = new FormationGeometry.SlotOffset(0.0D, 0.0D,
                ctx.file.behind(rank), FILE_ARRIVE_RADIUS, 0.0D);
        return FollowFormationCoordinator.buildPinnedSlot(Math.max(1, ctx.orderedIds.size()),
                ctx.file.x(rank), frozen ? ctx.anchor.y() : leader.posY, ctx.file.z(rank), fileOff,
                ctx.headingX, ctx.headingZ, frozen ? 0.0D : leaderSpeed, !frozen && leaderSpeed > 0.005D);
    }

    /** How far from the leader this follower's place is, for the leader's pace. */
    private static void recordExpected(LeaderContext ctx, EntityTraveller follower, EntityLivingBase leader,
                                       FollowFormationCoordinator.SlotAssignment slot) {
        double dx = slot.idealX - leader.posX, dz = slot.idealZ - leader.posZ;
        ctx.expected.put(follower.getUniqueID(), Math.sqrt(dx * dx + dz * dz) + 1.5D);
    }

    /** True while this leader's group waits at its route anchor (the leader fell or strayed). */
    public static boolean isWaitingForLeader(UUID leaderId) {
        LeaderContext ctx = CONTEXTS.get(leaderId);
        return ctx != null && ctx.anchor.isFrozen();
    }

    private static FollowFormationCoordinator.SlotAssignment pinned(LeaderContext ctx, int groupSize,
                                                                    FormationGeometry.SlotOffset off) {
        return FollowFormationCoordinator.buildPinnedSlot(groupSize,
                ctx.anchor.x() + off.dx, ctx.anchor.y(), ctx.anchor.z() + off.dz, off, ctx.headingX, ctx.headingZ,
                0.0D, false);
    }

    /**
     * Ground-speed factor for a travelling group leader whose followers lag behind: 1 when nothing to wait
     * for, never below {@link #MIN_PACE} - the leader slows, it never stops, so its own stuck rescue never
     * mistakes waiting for being stuck. Followers that are fighting, visiting, catching up from far away or
     * making no headway for a while are not waited for (their own rescue handles them).
     */
    public static double leaderPace(EntityTraveller leader) {
        LeaderContext ctx = CONTEXTS.get(leader.getUniqueID());
        long now = leader.world.getTotalWorldTime();
        if (ctx == null || now - ctx.lastActiveTick > MEMBER_EXPIRY_TICKS || !(leader.world instanceof WorldServer)) return 1.0D;
        if (now < ctx.nextPaceTick) return ctx.pace;
        ctx.nextPaceTick = now + REFRESH_INTERVAL_TICKS;

        double maxLag = 0.0D;
        if (!ctx.anchor.isFrozen() && ctx.anchor.baseItinerary() != null) {
            WorldServer ws = (WorldServer) leader.world;
            for (UUID id : ctx.registered.keySet()) {
                net.minecraft.entity.Entity e = ws.getEntityFromUuid(id);
                if (!(e instanceof EntityTraveller) || !e.isEntityAlive()) continue;
                EntityTraveller f = (EntityTraveller) e;
                if (!isFollowerOf(f, leader) || f.getAttackTarget() != null
                        || f.isDispersedInStay() || f.isCatchupTravel()) continue;
                Double expected = ctx.expected.get(id);
                if (expected == null) continue;
                double lag = Math.max(0.0D, f.getDistance(leader.posX, f.posY, leader.posZ) - expected);
                if (isMakingHeadway(ctx, id, lag, LAG_OK * (PathBody.isMounted(leader)
                        ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D), now)) maxLag = Math.max(maxLag, lag);
            }
        }
        ctx.lagWatch.keySet().retainAll(ctx.registered.keySet());
        double scale = PathBody.isMounted(leader) ? FormationGeometry.MOUNTED_SPACING_SCALE : 1.0D;
        double lagOk = LAG_OK * scale, lagFull = LAG_FULL_WAIT * scale;
        double pace = maxLag <= lagOk ? 1.0D
                : 1.0D - Math.min(1.0D, (maxLag - lagOk) / (lagFull - lagOk)) * (1.0D - MIN_PACE);
        if ((pace < 1.0D) != (ctx.pace < 1.0D)) {
            TravellerTracer.event(leader, "follow", pace < 1.0D
                    ? String.format(java.util.Locale.ROOT, "slowing for the group (lag %.1f, pace %.2f)", maxLag, pace)
                    : "group caught up, full pace");
        }
        ctx.pace = pace;
        return pace;
    }

    /** True while this leader slows for its group: its followers then walk their own travel pace to catch up. */
    public static boolean isLeaderPacing(UUID leaderId, long now) {
        LeaderContext ctx = CONTEXTS.get(leaderId);
        return ctx != null && ctx.pace < 1.0D && now - ctx.lastActiveTick <= MEMBER_EXPIRY_TICKS;
    }

    private static boolean isFollowerOf(EntityTraveller f, EntityTraveller leader) {
        UUID id = leader.getUniqueID();
        return id.equals(f.getGroupLeaderUUID())
                || (f instanceof EntityTravellerWorker && id.equals(((EntityTravellerWorker) f).getEscortLeaderUUID()));
    }

    /** False once a lagging follower has not closed the gap by a block for STUCK_LAG_TICKS. */
    private static boolean isMakingHeadway(LeaderContext ctx, UUID id, double lag, double lagOk, long now) {
        if (lag <= lagOk) {
            ctx.lagWatch.remove(id);
            return true;
        }
        double[] w = ctx.lagWatch.get(id);
        if (w == null || lag < w[0] - 1.0D) {
            ctx.lagWatch.put(id, new double[]{lag, now});
            return true;
        }
        return now - (long) w[1] < STUCK_LAG_TICKS;
    }
}
