package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.utils.world.camp.CampsiteManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.MaterializePositionResolver;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.MathHelper;
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
    private final FollowBoatController boatController;
    private final FollowFlightController flightController;
    private int repathCooldown;
    private int stagnationTicks;
    private double lastProgressX;
    private double lastProgressZ;
    private boolean hasProgressAnchor;
    private boolean sprintingCatchup;

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
        stagnationTicks = 0;
        hasProgressAnchor = false;
        sprintingCatchup = false;
        boatController.reset();
        flightController.reset();
    }

    @Override
    public void resetTask() {
        follower.getNavigator().clearPath();
        setCatchupSprint(false);
        leaderTarget = null;
        hasProgressAnchor = false;
        campCheckCooldown = 0;
        leaderIsCamping = false;
        boatController.reset();
        flightController.reset();
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
            targetX += slotOffsetX;
            targetZ += slotOffsetZ;
        }

        double distToLeader = horizontalDist(leaderTarget.x, leaderTarget.z);
        double distToTargetSq = distSq(follower.posX, follower.posZ, targetX, targetZ);
        boolean catchingUp = distToLeader > cfg.catchUpTriggerDistance;

        tickStagnation(distToTargetSq, cfg);

        if (stagnationTicks >= cfg.stagnationTeleportTicks) {
            tryHopTowardTarget(targetX, targetZ, cfg);
            return;
        }

        double stopDistSq = cfg.stopDistance * cfg.stopDistance;
        if (distToTargetSq <= stopDistSq) {
            follower.getNavigator().clearPath();
            setCatchupSprint(false);
            return;
        }

        setCatchupSprint(catchingUp && cfg.sprintWhileCatchingUp);

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }

        double effectiveSpeed = computeEffectiveSpeed(distToLeader, cfg);
        // Beyond directPathMaxDistance, asking the navigator to path straight to the leader's exact
        // tile routinely fails outright (out of pathfinding search range) - every failed attempt looks
        // "stuck" to the stagnation tracker below, and the follower ends up teleport-hopping its way
        // to the leader in a visible series of jumps instead of walking. Step toward it instead, the
        // same way EntityAITravel advances long-distance travel through intermediate waypoints.
        boolean farCatchup = distToLeader > cfg.directPathMaxDistance;
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

        int interval = moved
                ? (catchingUp ? cfg.repathTicksCatchup : cfg.repathTicksNormal)
                : cfg.repathTicksFailed;
        repathCooldown = interval;
    }

    /** Linearly ramps from 1x at catchUpTriggerDistance to catchUpSpeedMultiplier at catchUpMaxDistance. */
    private double computeEffectiveSpeed(double distToLeader, TravellersConfig.GroupFollow cfg) {
        if (distToLeader <= cfg.catchUpTriggerDistance) return speed;
        double span = Math.max(1.0, cfg.catchUpMaxDistance - cfg.catchUpTriggerDistance);
        double t = MathHelper.clamp((distToLeader - cfg.catchUpTriggerDistance) / span, 0.0D, 1.0D);
        double multiplier = 1.0D + t * (cfg.catchUpSpeedMultiplier - 1.0D);
        return speed * multiplier;
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

        BlockPos spot = findSafeSpotNear(hopX, hopZ, Math.max(3, (int) (cfg.teleportMaxStep / 2)));
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
}
