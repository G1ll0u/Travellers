package com.jubitus.travellers.unit;

import com.jubitus.travellers.follow.FollowRecoveryHelper;
import com.jubitus.travellers.follow.FollowRuntimeConfig;
import com.jubitus.travellers.ground.ColumnGround;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Catchup travel: a follower too far behind its leader (or stuck near its slot) is handed from
 * {@code EntityAIFollowLeader} to {@code EntityAITravel}, which steers toward {@link #dynamicGoal()}
 * until {@link #tickExit()} hands it back. The two AI tasks swap the movement mutex purely off {@link #isActive()}.
 */
public final class FollowCatchup {

    private final EntityTraveller unit;
    private boolean active;
    // The chased entity: a group leader, an escort leader or a worker's owner.
    @Nullable private UUID targetId;
    @Nullable private BlockPos cachedGoal;
    private long nextRefreshTick;
    private int virtualRepathCooldown = -1;

    FollowCatchup(EntityTraveller unit) {
        this.unit = unit;
    }

    /** True once per 40 ticks, staggered per unit, while following an offscreen leader. */
    boolean virtualRepathDue() {
        if (virtualRepathCooldown < 0) {
            virtualRepathCooldown = Math.floorMod(unit.getEntityId(), 40);
        }
        if (--virtualRepathCooldown > 0) return false;
        virtualRepathCooldown = 40;
        return true;
    }

    /** Starts catchup toward an offscreen leader when the follower is far from its slot there. */
    void beginIfFarFromVirtualSlot(UUID leaderId, BlockPos slot) {
        double startDist = FollowRuntimeConfig.get().catchup.stopDistanceVisible * 2.0D;
        if (!active && unit.getDistanceSqToCenter(slot) > startDist * startDist) {
            begin(leaderId);
        }
    }

    public boolean isActive() {
        return active;
    }

    @Nullable
    public UUID getTargetId() {
        return targetId;
    }

    public void start(@Nullable EntityLivingBase target) {
        if (target == null || target.dimension != unit.dimension) return;
        begin(target.getUniqueID());
    }

    public void begin(UUID target) {
        this.targetId = target;
        this.cachedGoal = null;
        this.nextRefreshTick = 0L;
        this.active = true;
        unit.onCatchupBegin();
        unit.getTravelCommon().clearRoute(true);
    }

    /** Ends catchup; {@code EntityAIFollowLeader} takes back over next tick. */
    public void stop() {
        this.active = false;
        this.targetId = null;
        this.cachedGoal = null;
        this.nextRefreshTick = 0L;
        unit.getTravelCommon().clearRoute(false);
        unit.onCatchupEnd();
    }

    /** The target's surface position, refreshed periodically; its offscreen slot while it is virtual. */
    @Nullable
    public BlockPos dynamicGoal() {
        if (!active) return null;
        if (!(unit.world instanceof WorldServer)) return cachedGoal;

        EntityLivingBase target = resolveTarget();
        if (target == null) {
            BlockPos slot = unit.getOffscreenLeaderSlot();
            return slot != null ? slot : cachedGoal;
        }

        long now = unit.world.getTotalWorldTime();
        if (cachedGoal == null || now >= nextRefreshTick) {
            cachedGoal = surfaceNear(unit.world, target);
            nextRefreshTick = now + FollowRuntimeConfig.get().catchup.repathIntervalTicks;
        }
        return cachedGoal;
    }

    /** Per-tick exit watch: ends catchup once close to the slot or leader, or once the leader is gone. */
    public void tickExit() {
        if (unit.world.isRemote || !active) return;

        EntityLivingBase t = resolveTarget();
        if (t == null) t = unit.getEffectiveFollowTarget();

        if (t == null || t.isDead || t.dimension != unit.dimension) {
            // An offscreen leader is chased through its slot until close to it; a gone one ends catchup.
            BlockPos slot = unit.getOffscreenLeaderSlot();
            double stopVisible = FollowRuntimeConfig.get().catchup.stopDistanceVisible;
            if (slot == null || (unit.getDistanceSqToCenter(slot) <= stopVisible * stopVisible
                    && !unit.getStuckRecord().isEscalating())) {
                stop();
            }
            return;
        }

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        boolean caveLike = FollowRecoveryHelper.isLeaderMeaningfullyUnderground(unit.world, t, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(unit.world, t, cfg);
        double stopDist = caveLike ? cfg.catchup.stopDistanceCave : cfg.catchup.stopDistanceVisible;
        double distToLeader = unit.getDistance(t);

        boolean stop;
        if (unit.hasFollowerSlotGoal()) {
            // The slot can be stale if the leader moved after catchup started: being close to the leader also ends it.
            double dx = unit.posX - unit.getFollowerSlotGoalX();
            double dy = unit.posY - unit.getFollowerSlotGoalY();
            double dz = unit.posZ - unit.getFollowerSlotGoalZ();
            double distToSlot = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean closeToSlot = caveLike
                    ? (distToSlot <= stopDist && Math.abs(dy) <= cfg.cave.verticalTolerance + 1.5D)
                    : (distToSlot <= stopDist);
            stop = closeToSlot || distToLeader <= stopDist;
        } else {
            boolean visible = unit.canEntityBeSeen(t) || t.canEntityBeSeen(unit);
            double verticalGap = Math.abs(unit.posY - t.posY);
            stop = caveLike
                    ? (distToLeader <= stopDist && verticalGap <= cfg.cave.verticalTolerance + 1.5D)
                    : (distToLeader <= stopDist && (visible || distToLeader <= stopDist * 0.75D));
        }

        // Near but trapped (a pit under the leader): the travel ladder must finish before follow resumes.
        if (stop && !unit.getStuckRecord().isEscalating()) stop();
    }

    @Nullable
    private EntityLivingBase resolveTarget() {
        if (targetId == null || !(unit.world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) unit.world).getEntityFromUuid(targetId);
        return e instanceof EntityLivingBase && !e.isDead ? (EntityLivingBase) e : null;
    }

    /** Ground under the target, or its own feet when it is underground or enclosed. */
    public static BlockPos surfaceNear(World world, EntityLivingBase target) {
        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        BlockPos actual = new BlockPos(target.posX, target.getEntityBoundingBox().minY, target.posZ);
        if (FollowRecoveryHelper.isLeaderMeaningfullyUnderground(world, target, cfg)
                || FollowRecoveryHelper.isEnclosedFollowSpace(world, target, cfg)) {
            return actual;
        }
        int groundY = ColumnGround.topLayer(world, actual.getX(), actual.getZ(), true);
        return groundY != ColumnGround.NONE ? new BlockPos(actual.getX(), groundY, actual.getZ()) : actual;
    }
}
