package com.jubitus.travellers.follow;


import com.jubitus.travellers.unit.LandingSpots;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.unit.Relocate;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.Footing;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillFootprints;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.*;
import net.minecraft.world.World;

import javax.annotation.Nullable;


import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.block.state.BlockFaceShape;
import com.jubitus.travellers.virtual.MaterializePositionResolver;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
        if (sky > use.cave.maxSkyLight) return false;

        // Reject tree canopies: only foliage-type blocks overhead → not a real roof
        if (hasOnlyFoliageCover(world, pos)) return false;
        return true;
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
        int sky = world.getLightFor(EnumSkyBlock.SKY, pos.up());
        if (sky > use.cave.maxSkyLight) return false;
        if (hasOnlyFoliageCover(world, pos)) return false;
        return getCoverBlocks(world, pos) >= 1;
    }

    public static int getCoverBlocks(World world, BlockPos pos) {
        if (world == null || pos == null) return 0;
        return ColumnGround.coverAbove(world, pos);
    }

    /**
     * Returns true if the only non-replaceable blocks between pos and open sky are foliage-like:
     * leaves, vines, or any low-opacity block (DynamicTrees branches, etc.).
     * Used to avoid triggering cave mode under tree canopies.
     */
    private static boolean hasOnlyFoliageCover(World world, BlockPos pos) {
        for (int dy = 1; dy <= 20; dy++) {
            BlockPos above = pos.add(0, dy, 0);
            if (!world.isBlockLoaded(above)) return false;
            IBlockState state = world.getBlockState(above);
            Material mat = state.getMaterial();
            if (mat.isReplaceable()) continue;
            if (BlockRules.isTreePart(state) || BlockRules.isVegetation(state)) continue;
            // DynamicTrees branches and similar partial blocks have low opacity — treat as foliage
            if (state.getBlock().getLightOpacity(state) < 10) continue;
            return false;
        }
        return true;
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

    public static void teleportWorker(EntityTraveller worker, TeleportCandidate candidate) {
        Relocate.teleport(worker, candidate.x, candidate.y, candidate.z);
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

    public static boolean isFollowerCloseEnoughForNaturalCaveFollow(EntityTraveller worker,
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

    public static boolean shouldSuppressCaveRescue(EntityTraveller worker,
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
    public static boolean shouldPreferCaveFollowRescue(EntityTraveller worker,
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

    public static boolean isWorkerOutsideWhileLeaderUnderground(EntityTraveller worker,
                                                                EntityLivingBase leader) {
        if (worker == null || leader == null) return false;
        return worker.world.canSeeSky(worker.getPosition().up()) && !worker.world.canSeeSky(new BlockPos(leader.posX, leader.posY, leader.posZ).up());
    }

    public static boolean tryCaveFollowRescue(EntityTraveller worker,
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
        boolean targetInBuilding = MillFootprints.at(worker.world, target.posX, target.posY, target.posZ, 0) != null;

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
                    if (!targetInBuilding && MillFootprints.isForbiddenLanding(worker.world,
                            cand.getX() + 0.5D, cand.getY(), cand.getZ() + 0.5D)) continue;

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
            if (direct != null && isValidCaveFollowRescueSpot(worker, direct, target)
                    && (targetInBuilding || !MillFootprints.isForbiddenLanding(worker.world,
                    direct.getX() + 0.5D, direct.getY(), direct.getZ() + 0.5D))) {
                best = direct;
            }
        }

        if (best == null) return false;

        teleportWorker(worker, new TeleportCandidate(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D));
        worker.fallDistance = 0.0F;
        return true;
    }

    private static Vec3d getCaveRescueBiasDir(EntityLivingBase target) {
        double mx = com.jubitus.travellers.unit.EntityMotion.velX(target);
        double mz = com.jubitus.travellers.unit.EntityMotion.velZ(target);
        double m2 = mx * mx + mz * mz;

        if (m2 > 0.01D) {
            return new Vec3d(mx, 0.0D, mz).normalize();
        }

        float yaw = target.renderYawOffset;
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0.0D, Math.cos(rad));
    }

    @Nullable
    private static BlockPos snapCaveFollowRescueFeet(EntityTraveller worker, BlockPos guess) {
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

    // Footprints are checked by the caller (a leader inside a building may be joined there).
    private static boolean isWorkerRescueFeetSpot(EntityTraveller worker, BlockPos feet) {
        return feet != null && LandingSpots.isSafe(worker.world, feet, worker, false, true);
    }

    private static boolean isValidCaveFollowRescueSpot(EntityTraveller worker,
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

    private static double scoreCaveFollowRescueSpot(EntityTraveller worker,
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