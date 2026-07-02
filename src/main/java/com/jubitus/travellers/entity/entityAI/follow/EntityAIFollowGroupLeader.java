package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.utils.world.camp.CampsiteManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Simple follow AI for travellers and raiders that belong to a group.
 * Followers stay near their leader; if stuck, they teleport near it.
 * Leaders are unaffected — they run their normal EntityAITravel.
 */
public class EntityAIFollowGroupLeader extends EntityAIBase {

    private static final double STOP_DIST = 3.0;
    private static final double DIRECT_STEER_DIST = 10.0;

    // After this many ticks without meaningful progress, teleport near leader.
    private static final int STAGNATION_TELEPORT_TICKS = 160;

    private final EntityTraveller follower;
    private final double speed;

    @Nullable private EntityTraveller leader;
    private int repathCooldown;
    private int stagnationTicks;
    private double lastProgressX;
    private double lastProgressZ;
    private boolean hasProgressAnchor;

    // Cached leader-camping check (campOf iterates camp occupants — refreshed every 10 ticks).
    private int campCheckCooldown = 0;
    private boolean leaderIsCamping = false;

    public EntityAIFollowGroupLeader(EntityTraveller follower, double speed) {
        this.follower = follower;
        this.speed = speed;
        setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (follower.getAttackTarget() != null && !follower.getAttackTarget().isDead) return false;
        EntityTraveller l = resolveLeader();
        if (l == null) return false;
        this.leader = l;
        // Yield to EntityAICampStay when the leader is camping.
        if (isLeaderCamping()) return false;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (leader == null || leader.isDead) return false;
        if (follower.getAttackTarget() != null && !follower.getAttackTarget().isDead) return false;
        if (isLeaderCamping()) return false;
        return true;
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
        stagnationTicks = 0;
        hasProgressAnchor = false;
    }

    @Override
    public void resetTask() {
        follower.getNavigator().clearPath();
        leader = null;
        hasProgressAnchor = false;
        campCheckCooldown = 0;
        leaderIsCamping = false;
    }

    @Override
    public void updateTask() {
        // Re-resolve leader each update in case it re-materialized with a new entity instance.
        EntityTraveller fresh = resolveLeader();
        if (fresh == null || fresh.isDead) {
            resetTask();
            return;
        }
        leader = fresh;

        if (follower.getAttackTarget() != null && !follower.getAttackTarget().isDead) {
            follower.getNavigator().clearPath();
            return;
        }

        double dist = horizontalDist(leader);

        tickStagnation(dist);

        if (stagnationTicks >= STAGNATION_TELEPORT_TICKS) {
            tryTeleportToLeader();
            return;
        }

        if (dist <= STOP_DIST) {
            follower.getNavigator().clearPath();
            return;
        }

        if (repathCooldown > 0) {
            repathCooldown--;
            return;
        }

        if (dist <= DIRECT_STEER_DIST) {
            follower.getNavigator().clearPath();
            follower.getMoveHelper().setMoveTo(leader.posX, leader.posY, leader.posZ, speed);
            repathCooldown = 5;
        } else {
            boolean moved = follower.getNavigator().tryMoveToEntityLiving(leader, speed);
            repathCooldown = moved ? 18 : 8;
        }
    }

    private void tickStagnation(double distToLeader) {
        // Only track stagnation when we are far from the leader and not at goal
        if (distToLeader <= STOP_DIST + 1.0) {
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

    private void tryTeleportToLeader() {
        BlockPos spot = findSafeSpotNear(leader);
        if (spot == null) {
            // No safe spot found; reset stagnation counter and retry later
            stagnationTicks = STAGNATION_TELEPORT_TICKS / 2;
            return;
        }
        follower.getNavigator().clearPath();
        follower.setPositionAndUpdate(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
        follower.motionX = 0;
        follower.motionY = 0;
        follower.motionZ = 0;
        stagnationTicks = 0;
        repathCooldown = 20;
        hasProgressAnchor = false;
    }

    @Nullable
    private BlockPos findSafeSpotNear(EntityLivingBase target) {
        World world = follower.world;
        int cx = MathHelper.floor(target.posX);
        int cy = MathHelper.floor(target.posY);
        int cz = MathHelper.floor(target.posZ);
        int r = 10;

        for (int attempt = 0; attempt < 20; attempt++) {
            int dx = world.rand.nextInt(r * 2 + 1) - r;
            int dz = world.rand.nextInt(r * 2 + 1) - r;
            if (dx * dx + dz * dz < 4) continue; // stay at least 2 blocks from leader

            BlockPos col = new BlockPos(cx + dx, 0, cz + dz);
            if (!world.isBlockLoaded(col)) continue;

            BlockPos solid = world.getTopSolidOrLiquidBlock(col);
            if (solid.getY() < 1) continue;
            if (Math.abs(solid.getY() - cy) > 12) continue;

            BlockPos standPos = solid.up();
            if (world.getBlockState(standPos).getMaterial().isSolid()) continue;
            if (world.getBlockState(standPos.up()).getMaterial().isSolid()) continue;
            return standPos;
        }
        return null;
    }

    private boolean isLeaderCamping() {
        if (!TravellersConfig.CAMP_EVENTS.enabled || follower.world.isRemote || leader == null) return false;
        if (campCheckCooldown > 0) {
            campCheckCooldown--;
            return leaderIsCamping;
        }
        leaderIsCamping = CampsiteManager.get((WorldServer) follower.world).campOf(leader.getUniqueID()) != null;
        campCheckCooldown = 10;
        return leaderIsCamping;
    }

    @Nullable
    private EntityTraveller resolveLeader() {
        UUID leaderUUID = follower.getGroupLeaderUUID();
        if (leaderUUID == null) return null;
        if (follower.world.isRemote) return null;
        net.minecraft.entity.Entity e = ((net.minecraft.world.WorldServer) follower.world).getEntityFromUuid(leaderUUID);
        if (!(e instanceof EntityTraveller)) return null;
        if (e.isDead) return null;
        return (EntityTraveller) e;
    }

    private double horizontalDist(EntityLivingBase target) {
        double dx = follower.posX - target.posX;
        double dz = follower.posZ - target.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
