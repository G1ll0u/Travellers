package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.travel.mount.MountedLeadController;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.camp.CampsiteManager;
import com.jubitus.travellers.world.pathing.PathBody;
import com.jubitus.travellers.world.virtualization.MaterializePositionResolver;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Follow AI for travellers and raiders that belong to a group.
 * Followers keep a small, fixed personal spot near the leader (same deterministic ring offset
 * used by the offscreen/materialize code, see {@link MaterializePositionResolver#followerSpreadOffset})
 * instead of all pathing to the leader's exact tile - this keeps them from crowding/blocking each
 * other, which was the main source of stuck followers and teleports.
 * Speeds up ("catches up") the farther behind it falls, and if genuinely stuck, hops only partway
 * toward the group rather than warping straight onto the leader.
 *
 * The leader is not necessarily a loaded entity: a follower can materialize (or stay loaded) while
 * its group leader is still offscreen/virtual. In that case this task tracks the leader's simulated
 * position via {@link VirtualTravellerManager} instead of giving up, mirroring
 * {@code EntityTravellerWorker.tickVirtualLeaderFollow()} — a physical follower must never lose track
 * of its leader just because the leader hasn't materialized yet.
 */
public class EntityAIFollowGroupLeader extends EntityAIBase {

    private final EntityTraveller follower;
    private double speed;

    private final double slotOffsetX;
    private final double slotOffsetZ;

    /** Current leader position/entity, resolved fresh every check — see {@link #resolveLeaderTarget()}. */
    private static final class LeaderTarget {
        final double x, y, z;
        @Nullable final EntityTraveller entity;

        LeaderTarget(double x, double y, double z, @Nullable EntityTraveller entity) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.entity = entity;
        }
    }

    @Nullable private LeaderTarget leaderTarget;

    /** Read by the trace tool - same idea as {@code EntityAIFollowLeader.getSteeringBlockedTicks()}
     *  but this class had no trace visibility at all before: a jittery/incoherent target was
     *  otherwise indistinguishable between "the resolved leader position itself is unstable" (loaded
     *  entity physically erratic, or flip-flopping between the loaded and virtual branches of
     *  {@link #resolveLeaderTarget()}) and a bug in this class's own target computation. */
    public int getSteeringBlockedTicks() {
        return steerBlockedTicks;
    }

    /** Whether the currently resolved leader is a loaded entity (vs. tracked via its virtual
     *  position) - {@code null} when no leader is currently resolved at all. */
    @Nullable
    public Boolean isLeaderLoaded() {
        return leaderTarget == null ? null : leaderTarget.entity != null;
    }

    /** Raw resolved leader position (before this follower's own formation-slot offset is added) -
     *  {@code null} when no leader is currently resolved. */
    @Nullable
    public Vec3d getLeaderPosition() {
        return leaderTarget == null ? null : new Vec3d(leaderTarget.x, leaderTarget.y, leaderTarget.z);
    }

    private final FollowBoatController boatController;
    private final FollowFlightController flightController;
    private final MountedFollowPacer mountedPacer;
    private int repathCooldown;
    /** Consecutive ticks a mounted follower's straight-line steer has been refused - once it reaches
     *  {@code steeringStuckTicksBeforePathfind} the servo escalates to a real A* search. Also feeds
     *  {@link #computePathPriority}: past {@code steeringBlockedEscalationGraceTicks} this follower is
     *  genuinely stuck on terrain, not merely lagging in distance, and must not keep asking the
     *  scheduler at the lowest priority - see the class doc on why that starves it under a busy
     *  mounted budget while the leader (a completely separate, unthrottled scheduler) rides on fine. */
    private int steerBlockedTicks;
    /** Consecutive real pathfind attempts that found no route at all (as opposed to a corridor steer
     *  refusal) - mirrors {@code EntityAIFollowLeader.pathFailTicks}, same escalation reason. */
    private int pathFailTicks;
    private int nextWaterLeadProbeTick;
    private int stagnationTicks;
    private double lastProgressX;
    private double lastProgressZ;
    private boolean hasProgressAnchor;
    private boolean sprintingCatchup;

    /** Leader/target position at the moment of the last *granted* real pathfind - see the "nothing
     * moved" guard in {@link #updateTask()} (Task 3 of the mounted-follow perf plan). */
    private double lastGoalEvalLeaderX, lastGoalEvalLeaderZ;
    private double lastGoalEvalTargetX, lastGoalEvalTargetZ;
    private boolean hasGoalEvalAnchor;
    /** Base epsilon (blocks) below which the leader/target haven't meaningfully moved since the last
     * granted search - scaled by {@code mountScale} like every other distance in this method. */
    private static final double REPATH_SKIP_EPSILON = 1.5D;

    // Cached leader-camping check (campOf iterates camp occupants — refreshed every 10 ticks).
    private int campCheckCooldown = 0;
    private boolean leaderIsCamping = false;

    public EntityAIFollowGroupLeader(EntityTraveller follower, double speed) {
        this.follower = follower;
        this.speed = speed;
        int[] off = MaterializePositionResolver.followerSpreadOffset(follower.getUniqueID());
        this.slotOffsetX = off[0];
        this.slotOffsetZ = off[1];
        this.boatController = new FollowBoatController(follower, speed);
        this.flightController = new FollowFlightController(follower);
        this.mountedPacer = new MountedFollowPacer(follower);
        setMutexBits(3);
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    @Override
    public boolean shouldExecute() {
        // No groupLeaderUUID at all means this entity never had a leader (it may itself BE a group
        // leader, or a fully solo traveller) - this task must never engage for it, full stop. Checked
        // before anything else: this task is registered on every EntityTraveller unconditionally, so
        // without this gate a solo entity riding its own boat via EntityAITravel would make
        // boatController.shouldHandle(null) below true purely from follower.isRiding(), stealing the
        // movement mutex to "chase" a leader that never existed.
        if (follower.getGroupLeaderUUID() == null) return false;
        if (follower.getAttackTarget() != null && !follower.getAttackTarget().isDead) return false;
        LeaderTarget t = resolveLeaderTarget();
        if (t == null) {
            // Leader is genuinely gone (dead, not loaded, not virtual) - clear the stale link, but
            // still run once more if the follower is stranded riding its own boat: it needs to be
            // brought to shore and the boat killed rather than left abandoned/ridden forever.
            follower.setGroupLeaderUUID(null);
            return boatController.shouldHandle(null);
        }
        this.leaderTarget = t;
        // A leader crossing water (or the follower already riding to catch up) always takes over,
        // ahead of the camp check - never mistaken for camping.
        if (flightController.shouldHandle(t.entity)) return true;
        if (boatController.shouldHandle(t.entity)) return true;
        // Yield to EntityAICampStay when the leader is camping.
        if (isLeaderCamping()) return false;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        // Reachable with a null leaderTarget only while finishing the orphaned-leader boat rescue
        // started in shouldExecute() above; never for an entity that had no leader to begin with,
        // since shouldExecute() would have refused to start this task for one at all.
        if (leaderTarget == null) return boatController.shouldHandle(null);
        if (follower.getAttackTarget() != null && !follower.getAttackTarget().isDead) return false;
        if (flightController.shouldHandle(leaderTarget.entity)) return true;
        if (boatController.shouldHandle(leaderTarget.entity)) return true;
        if (isLeaderCamping()) return false;
        return true;
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
        steerBlockedTicks = 0;
        pathFailTicks = 0;
        stagnationTicks = 0;
        hasProgressAnchor = false;
        hasGoalEvalAnchor = false;
        sprintingCatchup = false;
        boatController.reset();
        flightController.reset();
        mountedPacer.reset();
    }

    @Override
    public void resetTask() {
        follower.getNavigator().clearPath();
        setCatchupSprint(false);
        leaderTarget = null;
        hasProgressAnchor = false;
        hasGoalEvalAnchor = false;
        campCheckCooldown = 0;
        leaderIsCamping = false;
        boatController.reset();
        flightController.reset();
        mountedPacer.reset();
    }

    @Override
    public void updateTask() {
        // Re-resolve leader each update: it may have materialized/virtualized since the last tick,
        // or genuinely be gone (dead).
        LeaderTarget fresh = resolveLeaderTarget();
        if (fresh == null) {
            // Leader is gone entirely (not loaded, not virtual) - stop chasing a ghost and let the
            // follower fall back to travelling on its own (EntityAITravel refuses to run while
            // getGroupLeaderUUID() is set, see its own guard). Still get a stranded boat to shore first.
            follower.setGroupLeaderUUID(null);
            leaderTarget = null;
            if (boatController.tick(null)) return;
            resetTask();
            return;
        }
        leaderTarget = fresh;

        if (follower.getAttackTarget() != null && !follower.getAttackTarget().isDead) {
            follower.getNavigator().clearPath();
            setCatchupSprint(false);
            return;
        }

        // Water crossing: board/steer a boat to keep pace with a boating leader, or (with no live
        // leader to track) just get an already-mounted boat to safety. Bypasses all ground-follow
        // logic below - stagnation/teleport-rescue is meaningless while riding a boat and must not
        // fire mid-crossing.
        // Air crossing: launch with a gliding leader and chase them. Like the boat branch below, this
        // bypasses the ground-follow logic entirely - stagnation and teleport-rescue are meaningless
        // in mid-air and must not fire during a flight.
        if (flightController.tick(fresh.entity)) {
            stagnationTicks = 0;
            hasProgressAnchor = false;
            return;
        }

        if (boatController.tick(fresh.entity)) {
            stagnationTicks = 0;
            hasProgressAnchor = false;
            return;
        }

        TravellersConfig.GroupFollow cfg = TravellersConfig.GROUP_FOLLOW;

        double targetX = leaderTarget.x;
        double targetZ = leaderTarget.z;
        if (cfg.spreadFollowEnabled) {
            // Mounted followers need their ring slot pushed out so the horses don't interpenetrate.
            double spreadScale = com.jubitus.travellers.world.pathing.PathBody.isMounted(follower)
                    ? com.jubitus.travellers.entity.entityai.follow.formations.FormationGeometry.MOUNTED_SPACING_SCALE
                    : 1.0D;
            targetX += slotOffsetX * spreadScale;
            targetZ += slotOffsetZ * spreadScale;
        }

        // Mounted follower meets water its horse can't ford: step off and lead it in on foot. The
        // on-foot follow servo below then swims the crossing, TravellerMountSupport.reconcileOnFoot
        // tows the horse, and its sustained strict-dry gate remounts once both are dry on the far
        // bank. Same geometric probe EntityAITravel uses - see MountedLeadController, no crossing state.
        if (!follower.world.isRemote && TravellersConfig.MOUNTS.mountedDismountForWater
                && follower.isMountedOnBondedMount()
                && follower.ticksExisted >= nextWaterLeadProbeTick) {
            nextWaterLeadProbeTick = follower.ticksExisted + 8;
            if (MountedLeadController.waterAheadNeedsLeading(follower, bearingXZ(leaderTarget.x, leaderTarget.z))) {
                MountedLeadController.enterLedForWater(follower);
            }
        }

        // Horseback naturally runs farther behind and covers ground between checks faster than
        // walking does - every distance below tuned for a pedestrian follower is widened by the same
        // factor for a mounted one, or it stays perpetually catch-up-boosted and skids to a stop
        // right on top of its spot instead of settling into it (see mountedFollowDistanceScale).
        double mountScale = PathBody.isMounted(follower) ? cfg.mountedFollowDistanceScale : 1.0D;

        double distToLeader = horizontalDist(leaderTarget.x, leaderTarget.z);
        double distToTargetSq = distSq(follower.posX, follower.posZ, targetX, targetZ);
        boolean catchingUp = distToLeader > cfg.catchUpTriggerDistance * mountScale;

        // Mounted follower behind a moving loaded leader: continuous pace-matching replaces the
        // stop/re-path servo below for the tick - see MountedFollowPacer. A virtual leader has no
        // per-tick velocity to match, so it keeps the ordinary logic.
        if (leaderTarget.entity != null
                && mountedPacer.tick(
                        leaderTarget.entity.posX, leaderTarget.entity.posZ,
                        leaderTarget.entity.posX - leaderTarget.entity.prevPosX,
                        leaderTarget.entity.posZ - leaderTarget.entity.prevPosZ,
                        targetX, targetZ, distToLeader, speed, mountScale, leaderTarget.entity)) {
            setCatchupSprint(false);
            stagnationTicks = 0;
            hasProgressAnchor = false;
            return;
        }

        tickStagnation(distToTargetSq, cfg);

        if (stagnationTicks >= cfg.stagnationTeleportTicks) {
            tryHopTowardTarget(targetX, targetZ, cfg);
            return;
        }

        double stopDistance = cfg.stopDistance * mountScale;
        double stopDistSq = stopDistance * stopDistance;
        if (distToTargetSq <= stopDistSq) {
            follower.getNavigator().clearPath();
            setCatchupSprint(false);
            steerBlockedTicks = 0;
            return;
        }

        setCatchupSprint(catchingUp && cfg.sprintWhileCatchingUp);

        double effectiveSpeed = computeEffectiveSpeed(distToLeader, cfg, mountScale);
        // Leading a bonded horse on foot over dry ground: hang back so the leash doesn't drag it
        // (no-op unless in a water-lead LED spell - see MountedLeadController).
        effectiveSpeed *= MountedLeadController.ledRiderPaceMultiplier(follower);
        // Taper toward the stop instead of riding in at full speed and skidding past it - a horse's
        // own momentum (see the mount steering system's speed/turn-radius notes) does not cancel the
        // instant clearPath() fires above, so without this a mounted follower visibly overshoots its
        // spot and snaps back, which is exactly the jerky arrival this scale is meant to prevent.
        double decelRadius = stopDistance * 2.0D;
        double distToTarget = Math.sqrt(distToTargetSq);
        if (distToTarget < decelRadius) {
            effectiveSpeed *= MathHelper.clamp(distToTarget / decelRadius, 0.35D, 1.0D);
        }
        boolean farCatchup = distToLeader > cfg.directPathMaxDistance;

        // Path-averse servo (mirrors EntityAIFollowLeader): a follower's slot is almost always
        // reachable in a straight line, so a mounted follower steers there directly every tick and
        // only escalates to a full A* once that straight corridor has been blocked for several ticks
        // in a row. Mount-aware A* (TravellerNodeProcessor) is by far the most expensive path search
        // in the mod; with hundreds/thousands of followers loaded this is what keeps it off the tick.
        // steerDirect resolves the ground itself and coasts on a cached corridor verdict, so this
        // fast path does no heightmap lookup and no A*. A follower already on a real path is left to
        // walk it out - crossing whatever obstacle needed it never stutters mid-way.
        boolean navHasPath = !follower.getNavigator().noPath();
        if (PathBody.isMounted(follower) && !farCatchup && !navHasPath
                && follower.getNavigator() instanceof com.jubitus.travellers.world.pathing.TravellerNavigateGround) {
            // Same leg-at-a-time riding the leader's own EntityAITravel uses
            // (mountedDirectLegBlocks): proving one short stretch at a time succeeds far more often
            // on rolling terrain than proving the whole (up to directPathMaxDistance-long) line in
            // one shot - a single long corridor test hits the rise/drop budget or an obstacle
            // mid-line constantly on hills, which is exactly what made followers stall/zig-zag there
            // while the leader, riding the identical ground in short proven legs, sailed through.
            double legTargetX = targetX;
            double legTargetZ = targetZ;
            double toTargetX = targetX - follower.posX;
            double toTargetZ = targetZ - follower.posZ;
            double toTargetDist = Math.sqrt(toTargetX * toTargetX + toTargetZ * toTargetZ);
            double legBlocks = TravellersConfig.MOUNTS.mountedDirectLegBlocks;
            if (toTargetDist > legBlocks) {
                legTargetX = follower.posX + toTargetX / toTargetDist * legBlocks;
                legTargetZ = follower.posZ + toTargetZ / toTargetDist * legBlocks;
            }
            if (steerBlockedTicks < TravellersConfig.WORKER_FORMATION.steeringStuckTicksBeforePathfind
                    && ((com.jubitus.travellers.world.pathing.TravellerNavigateGround) follower.getNavigator())
                    .steerDirect(legTargetX, legTargetZ, effectiveSpeed)) {
                steerBlockedTicks = 0;
                return;
            }
            steerBlockedTicks++;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }

        // Nothing has actually changed since the last granted search: the follower already has a
        // live path walking it toward essentially the same spot, so asking the scheduler again (and
        // paying for a fresh mount-aware A* if granted) buys nothing. Mirrors
        // EntityAIFollowLeader.shouldReevaluateGoal, sized down for this class - no
        // FollowFormationCoordinator.SlotAssignment to reuse, but targetX/targetZ do the same job.
        // Only reached with navHasPath true (the direct-steer block above only runs while
        // !navHasPath), so a follower with no path, or one that just exhausted its steering
        // escalation above, always falls through to a real search instead of being skipped here.
        if (navHasPath && hasGoalEvalAnchor) {
            double leaderMovedSq = distSq(leaderTarget.x, leaderTarget.z, lastGoalEvalLeaderX, lastGoalEvalLeaderZ);
            double targetMovedSq = distSq(targetX, targetZ, lastGoalEvalTargetX, lastGoalEvalTargetZ);
            double epsilon = REPATH_SKIP_EPSILON * mountScale;
            if (leaderMovedSq < epsilon * epsilon && targetMovedSq < epsilon * epsilon) {
                return;
            }
        }

        // World-wide A* budget, shared with EntityAIFollowLeader: with thousands of followers
        // loaded only a bounded number of these searches run per server tick - everyone else coasts on
        // their last path / direct steer and retries. Keyed by the group leader so one big militia
        // shares one token bucket instead of each follower getting its own.
        EntityTraveller pathOwner = leaderTarget.entity != null ? leaderTarget.entity : follower;

        // Last resort before paying for a real search - by far the most expensive kind this class
        // asks for is a mounted A*, per the Spark profile that started this pass. Mirrors
        // EntityAIFollowLeader.tryFollowSharedCorridor: a nearby groupmate's just-found path
        // around the same obstacle is reused instead of everyone independently re-solving it.
        if (TravellersConfig.WORKER_FORMATION.sharedRouteCorridorEnabled) {
            double bearingToTarget = Math.atan2(targetZ - follower.posZ, targetX - follower.posX);
            Vec3d sharedWaypoint = FollowRouteCache.findNearestUsableWaypoint(
                    follower.world, pathOwner, follower.posX, follower.posZ, bearingToTarget,
                    TravellersConfig.WORKER_FORMATION.sharedRouteCorridorMaxSnapDistance,
                    TravellersConfig.WORKER_FORMATION.sharedRouteCorridorTtlTicks);
            if (sharedWaypoint != null) {
                follower.getMoveHelper().setMoveTo(sharedWaypoint.x, sharedWaypoint.y, sharedWaypoint.z, effectiveSpeed);
                return;
            }
        }

        if (!PathScheduler.tryAcquire(follower.world, pathOwner, follower.getEntityId(),
                computePathPriority(catchingUp), PathBody.isMounted(follower))) {
            repathCooldown = cfg.repathTicksFailed;
            return;
        }

        lastGoalEvalLeaderX = leaderTarget.x;
        lastGoalEvalLeaderZ = leaderTarget.z;
        lastGoalEvalTargetX = targetX;
        lastGoalEvalTargetZ = targetZ;
        hasGoalEvalAnchor = true;

        // Beyond directPathMaxDistance, asking the navigator to path straight to the leader's exact
        // tile routinely fails outright (out of pathfinding search range) - every failed attempt looks
        // "stuck" to the stagnation tracker below, and the follower ends up teleport-hopping its way
        // to the leader in a visible series of jumps instead of walking. Step toward it instead, the
        // same way EntityAITravel advances long-distance travel through intermediate waypoints.
        BlockPos groundTarget = farCatchup
                ? resolveStepWaypoint(targetX, targetZ, cfg.catchupStepDistance)
                : resolveGroundTarget(targetX, targetZ);
        boolean moved;
        if (groundTarget != null) {
            moved = follower.getNavigator().tryMoveToXYZ(
                    groundTarget.getX() + 0.5, groundTarget.getY(), groundTarget.getZ() + 0.5, effectiveSpeed);
        } else if (!farCatchup && leaderTarget.entity != null) {
            moved = follower.getNavigator().tryMoveToEntityLiving(leaderTarget.entity, effectiveSpeed);
        } else if (!farCatchup) {
            // Virtual leader with an unloaded target column: path straight at the raw target coords.
            moved = follower.getNavigator().tryMoveToXYZ(targetX, leaderTarget.y, targetZ, effectiveSpeed);
        } else {
            // Far away and no loaded step found anywhere between here and the leader - nothing safe
            // to walk toward this tick. Falls through to the stagnation/teleport-hop rescue below,
            // same as any other genuinely blocked case.
            moved = false;
        }

        if (moved) {
            steerBlockedTicks = 0;
            pathFailTicks = 0;
            if (TravellersConfig.WORKER_FORMATION.sharedRouteCorridorEnabled) {
                FollowRouteCache.record(follower.world, pathOwner, follower.getNavigator().getPath());
            }
        } else {
            pathFailTicks++;
        }

        int interval = moved
                ? (catchingUp ? cfg.repathTicksCatchup : cfg.repathTicksNormal)
                : cfg.repathTicksFailed;
        // Horseback re-derives essentially the same route it just finished walking a third of if
        // checked on a pedestrian cadence (a mounted follower covers ground faster between checks -
        // Task 3), and a failed/blocked mounted attempt is terrain, which doesn't change tick to tick
        // (Task 6) - both widened by the same mountScale already used for every other distance/speed
        // threshold in this method, instead of leaving these at the pedestrian value.
        repathCooldown = (int) Math.round(interval * mountScale);
    }

    /** Linearly ramps from 1x at catchUpTriggerDistance to catchUpSpeedMultiplier at
     * catchUpMaxDistance - both scaled by {@code mountScale} (1.0 on foot) so a mounted follower's
     * much larger cruising distances don't read as "always catching up". */
    private double computeEffectiveSpeed(double distToLeader, TravellersConfig.GroupFollow cfg, double mountScale) {
        double triggerDist = cfg.catchUpTriggerDistance * mountScale;
        double maxDist = cfg.catchUpMaxDistance * mountScale;
        if (distToLeader <= triggerDist) return speed;
        double span = Math.max(1.0, maxDist - triggerDist);
        double t = MathHelper.clamp((distToLeader - triggerDist) / span, 0.0D, 1.0D);
        double multiplier = 1.0D + t * (cfg.catchUpSpeedMultiplier - 1.0D);
        return speed * multiplier;
    }

    /**
     * Mirrors the stuck-escalation half of {@code EntityAIFollowLeader.getPathPriority()},
     * which this class never had - it used to request priority purely from {@code catchingUp} (a
     * distance-to-leader threshold), so a follower wedged on terrain right next to its own slot
     * always asked at the lowest priority. Under a busy mounted budget (a global, server-wide cap,
     * unlike the leader's own far larger and unthrottled travel scheduler) that starves exactly the
     * followers that most need a real route, leaving them retrying a doomed direct steer instead -
     * the stall/zig-zag pattern on hills a trace caught repeating at the same spot dozens of times.
     */
    private int computePathPriority(boolean catchingUp) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (wf.steeringBlockedFastEscalationEnabled
                && steerBlockedTicks >= wf.steeringBlockedEscalationGraceTicks) {
            return 2;
        }
        if (pathFailTicks >= 6) {
            return 2;
        }
        return catchingUp ? 1 : 0;
    }

    private void setCatchupSprint(boolean sprint) {
        if (sprint == sprintingCatchup) return;
        sprintingCatchup = sprint;
        follower.setSprinting(sprint);
    }

    private void tickStagnation(double distToTargetSq, TravellersConfig.GroupFollow cfg) {
        double idleBufferSq = (cfg.stopDistance + 1.0D) * (cfg.stopDistance + 1.0D);
        if (distToTargetSq <= idleBufferSq) {
            stagnationTicks = 0;
            hasProgressAnchor = false;
            return;
        }

        if (!hasProgressAnchor) {
            lastProgressX = follower.posX;
            lastProgressZ = follower.posZ;
            hasProgressAnchor = true;
            stagnationTicks = 0;
            return;
        }

        double dx = follower.posX - lastProgressX;
        double dz = follower.posZ - lastProgressZ;
        if (dx * dx + dz * dz > 0.25) {
            stagnationTicks = 0;
            lastProgressX = follower.posX;
            lastProgressZ = follower.posZ;
        } else {
            stagnationTicks++;
        }
    }

    /**
     * Teleports the follower a bounded distance toward its target instead of straight onto the
     * leader - a stuck follower that is far away "blinks" closer in a few short hops rather than
     * warping the whole gap in one jump.
     */
    private void tryHopTowardTarget(double targetX, double targetZ, TravellersConfig.GroupFollow cfg) {
        double dx = targetX - follower.posX;
        double dz = targetZ - follower.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < cfg.teleportMinTriggerDistance) {
            // Gap too small to bother teleporting over; keep trying to walk it off.
            stagnationTicks = cfg.stagnationTeleportTicks / 2;
            return;
        }

        double step = Math.min(dist, cfg.teleportMaxStep);
        double dirX = dx / dist;
        double dirZ = dz / dist;
        double hopX = follower.posX + dirX * step;
        double hopZ = follower.posZ + dirZ * step;

        BlockPos spot = resolveHopSafeSpot(hopX, hopZ, Math.max(3, (int) (cfg.teleportMaxStep / 2)));
        if (spot == null) {
            // No safe spot found; reset stagnation counter and retry later
            stagnationTicks = cfg.stagnationTeleportTicks / 2;
            return;
        }
        follower.getNavigator().clearPath();
        follower.setPositionAndUpdate(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        follower.motionX = 0;
        follower.motionY = 0;
        follower.motionZ = 0;
        stagnationTicks = 0;
        repathCooldown = cfg.repathTicksCatchup;
        hasProgressAnchor = false;
    }

    /**
     * Picks the rescue-hop landing spot, accounting for a bonded horse when there is one.
     *
     * <p>{@code EntityTraveller.setPositionAndUpdate} carries a mounted <em>or</em> led horse to the
     * exact same point the rider lands on - so whenever this follower has a bonded mount, the spot has
     * to fit the whole horse(+rider) silhouette, not just the rider's own ~1-wide body. {@link
     * #findSafeSpotNear} only ever checked a rider-sized 1x2 column, which is routinely wide enough for
     * the rider while still embedding the wider horse in solid rock right next to it - reported on
     * steep mountainside terrain specifically, exactly where a narrow rider-sized ledge and a
     * horse-sized one most often diverge. Reuses {@link MaterializePositionResolver}'s existing
     * mount-aware footprint search (the same one materialize/virtualize placement already relies on)
     * instead of adding a second, disagreeing clearance model.
     */
    @Nullable
    private BlockPos resolveHopSafeSpot(double hopX, double hopZ, int radius) {
        UUID mountUUID = follower.getMountUUID();
        if (mountUUID != null && follower.world instanceof WorldServer) {
            Entity mount = ((WorldServer) follower.world).getEntityFromUuid(mountUUID);
            if (mount instanceof EntityHorse && !mount.isDead) {
                BlockPos spot = MaterializePositionResolver.findClearMountFootprint(
                        (WorldServer) follower.world, mount, follower,
                        hopX, follower.posY, hopZ, null, radius, true);
                if (spot != null) return spot;
            }
        }
        return findSafeSpotNear(hopX, hopZ, radius);
    }

    @Nullable
    private BlockPos findSafeSpotNear(double cx, double cz, int radius) {
        World world = follower.world;
        int baseX = MathHelper.floor(cx);
        int baseZ = MathHelper.floor(cz);
        int baseY = MathHelper.floor(follower.posY);

        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = world.rand.nextInt(radius * 2 + 1) - radius;
            int dz = world.rand.nextInt(radius * 2 + 1) - radius;

            BlockPos col = new BlockPos(baseX + dx, 0, baseZ + dz);
            if (!world.isBlockLoaded(col)) continue;

            BlockPos solid = world.getTopSolidOrLiquidBlock(col);
            if (solid.getY() < 1) continue;
            if (Math.abs(solid.getY() - baseY) > 16) continue;

            BlockPos standPos = solid.up();
            if (world.getBlockState(standPos).getMaterial().isSolid()) continue;
            if (world.getBlockState(standPos.up()).getMaterial().isSolid()) continue;
            return standPos;
        }
        return null;
    }

    @Nullable
    private BlockPos resolveGroundTarget(double x, double z) {
        World world = follower.world;
        BlockPos col = new BlockPos(MathHelper.floor(x), 0, MathHelper.floor(z));
        if (!world.isBlockLoaded(col)) return null;
        return world.getTopSolidOrLiquidBlock(col);
    }

    /**
     * Resolves an intermediate waypoint toward a far-away (x, z), stepping at most {@code stepDistance}
     * blocks from the follower's own position — mirrors {@code EntityAITravel.computeNextWaypointToward}'s
     * step-down loop for long-distance travel, kept deliberately simpler since a follower always has a
     * live leader/slot to aim at (no route/village/road concerns). Tries progressively shorter steps in
     * case the full step distance lands in an unloaded chunk, so a follower right at the edge of loaded
     * terrain still gets a usable near step instead of giving up outright.
     */
    @Nullable
    private BlockPos resolveStepWaypoint(double x, double z, double stepDistance) {
        double dx = x - follower.posX;
        double dz = z - follower.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return resolveGroundTarget(x, z);

        double dirX = dx / dist;
        double dirZ = dz / dist;
        double maxStep = Math.min(stepDistance, dist);
        for (double step = maxStep; step >= 8.0; step -= 8.0) {
            BlockPos wp = resolveGroundTarget(follower.posX + dirX * step, follower.posZ + dirZ * step);
            if (wp != null) return wp;
        }
        return null;
    }

    private boolean isLeaderCamping() {
        UUID leaderUUID = follower.getGroupLeaderUUID();
        if (!TravellersConfig.CAMP_EVENTS.enabled || follower.world.isRemote || leaderUUID == null) return false;
        if (campCheckCooldown > 0) {
            campCheckCooldown--;
            return leaderIsCamping;
        }
        CampsiteManager cm = CampsiteManager.get((WorldServer) follower.world);
        // campOf is keyed by UUID and reflects occupancy whether the entity is loaded or virtual, so
        // this works the same for a loaded or still-offscreen leader.
        // Also check the follower's own occupancy: the leader may finish its own teardown and
        // leave the camp's occupant map before this follower has finished tearing its shelter down.
        leaderIsCamping = cm.campOf(leaderUUID) != null || cm.campOf(follower.getUniqueID()) != null;
        campCheckCooldown = 10;
        return leaderIsCamping;
    }

    /**
     * Resolves the leader's current position, preferring a loaded entity and falling back to its
     * simulated virtual position ({@link VirtualTravellerManager}) so a physical follower never loses
     * track of a leader that simply hasn't materialized (or has re-virtualized) yet. Returns
     * {@code null} only when the leader is genuinely gone (dead, not loaded, not virtual).
     */
    @Nullable
    private LeaderTarget resolveLeaderTarget() {
        UUID leaderUUID = follower.getGroupLeaderUUID();
        if (leaderUUID == null) return null;
        if (follower.world.isRemote) return null;
        WorldServer world = (WorldServer) follower.world;

        net.minecraft.entity.Entity e = world.getEntityFromUuid(leaderUUID);
        if (e instanceof EntityTraveller && !e.isDead) {
            EntityTraveller le = (EntityTraveller) e;
            return new LeaderTarget(le.posX, le.posY, le.posZ, le);
        }

        BlockPos virtualPos = VirtualTravellerManager.get(world).getVirtualPosition(leaderUUID);
        if (virtualPos != null) {
            return new LeaderTarget(virtualPos.getX() + 0.5, virtualPos.getY(), virtualPos.getZ() + 0.5, null);
        }

        return null;
    }

    private double distSq(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    private double horizontalDist(double x, double z) {
        return Math.sqrt(distSq(follower.posX, follower.posZ, x, z));
    }

    /** Normalised XZ bearing from the follower toward (x, z), or {@code null} if it is underfoot. */
    @Nullable
    private double[] bearingXZ(double x, double z) {
        double dx = x - follower.posX;
        double dz = z - follower.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return null;
        return new double[]{dx / len, dz / len};
    }
}
