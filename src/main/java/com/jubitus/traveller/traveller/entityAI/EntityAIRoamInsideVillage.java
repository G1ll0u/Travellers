package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Random;

public class EntityAIRoamInsideVillage extends EntityAIBase {
    private final EntityTraveller mob;
    private final int minPause;             // ticks
    private final int maxPause;             // ticks
    private final double roamSpeed;
    private int minStay, maxStay;
    private BlockPos village;               // current village center (cached)
    private BlockPos roamTarget;            // current small local target
    private int pauseTicks = 0;
    private int timeInVillage = 0;
    private int stayDuration; // <-- new
    private boolean inside = false;

    public EntityAIRoamInsideVillage(EntityTraveller mob,
                                     double roamSpeed,
                                     int minPause, int maxPause,
                                     int minStay, int maxStay) {
        this.mob = mob;
        this.roamSpeed = roamSpeed;
        this.minPause = minPause;
        this.maxPause = maxPause;
        this.minStay = minStay;
        this.maxStay = maxStay;
        this.setMutexBits(1); // MOVE
    }

    public void setStayDuration(int minTicks, int maxTicks) {
        this.minStay = minTicks;
        this.maxStay = maxTicks;
    }

    @Override
    public boolean shouldExecute() {
        if (mob.getDepartCooldownTicks() > 0) return false;
        if (mob.isReadyToDepart()) return false;

        final BlockPos target = mob.getTargetVillage();
        if (target == null) return false;

        // Soft sanity: allow either to be null; if both exist, make sure they’re roughly the same place
        final BlockPos near = mob.getNearestVillage();
        if (near != null && !approxSameVillage2D(near, target, Math.max(24, mob.arrivalRadiusBlocks(target)))) {
            return false;
        }

        // <<< key change: rely on the SAME arrival policy as travel >>>
        return mob.isAtTargetStop();
    }

    private static boolean approxSameVillage2D(@Nullable BlockPos a, @Nullable BlockPos b, int tol) {
        if (a == null || b == null) return false;
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return (dx * dx + dz * dz) <= (tol * tol);
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.getDepartCooldownTicks() > 0) return false;
        if (!mob.isVillageIdling()) return false;

        final BlockPos target = mob.getTargetVillage();
        if (target == null) return false;

        final BlockPos near = mob.getNearestVillage();
        if (near != null && !approxSameVillage2D(near, target, Math.max(24, mob.arrivalRadiusBlocks(target)))) {
            return false;
        }

