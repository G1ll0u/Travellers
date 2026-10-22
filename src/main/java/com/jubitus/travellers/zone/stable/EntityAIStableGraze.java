package com.jubitus.travellers.zone.stable;

import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.ai.RandomPositionGenerator;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Bounded wander for a stable-tracked horse: like vanilla {@code EntityAIWander}, but every
 * candidate point is checked against the zone's box first and simply skipped if it falls outside.
 * The horse's own choice of where to walk can therefore never be the reason it leaves the zone -
 * see {@link StableContainmentGuard} for the independent hard backstop covering every other reason
 * (combat knockback, water drift, anything else that moves it without asking this task).
 *
 * <p>Reuses vanilla's own {@link RandomPositionGenerator} for "is there a walkable spot near here"
 * rather than a bespoke surface search - only the containment filter on top is new.
 */
public class EntityAIStableGraze extends EntityAIBase {

    /** Vanilla horses wander at this navigator speed. */
    private static final double WANDER_SPEED = 0.7D;

    private final EntityHorse horse;
    private final BlockPos center;
    private final double halfWidth;
    private final double halfDepth;
    private int cooldown;
    private double targetX, targetY, targetZ;

    public EntityAIStableGraze(EntityHorse horse, BlockPos center, int halfWidth, int halfDepth) {
        this.horse = horse;
        this.center = center;
        // Stay a block clear of the actual edge, not right up against it.
        this.halfWidth = Math.max(1, halfWidth - 1);
        this.halfDepth = Math.max(1, halfDepth - 1);
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (!horse.getNavigator().noPath()) return false;

        for (int i = 0; i < 4; i++) {
            Vec3d target = RandomPositionGenerator.findRandomTarget(horse, 6, 3);
            if (target == null) continue;
            if (isInsideBox(target.x, target.z)) {
                targetX = target.x;
                targetY = target.y;
                targetZ = target.z;
                cooldown = 60 + horse.getRNG().nextInt(100);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return !horse.getNavigator().noPath();
    }

    @Override
    public void startExecuting() {
        horse.getNavigator().tryMoveToXYZ(targetX, targetY, targetZ, WANDER_SPEED);
    }

    private boolean isInsideBox(double x, double z) {
        return Math.abs(x - (center.getX() + 0.5D)) <= halfWidth
                && Math.abs(z - (center.getZ() + 0.5D)) <= halfDepth;
    }
}
