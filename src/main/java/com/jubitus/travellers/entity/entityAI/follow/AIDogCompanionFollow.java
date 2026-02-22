package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.entity.travellers.others.EntityTravellerDog;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Random;

public class AIDogCompanionFollow extends EntityAIBase {

    private final EntityTravellerDog dog;
    private final World world;
    private final double speed;
    private final double teleportDistSq;
    private final double leashRadiusMin;
    private final double leashRadiusMax;
    private EntityLivingBase owner;
    private int rePathDelay;
    private int modeTicks;
    private CompanionMode mode = CompanionMode.AROUND;

    // owner idle tracking
    private double lastOwnerX, lastOwnerY, lastOwnerZ;
    private int ownerStillTicks;
    private boolean ownerIdle;

    public AIDogCompanionFollow(EntityTravellerDog dog, double speed) {
        this.dog = dog;
        this.world = dog.world;
        this.speed = speed;

        this.leashRadiusMin = 2.0D;
        this.leashRadiusMax = 6.0D;

        // Only teleport if we *really* lost him (failsafe)
        this.teleportDistSq = 64.0D * 64.0D; // 64 blocks

        this.setMutexBits(3); // move + look
    }

    @Override
    public boolean shouldExecute() {
        owner = dog.getOwner();
        if (dog.getAttackTarget() != null) return false;
        if (dog.getRevengeTarget() != null) return false;
        if (owner == null) return false;
        if (!dog.isTamed()) return false;
        if (owner instanceof EntityPlayer && ((EntityPlayer) owner).isSpectator()) return false;

        // If dog is currently doing orphan travel, don’t run.
        return !dog.shouldRunOrphanTravel();
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (owner == null || !owner.isEntityAlive()) return false;

        // ✅ STOP following if combat starts, so melee AI can run
        if (dog.getAttackTarget() != null) return false;
        if (dog.getRevengeTarget() != null) return false;

        // Optional but recommended: sitting dogs shouldn't keep pathing
        if (dog.isSitting()) return false;

        return !dog.shouldRunOrphanTravel();
    }


    @Override
    public void startExecuting() {
        rePathDelay = 0;
        modeTicks = 0;
        lastOwnerX = owner.posX;
        lastOwnerY = owner.posY;
        lastOwnerZ = owner.posZ;
        ownerStillTicks = 0;
        ownerIdle = false;
    }

    @Override
    public void resetTask() {
        owner = null;
        dog.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        if (owner == null) return;
// ✅ If combat started, don't fight the combat AI over navigation
        if (dog.getAttackTarget() != null || dog.getRevengeTarget() != null) {
            dog.getNavigator().clearPath();
            return;
        }
        dog.getLookHelper().setLookPositionWithEntity(owner, 30.0F, 30.0F);

        // === Owner movement tracking (horizontal only) ===
        double dx = owner.posX - lastOwnerX;
        double dz = owner.posZ - lastOwnerZ;
        double ownerMoveSq = dx * dx + dz * dz;

        if (ownerMoveSq < 0.0001D) { // basically still or VERY slow
            ownerStillTicks++;
        } else {
            ownerStillTicks = 0;
            lastOwnerX = owner.posX;
            lastOwnerY = owner.posY;
            lastOwnerZ = owner.posZ;
        }

        double ownerSpeedSq = owner.motionX * owner.motionX + owner.motionZ * owner.motionZ;

        // Idle if: has been nearly still for ~2s AND moving very slowly
        ownerIdle = (ownerStillTicks > 10) || (ownerSpeedSq < 0.0001D);

        double distSq = dog.getDistanceSq(owner);

        // === Rare failsafe teleport if we are ridiculously far ===
        if (distSq > teleportDistSq) {
            tryTeleportNearOwner();
            return;
        }

        // === Companion movement ===
        if (--rePathDelay <= 0) {
            if (ownerIdle) {
                // Owner idle → dog roams more, but still slower than when you move
                rePathDelay = 14 + dog.getRNG().nextInt(10); // ~0.7–1.2s
            } else {
                // Owner moving → more responsive
                rePathDelay = 8 + dog.getRNG().nextInt(4); // ~0.4–0.6s
            }

            updateModeIfNeeded(ownerIdle);
            Vec3d target = pickCompanionTargetPos(distSq, ownerIdle);

            if (target != null) {
                double effectiveSpeed = ownerIdle ? (speed * 0.4D) : speed;

                if (!ownerIdle || dog.getRNG().nextInt(4) != 0) {
                    // idle: 75% of the time he moves, 25% he just chills
                    dog.getNavigator().tryMoveToXYZ(target.x, target.y, target.z, effectiveSpeed);
                } else {
                    dog.getNavigator().clearPath();
                }
            }
        }

    }