        // Stay active while we’re considered "inside" by the same rule travel uses
        return mob.isAtTargetStop();
    }


    @Override
    public void startExecuting() {
        // pick a robust center
        BlockPos target = mob.getTargetVillage();
        BlockPos near = mob.getNearestVillage();
        this.village = (target != null) ? target : near;

        // If we can’t find a center, abort cleanly
        if (this.village == null) {
            mob.setVillageIdling(false);
            mob.setReadyToDepart(false);
            mob.setRoamTarget(null);
            return;
        }

        this.timeInVillage = 0;
        this.pauseTicks = 0;
        this.roamTarget = null;
        this.stayDuration = minStay + mob.getRNG().nextInt(Math.max(1, maxStay - minStay + 1));
        mob.setVillageIdling(true);
        mob.setReadyToDepart(false);
        mob.setRoamTarget(null);
        inside = mob.isAtTargetStop();
    }

    @Override
    public void resetTask() {
        mob.setVillageIdling(false);
        mob.setRoamTarget(null);
        this.roamTarget = null;
    }

    // ---- helpers ----

    @Override
    public void updateTask() {
        // Recover/validate village center
        if (this.village == null) {
            BlockPos target = mob.getTargetVillage();
            BlockPos near = mob.getNearestVillage();
            this.village = (target != null) ? target : near;
            if (this.village == null) {
                // nothing to roam around; exit gracefully
                mob.setVillageIdling(false);
                mob.setReadyToDepart(true);
                mob.getNavigator().clearPath();
                return;
            }
        }

        timeInVillage++;
        mob.setVillageIdleTicks(timeInVillage);

        if (timeInVillage >= stayDuration) {
            mob.setVillageIdling(false);
            mob.setReadyToDepart(true);
            mob.getNavigator().clearPath();
            return;
        }

        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }

        // Need a new local roam target?
        if (roamTarget == null || mob.getDistanceSqToCenter(roamTarget) < 2.0) {
            int radius = Math.max(6, EntityTraveller.VILLAGE_RADIUS - 4); // keep sane/positive
            roamTarget = pickLocalSpotInsideVillage(this.village, radius);
            mob.setRoamTarget(roamTarget);
            if (roamTarget != null) {
                mob.getNavigator().tryMoveToXYZ(roamTarget.getX() + 0.5, roamTarget.getY(), roamTarget.getZ() + 0.5, this.roamSpeed);
            } else {
                // Couldn't find a spot; short pause then retry
                pauseTicks = 20 + mob.getRNG().nextInt(20);
                return;
            }
        }

        if (roamTarget != null && mob.getDistanceSqToCenter(roamTarget) <= 2.0) {
            roamTarget = null;
            pauseTicks = minPause + mob.getRNG().nextInt(Math.max(1, maxPause - minPause + 1));
        }

        if (mob.ticksExisted % 20 == 0 && roamTarget != null) {
            mob.getNavigator().tryMoveToXYZ(roamTarget.getX() + 0.5, roamTarget.getY(), roamTarget.getZ() + 0.5, this.roamSpeed);
        }
    }

    /**
     * Pick a random standable spot within 'radius' of 'center', biasing toward loaded & valid positions.
     */
    private BlockPos pickLocalSpotInsideVillage(@Nullable BlockPos center, int radius) {
        if (center == null) return null;
        final World w = mob.world;
        final Random r = mob.getRNG();
        radius = Math.max(6, radius);

        for (int tries = 0; tries < 24; tries++) {
            double angle = r.nextDouble() * Math.PI * 2.0;
            double dist = 6.0 + r.nextDouble() * (radius - 6.0);
            int x = center.getX() + (int)Math.round(Math.cos(angle) * dist);
            int z = center.getZ() + (int)Math.round(Math.sin(angle) * dist);

            // Find a plausible ground Y with a wider vertical sweep
            BlockPos surface = findGoodSurface(new BlockPos(x, center.getY(), z), 24);
            if (surface == null) continue;
            if (!isAreaLoaded(surface, 1)) continue;
            if (!isStandable(surface)) continue;

            // NEW: only accept spots we can actually path to
            if (mob.getNavigator().getPathToPos(surface) != null) {
                if (!farEnough(surface, mob.getPosition(), 9.0)) continue; // at least 3 blocks away
                return surface;
            }
        }
        return null;
    }

    private @Nullable BlockPos findGoodSurface(BlockPos seed, int verticalWindow) {
        if (!isAreaLoaded(seed, 1)) return null;

        // Start from top solid/liquid at column
        int topY = mob.world.getHeight(seed).getY();
        if (topY <= 0) return null;

        // Search down farther than 8 to avoid getting stuck on roofs/leaves
        BlockPos pos = new BlockPos(seed.getX(), topY, seed.getZ());
        for (int dy = 0; dy < verticalWindow; dy++) {
            if (isStandable(pos)) return pos;
            pos = pos.down();
        }

        // If downward failed, try scanning upward a little (underground TH edge cases)
        pos = new BlockPos(seed.getX(), Math.max(1, seed.getY() - 2), seed.getZ());
        for (int dy = 0; dy < verticalWindow; dy++) {
            if (isStandable(pos)) return pos;
            pos = pos.up();
        }
        return null;
    }


    private boolean isAreaLoaded(BlockPos pos, int radius) {
        if (!(mob.world instanceof WorldServer)) return mob.world.isAreaLoaded(pos, radius);
        WorldServer ws = (WorldServer) mob.world;
        int minCX = (pos.getX() - radius) >> 4, maxCX = (pos.getX() + radius) >> 4;
        int minCZ = (pos.getZ() - radius) >> 4, maxCZ = (pos.getZ() + radius) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++)
                if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;
        return true;
    }

    private boolean isStandable(BlockPos feet) {
        IBlockState below = mob.world.getBlockState(feet.down());
        IBlockState at = mob.world.getBlockState(feet);
        IBlockState head = mob.world.getBlockState(feet.up());
        return (below.isFullCube() || below.isSideSolid(mob.world, feet.down(), EnumFacing.UP))
                && (at.getMaterial().isReplaceable() || at.getCollisionBoundingBox(mob.world, feet) == null)
                && (head.getMaterial().isReplaceable() || head.getCollisionBoundingBox(mob.world, feet.up()) == null);
    }
    private boolean farEnough(BlockPos a, BlockPos b, double minSq) {
        double dx = (a.getX() + 0.5) - (b.getX() + 0.5);
        double dz = (a.getZ() + 0.5) - (b.getZ() + 0.5);
        return (dx*dx + dz*dz) >= minSq;
    }
}
