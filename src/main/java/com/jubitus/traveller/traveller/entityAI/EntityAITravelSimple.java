package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.init.Blocks;

/**
 * Very simple waypoint-based travel AI for EntityTraveller.
 *
 * - Walks in straight-ish segments toward the current target village.
 * - Works in waypoints (step distance is configurable).
 * - Stops when "close enough" to the village.
 * - Never explicitly loads chunks: only uses positions in already-loaded chunks.
 * - Avoids non-water liquids (lava, mod fluids, etc.), but allows vanilla water.
 * - Optionally toggles the traveller's "travel torch" when moving at night.
 */
public class EntityAITravelSimple extends EntityAIBase {

    private final EntityTraveller traveller;
    private final double moveSpeed;

    private final int minStep;
    private final int maxStep;
    private final int arrivalRadius;

    private BlockPos villageTargetSnapshot;
    private BlockPos currentWaypoint;
    private int pathRecalcCooldown;

    public EntityAITravelSimple(EntityTraveller traveller,
                                double moveSpeed,
                                int minStep,
                                int maxStep,
                                int arrivalRadius) {
        this.traveller = traveller;
        this.moveSpeed = moveSpeed;
        this.minStep = Math.max(1, minStep);
        this.maxStep = Math.max(this.minStep, maxStep);
        this.arrivalRadius = Math.max(1, arrivalRadius);

        // Only conflicts with other movement tasks.
        this.setMutexBits(1);
    }

    @Override
    public boolean shouldExecute() {
        BlockPos village = traveller.getTargetVillage();
        if (village == null) return false;

        if (isBusy()) return false;

        // Let it start even if already somewhat close; we'll stop in shouldContinueExecuting.
        this.villageTargetSnapshot = village;
        this.currentWaypoint = null;
        this.pathRecalcCooldown = 0;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        BlockPos currentTarget = traveller.getTargetVillage();
        if (currentTarget == null) return false;

        if (isBusy()) return false;

        // If the current target moved *a lot*, we give up and let the task restart.
        if (!roughlySameTarget(currentTarget, villageTargetSnapshot)) return false;

        if (isInsideArrivalRadius(currentTarget)) return false;

        return true;
    }

    @Override
    public void startExecuting() {
        currentWaypoint = null;
        pathRecalcCooldown = 0;

        if (!traveller.world.isRemote && !traveller.world.isDaytime()) {
            traveller.setTravelTorchVisible(true);
        }
    }

    @Override
    public void resetTask() {
        traveller.getNavigator().clearPath();
        currentWaypoint = null;
        pathRecalcCooldown = 0;

        if (!traveller.world.isRemote) {
            traveller.setTravelTorchVisible(false);
        }
    }

    @Override
    public void updateTask() {
        BlockPos village = traveller.getTargetVillage();
        if (village == null) return;

        if (needsNewWaypoint()) {
            chooseNextWaypoint(village);
        }

        if (currentWaypoint != null) {
            if (pathRecalcCooldown <= 0) {
                pathRecalcCooldown = 8; // slightly more eager than before
                double x = currentWaypoint.getX() + 0.5;
                double y = currentWaypoint.getY() + 0.1;
                double z = currentWaypoint.getZ() + 0.5;
                traveller.getNavigator().tryMoveToXYZ(x, y, z, moveSpeed);
            } else {
                pathRecalcCooldown--;
            }
        }
    }

    // -------------------------------------------------------

    private boolean isBusy() {
        if (traveller.isInCombat()) return true;
        if (traveller.isAutoEating()) return true;
        if (traveller.isHandActive()) return true;
        return false;
    }

    private boolean isInsideArrivalRadius(BlockPos village) {
        double dx = (village.getX() + 0.5) - traveller.posX;
        double dz = (village.getZ() + 0.5) - traveller.posZ;
        double distSq = dx * dx + dz * dz;
        return distSq <= (double) arrivalRadius * (double) arrivalRadius;
    }

