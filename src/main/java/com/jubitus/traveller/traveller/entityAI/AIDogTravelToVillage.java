package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

class AIDogTravelToVillage extends EntityAIBase {
    private static final int RECALC_EVERY = 12; // ticks
    private final EntityTravellerDog dog;
    private final double speed;
    private final int stepBlocks;
    private BlockPos targetVillage = null;
    private BlockPos waypoint = null;
    private int recalcTicker = 0;

    AIDogTravelToVillage(EntityTravellerDog dog, double speed, int stepBlocks) {
        this.dog = dog;
        this.speed = speed;
        this.stepBlocks = Math.max(12, stepBlocks);
        this.setMutexBits(1 | 2); // MOVE + LOOK
    }

    @Override
    public boolean shouldExecute() {
        if (!dog.shouldRunOrphanTravel()) return false;
        if (dog.isInWater() || dog.isSitting() || dog.isHandActive()) return false;

        // already “inside” a village? (reuse your helper)
        if (EntityTraveller.isNearAnyVillage(dog.world, dog.getPosition(), 24)) return false;

        targetVillage = dog.pickNearestVillage();
        if (targetVillage == null) return false;

        waypoint = nextWaypointToward(targetVillage, stepBlocks);
        return waypoint != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (!dog.shouldRunOrphanTravel()) return false;
        if (dog.isSitting() || dog.isHandActive()) return false;
        // stop once we’re near a village
        return !EntityTraveller.isNearAnyVillage(dog.world, dog.getPosition(), 24);
    }

    @Override
    public void startExecuting() {
        pushPath();
    }

    @Override
    public void resetTask() {
        waypoint = null;
        targetVillage = null;
        dog.getNavigator().clearPath();
    }

    @Override
    public void updateTask() {
        if (targetVillage == null) return;

        // close enough to waypoint? step again
        if (waypoint != null && dog.getDistanceSqToCenter(waypoint) <= 3.0) {
            waypoint = nextWaypointToward(targetVillage, stepBlocks);
            pushPath();
        }

        // occasional repath so we don't get stale
        if ((recalcTicker++ % RECALC_EVERY) == 0) pushPath();
    }

    private void pushPath() {
        if (waypoint == null) return;
        dog.getNavigator().tryMoveToXYZ(
                waypoint.getX() + 0.5,
                waypoint.getY(),
                waypoint.getZ() + 0.5,
                speed
        );
    }

    @Nullable
    private BlockPos nextWaypointToward(BlockPos dest, int step) {
        BlockPos from = dog.getPosition();
        Vec3d dir = new Vec3d(dest.getX() - from.getX(), 0, dest.getZ() - from.getZ());
        if (dir.lengthSquared() < 1.0) return null;
        dir = dir.normalize();

        // propose a point step blocks ahead
        int nx = from.getX() + (int) Math.round(dir.x * step);
        int nz = from.getZ() + (int) Math.round(dir.z * step);
        BlockPos guess = new BlockPos(nx, from.getY(), nz);

        // keep it chunk-safe without forcing loads
        if (!(dog.world instanceof WorldServer)) return guess;
        WorldServer ws = (WorldServer) dog.world;
        int cx = nx >> 4, cz = nz >> 4;
        if (!ws.getChunkProvider().chunkExists(cx, cz)) {
            // fall back to a shorter step that’s still in the current chunk ring
            nx = from.getX() + (int) Math.round(dir.x * Math.max(8, step / 2));
            nz = from.getZ() + (int) Math.round(dir.z * Math.max(8, step / 2));
            guess = new BlockPos(nx, from.getY(), nz);
            cx = nx >> 4;
            cz = nz >> 4;
            if (!ws.getChunkProvider().chunkExists(cx, cz)) return null;
        }

        // settle Y on a reasonable standing surface
        return findSurface(guess);
    }

    @Nullable
    private BlockPos findSurface(BlockPos guess) {
        World w = dog.world;
        if (!w.isAreaLoaded(guess, 1)) return null;

        BlockPos top = w.getTopSolidOrLiquidBlock(guess);

        // try a small up/down probe so bridges/slabs/leaves work
        BlockPos p = top;
        for (int i = 0; i < 2; i++) {
            if (isStandable(p)) return p;
            p = p.up();
        }
        p = top;
        for (int i = 0; i < 8; i++) {
            if (isStandable(p)) return p;
            p = p.down();
        }
        return null;
    }

    private boolean isStandable(BlockPos feet) {
        World w = dog.world;
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;

        IBlockState below = w.getBlockState(feet.down());
        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;

        // flat-enough or solid top?
        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID)
            return atFeet.getMaterial().isReplaceable() && atHead.getMaterial().isReplaceable();

        AxisAlignedBB bb = below.getBoundingBox(w, feet.down());
        boolean thinTop = bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001;
        return thinTop && atFeet.getMaterial().isReplaceable() && atHead.getMaterial().isReplaceable();
    }
}