    /**
     * Only used as a rare failsafe when completely lost.
     */
    private void tryTeleportNearOwner() {
        BlockPos base = new BlockPos(owner).down();
        for (int i = 0; i < 10; i++) {
            int dx = (dog.getRNG().nextInt(5) - 2);
            int dz = (dog.getRNG().nextInt(5) - 2);
            BlockPos p = base.add(dx, 0, dz);

            if (!world.isBlockFullCube(p) && !world.isBlockFullCube(p.up())) {
                dog.setLocationAndAngles(
                        p.getX() + 0.5D,
                        p.getY(),
                        p.getZ() + 0.5D,
                        dog.rotationYaw,
                        dog.rotationPitch
                );
                dog.getNavigator().clearPath();
                return;
            }
        }
    }

    /**
     * Change companion “mode” every few seconds to add personality.
     */
    private void updateModeIfNeeded(boolean ownerIdleNow) {
        if (--modeTicks > 0) return;

        if (ownerIdleNow) {
            // While owner is idle, stay in AROUND mode longer and don’t run ahead
            modeTicks = 100 + dog.getRNG().nextInt(60); // 5–8 seconds
            mode = CompanionMode.AROUND;
            return;
        }

        // Owner moving: normal personality cycling
        modeTicks = 80 + dog.getRNG().nextInt(80); // 4–8 seconds

        int r = dog.getRNG().nextInt(10);
        if (r <= 4) {
            mode = CompanionMode.AROUND;
        } else if (r <= 7) {
            mode = CompanionMode.AHEAD;
        } else {
            mode = CompanionMode.FALLBACK;
        }
    }

    /**
     * Compute where the dog *wants* to be relative to owner and owner’s heading.
     */
    @Nullable
    private Vec3d pickCompanionTargetPos(double distSq, boolean ownerIdleNow) {
        Vec3d ownerPos = new Vec3d(owner.posX, owner.posY, owner.posZ);

        float yawRad = owner.rotationYaw * ((float) Math.PI / 180F);
        Vec3d forward = new Vec3d(-MathHelper.sin(yawRad), 0, MathHelper.cos(yawRad));
        Vec3d right = new Vec3d(forward.z, 0, -forward.x); // perpendicular

        Random rand = dog.getRNG();

        double radius = leashRadiusMin + rand.nextDouble() * (leashRadiusMax - leashRadiusMin);
        double ahead;
        double side;

        if (ownerIdleNow) {
            // Owner is basically standing → roam in a small ring around them
            radius = 3.0D + rand.nextDouble() * 3.0D; // 3–6 blocks

            // Choose a random point around the owner, not just tiny shuffles
            double angle = rand.nextDouble() * (Math.PI * 2.0D);
            ahead = Math.cos(angle) * radius * 0.7D; // mostly around, not way ahead
            side = Math.sin(angle) * radius * 0.7D;
        } else {
            // Normal moving behaviour
            switch (mode) {
                case AROUND:
                    ahead = (rand.nextDouble() * 0.4D - 0.2D) * radius;
                    side = (rand.nextDouble() * 1.2D - 0.6D) * radius;
                    break;

                case AHEAD:
                    ahead = radius * 0.8D + rand.nextDouble() * 1.5D;    // a bit in front
                    side = (rand.nextDouble() * 0.6D - 0.3D) * radius;  // small side offset
                    break;

                case FALLBACK:
                default:
                    ahead = -radius * (0.8D + rand.nextDouble() * 0.8D); // behind
                    side = (rand.nextDouble() * 0.6D - 0.3D) * radius * 0.5D;
                    break;
            }
        }

        // If we are already really close, nudge away a bit
        if (distSq < leashRadiusMin * leashRadiusMin) {
            ahead += 1.0D;
        }

        Vec3d offset = forward.scale(ahead).add(right.scale(side));
        Vec3d dest = ownerPos.add(offset);

        BlockPos ground = new BlockPos(dest.x, dest.y, dest.z);
        ground = findGroundBelow(ground, 4);
        return new Vec3d(ground.getX() + 0.5D, ground.getY(), ground.getZ() + 0.5D);
    }

    private BlockPos findGroundBelow(BlockPos pos, int maxDown) {
        BlockPos p = pos;
        for (int i = 0; i < maxDown; i++) {
            if (!world.isAirBlock(p.down())) break;
            p = p.down();
        }
        return p;
    }

    private enum CompanionMode {
        AROUND,     // normal follow, around the sides/behind
        AHEAD,      // run slightly ahead in owner direction
        FALLBACK    // drop back behind owner
    }
}