    /** Allow minor differences (e.g. Y changes or off-by-one). */
    private boolean roughlySameTarget(BlockPos a, BlockPos b) {
        if (a == null || b == null) return false;
        int dx = Math.abs(a.getX() - b.getX());
        int dz = Math.abs(a.getZ() - b.getZ());
        return dx <= 2 && dz <= 2;
    }

    private boolean needsNewWaypoint() {
        if (currentWaypoint == null) return true;

        double distSq = traveller.getDistanceSqToCenter(currentWaypoint);
        if (distSq < 2.0 * 2.0) return true;

        if (traveller.getNavigator().noPath()) return true;

        return false;
    }

    private void chooseNextWaypoint(BlockPos village) {
        double dx = (village.getX() + 0.5) - traveller.posX;
        double dz = (village.getZ() + 0.5) - traveller.posZ;
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        if (distXZ < 1e-4) {
            currentWaypoint = null;
            return;
        }

        // Don't step deep inside arrival radius.
        double remainingOutside = Math.max(0.0, distXZ - arrivalRadius);
        double step = net.minecraft.util.math.MathHelper.clamp(remainingOutside, minStep, maxStep);

        double dirX = dx / distXZ;
        double dirZ = dz / distXZ;

        BlockPos best = null;
        double factor = 1.0;

        // Try 3 times, shorter each time if needed.
        for (int attempt = 0; attempt < 3; attempt++) {
            double sx = traveller.posX + dirX * step * factor;
            double sz = traveller.posZ + dirZ * step * factor;

            int x = net.minecraft.util.math.MathHelper.floor(sx);
            int z = net.minecraft.util.math.MathHelper.floor(sz);

            BlockPos candidate = findTopStandable(new BlockPos(x, traveller.posY, z));
            if (candidate != null && isWaypointSafe(candidate)) {
                best = candidate;
                break;
            }

            factor *= 0.5;
        }

        currentWaypoint = best;
    }

    /**
     * Very simple “top standable” search in a small vertical window
     * around the traveller’s current Y.
     */
    private BlockPos findTopStandable(BlockPos guess) {
        if (!traveller.world.isBlockLoaded(guess)) return null;

        int minY = Math.max(1, guess.getY() - 4);
        int maxY = Math.min(255, guess.getY() + 4);

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int y = maxY; y >= minY; y--) {
            mutable.setPos(guess.getX(), y, guess.getZ());
            BlockPos feet = mutable.toImmutable();
            BlockPos below = feet.down();
            BlockPos head = feet.up();

            if (!traveller.world.isBlockLoaded(below) || !traveller.world.isBlockLoaded(head))
                continue;

            // solid-ish floor
            if (!traveller.world.getBlockState(below).isSideSolid(traveller.world, below, net.minecraft.util.EnumFacing.UP))
                continue;

            // feet must not be solid full cube (no spawning inside block)
            if (traveller.world.getBlockState(feet).isFullCube())
                continue;

            // head must be free
            if (traveller.world.getBlockState(head).isFullCube())
                continue;

            return feet;
        }
        return null;
    }

    private boolean isWaypointSafe(BlockPos feet) {
        if (!traveller.world.isBlockLoaded(feet)) return false;

        // Check liquids at feet & head positions.
        BlockPos head = feet.up();
        return !isForbiddenLiquid(feet) && !isForbiddenLiquid(head);
    }

    private boolean isForbiddenLiquid(BlockPos pos) {
        if (!traveller.world.isBlockLoaded(pos)) return true;

        net.minecraft.block.state.IBlockState state = traveller.world.getBlockState(pos);
        Material mat = state.getMaterial();
        if (!mat.isLiquid()) return false;

        Block block = state.getBlock();
        if (block == Blocks.WATER || block == Blocks.FLOWING_WATER) {
            return false; // vanilla water allowed
        }
        return true; // everything else liquid = forbidden
    }
}
