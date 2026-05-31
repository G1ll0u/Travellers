package com.jubitus.travellers.entity.entityAI.follow;


import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.*;
import net.minecraft.world.World;

import javax.annotation.Nullable;


import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.block.state.BlockFaceShape;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public final class FollowRecoveryHelper {

    private static final double CAVE_FOLLOW_RESCUE_MAX_XZ = 22.0D;
    private static final double CAVE_FOLLOW_RESCUE_MAX_XZ_SQ = CAVE_FOLLOW_RESCUE_MAX_XZ * CAVE_FOLLOW_RESCUE_MAX_XZ;
    private static final double CAVE_FOLLOW_RESCUE_OVERCROWD_RADIUS = 0.35D;
    private static final double CAVE_FOLLOW_RESCUE_OVERCROWD_RADIUS_SQ =
            CAVE_FOLLOW_RESCUE_OVERCROWD_RADIUS * CAVE_FOLLOW_RESCUE_OVERCROWD_RADIUS;
    private static final int CAVE_FOLLOW_RESCUE_SEARCH_RADIUS = 24;
    private static final int CAVE_FOLLOW_RESCUE_VERTICAL = 32;

    private FollowRecoveryHelper() {
    }

    public static boolean isLeaderMeaningfullyUnderground(World world, EntityLivingBase leader) {
        return isLeaderMeaningfullyUnderground(world, leader, FollowRuntimeConfig.get());
    }

    public static boolean isLeaderMeaningfullyUnderground(World world,
                                                          EntityLivingBase leader,
                                                          @Nullable FollowRuntimeConfig cfg) {
        if (leader == null) return false;
        if (leader.isRiding() && leader.getRidingEntity() instanceof EntityBoat) return false;

        FollowRuntimeConfig use = cfg == null ? FollowRuntimeConfig.get() : cfg;
        BlockPos pos = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);

        if (!world.isBlockLoaded(pos)) return false;
        if (world.canSeeSky(pos)) return false;

        int cover = getCoverBlocks(world, pos);
        if (cover < use.cave.minCoverBlocks) return false;

        int sky = world.getLightFor(EnumSkyBlock.SKY, pos.up());
        return sky <= use.cave.maxSkyLight;
    }

    public static boolean isEnclosedFollowSpace(World world,
                                                EntityLivingBase leader,
                                                @Nullable FollowRuntimeConfig cfg) {
        if (leader == null) return false;
        if (leader.isRiding() && leader.getRidingEntity() instanceof EntityBoat) return false;

        FollowRuntimeConfig use = cfg == null ? FollowRuntimeConfig.get() : cfg;
        BlockPos pos = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);

        if (!world.isBlockLoaded(pos)) return false;
        if (isLeaderMeaningfullyUnderground(world, leader, use)) return true;
        if (world.canSeeSky(pos)) return false;
        // Any solid block above = enclosed: catches buildings, dungeons, and overhangs,
        // not just deep caves. Leaves/glass don't count (getTopSolidOrLiquidBlock skips them).
        return getCoverBlocks(world, pos) >= 1;
    }

    public static int getCoverBlocks(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) return 0;
        BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(pos.getX(), 0, pos.getZ()));
        return Math.max(0, top.getY() - pos.getY());
    }

    public static int countSolidSides(World world, BlockPos pos) {
        int solid = 0;
        for (EnumFacing facing : EnumFacing.HORIZONTALS) {
            BlockPos side = pos.offset(facing);
            BlockPos sideHead = side.up();
            if (!world.isBlockLoaded(side) || !world.isBlockLoaded(sideHead)) continue;

            IBlockState feet = world.getBlockState(side);
            IBlockState head = world.getBlockState(sideHead);

            boolean feetSolid = feet.getCollisionBoundingBox(world, side) != null;
            boolean headSolid = head.getCollisionBoundingBox(world, sideHead) != null;

            if (feetSolid || headSolid) {
                solid++;
            }
        }
        return solid;
    }

    public static boolean isFollowerRoughlyAboveLeader(EntityLivingBase follower,
                                                       EntityLivingBase leader,
                                                       double maxHorizontal,
                                                       double minVertical) {
        double dx = follower.posX - leader.posX;
        double dz = follower.posZ - leader.posZ;
        double horizSq = dx * dx + dz * dz;
        double dy = follower.posY - leader.posY;
        return horizSq <= maxHorizontal * maxHorizontal && dy >= minVertical;
    }

    @Nullable
    public static TeleportCandidate findCaveEntryTeleport(EntityTravellerWorker worker,
                                                          EntityLivingBase leader,
                                                          double minLeaderDistance,
                                                          double maxLeaderDistance) {
        return findBestSpotNear(
                worker,
                leader,
                leader.posX,
                leader.posY,
                leader.posZ,
                6,
                true,
                minLeaderDistance,
                maxLeaderDistance,
                10.0D
        );
    }

    @Nullable
    public static TeleportCandidate findShortStepTeleport(EntityTravellerWorker worker,
                                                          EntityLivingBase leader,
                                                          double maxStep,
                                                          double minLeaderDistance,
                                                          double maxLeaderDistance,
                                                          boolean requireUnderground) {
        double dx = leader.posX - worker.posX;
        double dy = leader.posY - worker.posY;
        double dz = leader.posZ - worker.posZ;

        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq < 1.0E-6D) return null;

        double dist = Math.sqrt(distSq);
        double step = Math.min(maxStep, dist);

        double nx = dx / dist;
        double ny = dy / dist;
        double nz = dz / dist;

        double anchorX = worker.posX + nx * step;
        double anchorY = worker.posY + ny * step;
        double anchorZ = worker.posZ + nz * step;

        return findBestSpotNear(
                worker,
                leader,
                anchorX,
                anchorY,
                anchorZ,
                4,
                requireUnderground,
                minLeaderDistance,
                maxLeaderDistance,
                maxStep + 2.5D
        );
    }

    @Nullable
    private static TeleportCandidate findBestSpotNear(EntityTravellerWorker worker,
                                                      EntityLivingBase leader,
                                                      double centerX,
                                                      double centerY,
                                                      double centerZ,
                                                      int radius,
                                                      boolean requireUnderground,
                                                      double minLeaderDistance,
                                                      double maxLeaderDistance,
                                                      double maxWorkerDistance) {
        World world = worker.world;

        int xi = MathHelper.floor(centerX);
        int yi = MathHelper.clamp(MathHelper.floor(centerY), 2, world.getActualHeight() - 3);
        int zi = MathHelper.floor(centerZ);

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos pos = new BlockPos(xi + dx, yi + dy, zi + dz);
                    if (!isStandable(world, pos)) continue;
                    if (requireUnderground && world.canSeeSky(pos)) continue;

                    double px = pos.getX() + 0.5D;
                    double py = pos.getY();
                    double pz = pos.getZ() + 0.5D;

                    double dlx = px - leader.posX;
                    double dly = py - leader.posY;
                    double dlz = pz - leader.posZ;
                    double distToLeader = Math.sqrt(dlx * dlx + dly * dly + dlz * dlz);
                    if (distToLeader < minLeaderDistance || distToLeader > maxLeaderDistance) continue;

                    double dwx = px - worker.posX;
                    double dwy = py - worker.posY;
                    double dwz = pz - worker.posZ;
                    double distFromWorker = Math.sqrt(dwx * dwx + dwy * dwy + dwz * dwz);
                    if (distFromWorker > maxWorkerDistance) continue;

                    double score = 0.0D;
                    score += distFromWorker * 1.20D;
                    score += Math.abs(py - leader.posY) * 1.55D;
                    score += distToLeader * 0.45D;
                    if (world.canSeeSky(pos)) score += 18.0D;

                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }

        if (best == null) return null;
        return new TeleportCandidate(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D);
    }

    public static boolean isStandable(World world, BlockPos feet) {
        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.up()) || !world.isBlockLoaded(feet.down())) {
            return false;
        }

        IBlockState below = world.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        IBlockState atFeet = world.getBlockState(feet);
        IBlockState atHead = world.getBlockState(feet.up());

        boolean freeFeet = atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(world, feet) == null;
        boolean freeHead = atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(world, feet.up()) == null;
        if (!freeFeet || !freeHead) return false;

        BlockFaceShape shape = below.getBlockFaceShape(world, feet.down(), EnumFacing.UP);
        return shape == BlockFaceShape.SOLID;
    }

    /**
     * Last-resort teleport for a worker trapped in a pit: steps a small distance
     * toward the goal slot and finds the nearest standable surface.
     * No leader-distance filtering — the only goal is to escape the pit.
     */
    @Nullable
    public static TeleportCandidate findPitEscapeTeleport(
            EntityTravellerWorker worker,
            EntityLivingBase leader,
            double goalX, double goalY, double goalZ) {

        double dx = goalX - worker.posX;
        double dy = goalY - worker.posY;
        double dz = goalZ - worker.posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.5D) return null;

        double step = Math.min(5.0D, dist);
        double anchorX = worker.posX + (dx / dist) * step;
        double anchorY = worker.posY + (dy / dist) * step;
        double anchorZ = worker.posZ + (dz / dist) * step;

        // Generous max-worker-distance so we can escape a deep pit.
        return findBestSpotNear(
                worker,
                leader,
                anchorX,
                anchorY,
                anchorZ,
                4,
                false,
                0.0D,
                256.0D,
                step + 5.0D
        );
    }

    public static void teleportWorker(EntityTravellerWorker worker, TeleportCandidate candidate) {
        worker.getNavigator().clearPath();
        worker.setPositionAndUpdate(candidate.x, candidate.y, candidate.z);
        worker.motionX = 0.0D;
        worker.motionY = 0.0D;
        worker.motionZ = 0.0D;
        worker.velocityChanged = true;
    }

    public static final class TeleportCandidate {
        public final double x;
        public final double y;
        public final double z;

        public TeleportCandidate(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static boolean isFollowerCloseEnoughForNaturalCaveFollow(EntityTravellerWorker worker,
                                                                    EntityLivingBase leader,
                                                                    double maxLeaderDistance,
                                                                    double maxVerticalGap) {
        if (worker == null || leader == null) return false;

        double dist = worker.getDistance(leader);
        double dy = Math.abs(worker.posY - leader.posY);
        return dist <= maxLeaderDistance && dy <= maxVerticalGap;
    }

    public static boolean isSameLocalCavePocket(World world,
                                                EntityLivingBase a,
                                                EntityLivingBase b,
                                                int horizontalRadius,
                                                int verticalTolerance) {
        if (world == null || a == null || b == null) return false;

        BlockPos pa = new BlockPos(a.posX, a.getEntityBoundingBox().minY, a.posZ);
        BlockPos pb = new BlockPos(b.posX, b.getEntityBoundingBox().minY, b.posZ);

        if (!world.isBlockLoaded(pa) || !world.isBlockLoaded(pb)) return false;

        double dx = a.posX - b.posX;
        double dz = a.posZ - b.posZ;
        double horizSq = dx * dx + dz * dz;
        if (horizSq > horizontalRadius * horizontalRadius) return false;

        if (Math.abs(pa.getY() - pb.getY()) > verticalTolerance) return false;
        if (world.canSeeSky(pa) || world.canSeeSky(pb)) return false;

        int solidA = countSolidSides(world, pa);
        int solidB = countSolidSides(world, pb);

        return solidA >= 1 && solidB >= 1;
    }

    public static boolean shouldSuppressCaveRescue(EntityTravellerWorker worker,
                                                   EntityLivingBase leader,
                                                   double goalX,
                                                   double goalY,
                                                   double goalZ,
                                                   FollowRuntimeConfig cfg) {
        if (worker == null || leader == null || cfg == null) return false;

        if (isFollowerCloseEnoughForNaturalCaveFollow(
                worker,
                leader,
                cfg.rescue.caveNoTeleportLeaderDistance,
                cfg.rescue.caveNoTeleportVerticalGap)) {
            return true;
        }

        double dx = worker.posX - goalX;
        double dy = worker.posY - goalY;
        double dz = worker.posZ - goalZ;
        double goalDistSq = dx * dx + dy * dy + dz * dz;
        if (goalDistSq <= cfg.rescue.caveNoTeleportGoalDistance * cfg.rescue.caveNoTeleportGoalDistance) {
            return true;
        }

        return isSameLocalCavePocket(worker.world, worker, leader, 8, 5);
    }
    public static boolean shouldPreferCaveFollowRescue(EntityTravellerWorker worker,
                                                       EntityLivingBase target,
                                                       @Nullable FollowRuntimeConfig cfg) {
        if (worker == null || target == null) return false;
        if (worker.world == null || worker.world.isRemote) return false;
        if (!worker.isHiredFollower()) return false;
        if (!worker.isFollowOrderActive()) return false;
        if (worker.isJobActive()) return false;
        if (worker.isGoingHome()) return false;
        if (worker.isRiding()) return false;
        if (target.isDead || target.dimension != worker.dimension) return false;

        FollowRuntimeConfig use = cfg == null ? FollowRuntimeConfig.get() : cfg;
        return isLeaderMeaningfullyUnderground(worker.world, target, use)
                || isEnclosedFollowSpace(worker.world, target, use);
    }

    public static boolean isWorkerOutsideWhileLeaderUnderground(EntityTravellerWorker worker,
                                                                EntityLivingBase leader) {
        if (worker == null || leader == null) return false;
        return worker.world.canSeeSky(worker.getPosition().up()) && !worker.world.canSeeSky(new BlockPos(leader.posX, leader.posY, leader.posZ).up());
    }

    public static boolean tryCaveFollowRescue(EntityTravellerWorker worker,
                                              EntityLivingBase target,
                                              double goalX,
                                              double goalY,
                                              double goalZ,
                                              @Nullable FollowRuntimeConfig cfg) {
        if (!shouldPreferCaveFollowRescue(worker, target, cfg)) return false;

        FollowRuntimeConfig use = cfg == null ? FollowRuntimeConfig.get() : cfg;

        if (shouldSuppressCaveRescue(worker, target, goalX, goalY, goalZ, use)) {
            return false;
        }

        BlockPos targetFeet = new BlockPos(
                MathHelper.floor(target.posX),
                MathHelper.floor(target.getEntityBoundingBox().minY),
                MathHelper.floor(target.posZ)
        );

        Vec3d biasDir = getCaveRescueBiasDir(target);

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int r = 2; r <= CAVE_FOLLOW_RESCUE_SEARCH_RADIUS; r++) {
            for (int yOff = -CAVE_FOLLOW_RESCUE_VERTICAL; yOff <= CAVE_FOLLOW_RESCUE_VERTICAL; yOff++) {
                for (int a = 0; a < 12; a++) {
                    double ang = (Math.PI * 2.0D * a) / 12.0D;

                    double ox = Math.cos(ang) * r;
                    double oz = Math.sin(ang) * r;

                    BlockPos cand = new BlockPos(
                            MathHelper.floor(target.posX + ox),
                            targetFeet.getY() + yOff,
                            MathHelper.floor(target.posZ + oz)
                    );

                    cand = snapCaveFollowRescueFeet(worker, cand);
                    if (cand == null) continue;
                    if (!isValidCaveFollowRescueSpot(worker, cand, target)) continue;

                    double score = scoreCaveFollowRescueSpot(worker, cand, target, biasDir);
                    if (score > bestScore) {
                        bestScore = score;
                        best = cand;
                    }
                }
            }
        }

        if (best == null) {
            BlockPos direct = snapCaveFollowRescueFeet(worker, targetFeet);
            if (direct != null && isValidCaveFollowRescueSpot(worker, direct, target)) {
                best = direct;
            }
        }

        if (best == null) return false;

        teleportWorker(worker, new TeleportCandidate(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D));
        worker.fallDistance = 0.0F;
        return true;
    }

    private static Vec3d getCaveRescueBiasDir(EntityLivingBase target) {
        double mx = target.posX - target.prevPosX;
        double mz = target.posZ - target.prevPosZ;
        double m2 = mx * mx + mz * mz;

        if (m2 > 0.01D) {
            return new Vec3d(mx, 0.0D, mz).normalize();
        }

        float yaw = target.renderYawOffset;
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0.0D, Math.cos(rad));
    }

    @Nullable
    private static BlockPos snapCaveFollowRescueFeet(EntityTravellerWorker worker, BlockPos guess) {
        if (worker == null || guess == null) return null;

        BlockPos p = guess;

        for (int i = 0; i < 5; i++) {
            if (isWorkerRescueFeetSpot(worker, p)) return p;
            p = p.up();
        }

        p = guess.down();
        for (int i = 0; i < 8 && p.getY() > 1; i++) {
            if (isWorkerRescueFeetSpot(worker, p)) return p;
            p = p.down();
        }

        return null;
    }

    private static boolean isWorkerRescueFeetSpot(EntityTravellerWorker worker, BlockPos feet) {
        World world = worker.world;
        if (feet == null) return false;
        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.up()) || !world.isBlockLoaded(feet.down())) return false;

        IBlockState below = world.getBlockState(feet.down());
        IBlockState atFeet = world.getBlockState(feet);
        IBlockState atHead = world.getBlockState(feet.up());

        if (!below.getMaterial().isSolid()) return false;
        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;

        boolean freeFeet = atFeet.getMaterial().isReplaceable()
                || atFeet.getCollisionBoundingBox(world, feet) == null
                || atFeet.getBlock() == Blocks.SNOW_LAYER;
        boolean freeHead = atHead.getMaterial().isReplaceable()
                || atHead.getCollisionBoundingBox(world, feet.up()) == null
                || atHead.getBlock() == Blocks.SNOW_LAYER;

        if (!freeFeet || !freeHead) return false;

        AxisAlignedBB bb = worker.getEntityBoundingBox().offset(
                (feet.getX() + 0.5D) - worker.posX,
                feet.getY() - worker.posY,
                (feet.getZ() + 0.5D) - worker.posZ
        );

        return world.getCollisionBoxes(worker, bb).isEmpty();
    }

    private static boolean isValidCaveFollowRescueSpot(EntityTravellerWorker worker,
                                                       BlockPos feet,
                                                       EntityLivingBase target) {
        if (!isWorkerRescueFeetSpot(worker, feet)) return false;
        if (worker.world.canSeeSky(feet.up())) return false;
        // Removed sky light threshold: the canSeeSky check is sufficient.
        // Buildings near the surface can have high sky light even with solid roofs.

        if (Math.abs(feet.getY() - MathHelper.floor(target.posY)) > CAVE_FOLLOW_RESCUE_VERTICAL + 2) {
            return false;
        }

        AxisAlignedBB crowdBox = new AxisAlignedBB(feet).grow(1.2D, 1.0D, 1.2D);
        List<EntityTravellerWorker> others = worker.world.getEntitiesWithinAABB(
                EntityTravellerWorker.class,
                crowdBox,
                w -> w != null
                        && w != worker
                        && w.isEntityAlive()
                        && Objects.equals(w.getFollowOwnerId(), worker.getFollowOwnerId())
        );

        double cx = feet.getX() + 0.5D;
        double cz = feet.getZ() + 0.5D;

        for (EntityTravellerWorker other : others) {
            double dx = other.posX - cx;
            double dz = other.posZ - cz;
            if (dx * dx + dz * dz < CAVE_FOLLOW_RESCUE_OVERCROWD_RADIUS_SQ) {
                return false;
            }
        }

        double tx = target.posX - cx;
        double tz = target.posZ - cz;
        double distSq = tx * tx + tz * tz;

        return distSq >= 2.0D * 2.0D && distSq <= CAVE_FOLLOW_RESCUE_MAX_XZ_SQ;
    }

    private static double scoreCaveFollowRescueSpot(EntityTravellerWorker worker,
                                                    BlockPos feet,
                                                    EntityLivingBase target,
                                                    Vec3d biasDir) {
        double cx = feet.getX() + 0.5D;
        double cz = feet.getZ() + 0.5D;

        double toSpotX = cx - target.posX;
        double toSpotZ = cz - target.posZ;
        double distSq = toSpotX * toSpotX + toSpotZ * toSpotZ;

        Vec3d toSpot = new Vec3d(toSpotX, 0.0D, toSpotZ);
        double alignment = 0.0D;
        if (toSpot.lengthSquared() > 1.0E-6D) {
            alignment = toSpot.normalize().dotProduct(biasDir);
        }

        double yPenalty = Math.abs(feet.getY() - target.posY) * 2.5D;

        int wallCount = 0;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            if (worker.world.getBlockState(feet.offset(f)).getMaterial().isSolid()) {
                wallCount++;
            }
        }

        double selfDx = worker.posX - cx;
        double selfDz = worker.posZ - cz;
        double selfMovePenalty = (selfDx * selfDx + selfDz * selfDz) * 0.35D;

        return (alignment * 8.0D) - distSq - yPenalty - (wallCount * 0.75D) - selfMovePenalty;
    }
}