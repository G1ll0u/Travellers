package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.flight.ElytraCapability;
import com.jubitus.travellers.entity.entityai.flight.ElytraOpportunityScanner;
import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import com.jubitus.travellers.entity.entityai.travel.mount.MountMode;
import com.jubitus.travellers.entity.entityai.travel.mount.MountedLeadController;
import com.jubitus.travellers.entity.entityai.travel.mount.TravellerMountSupport;
import com.jubitus.travellers.world.pathing.TravellerNavigateGround;
import com.jubitus.travellers.world.mill.MillFootprints;
import com.jubitus.travellers.world.mill.MillVillageIndex;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelPathScheduler;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.pathing.route.TravelPlan;
import com.jubitus.travellers.world.pathing.itinerary.Itinerary;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryState;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.millenaire.common.block.IBlockPath;
import org.millenaire.common.utilities.MillLog;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The travel AI's short rescue hops: onto the shore, up out of a hole, or a few blocks along the
 * way. Every landing is checked clear for the whole body (a bonded horse included) before moving.
 */
final class TravelRescue {

    private final EntityAITravel ai;
    private final EntityTraveller mob;
    private final TravelTerrain terrain;

    TravelRescue(EntityAITravel ai, EntityTraveller mob, TravelTerrain terrain) {
        this.ai = ai;
        this.mob = mob;
        this.terrain = terrain;
    }

    /**
     * The volume a teleport candidate must leave clear. On foot that is this traveller's own box
     * moved to the landing spot; mounted it is the whole horse-plus-rider stack, which is both wider
     * and rooted a body lower - see {@link TravellerMountSupport#mountedLandingBox}. Every rescue hop
     * here goes through this one method so a mount can never be dropped into a gap only its rider
     * would have fitted in.
     */
    AxisAlignedBB landingBox(double cx, double groundY, double cz) {
        AxisAlignedBB mounted = TravellerMountSupport.mountedLandingBox(mob, cx, groundY, cz);
        if (mounted != null) return mounted;
        return mob.getEntityBoundingBox().offset(cx - mob.posX, groundY - mob.posY, cz - mob.posZ);
    }

    boolean teleportToward(BlockPos target, int stepBlocks) {
        if (mob.world.isRemote) return false;
        if (target == null) return false;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return false;

        if (ai.isInBoat()) ai.dismountAndDeleteBoat();

        final BlockPos start = mob.getPosition();
        final Vec3d fwd = TravelTerrain.goalDirXZ(mob, target);
        final double curD2 = TravelTerrain.horizDistSq(start, target);

        final double MIN_FORWARD = 1.5; // blocks
        final double MAX_BACKSLIDE = 4.0;
        final double MAX_BACKSLIDE_SQ = MAX_BACKSLIDE * MAX_BACKSLIDE;
        final int LOAD_RADIUS = 2;

        Vec3d to = new Vec3d(target.getX() - start.getX(), 0, target.getZ() - start.getZ());
        if (to.lengthSquared() < 1.0) return false;

        Vec3d dir = to.normalize();
        Vec3d right = new Vec3d(-dir.z, 0, dir.x);

        int lateralBase = Math.max(4, stepBlocks / 2);
        int[] lateral = new int[]{0, +lateralBase, -lateralBase, +lateralBase * 2, -lateralBase * 2};

        BlockPos best = null;
        double bestScore = -1e18;

        for (int lat : lateral) {
            int nx = start.getX() + (int) Math.round(dir.x * stepBlocks + right.x * lat);
            int nz = start.getZ() + (int) Math.round(dir.z * stepBlocks + right.z * lat);

            BlockPos guess = new BlockPos(nx, start.getY(), nz);
            if (!terrain.isAreaLoaded(guess, LOAD_RADIUS)) continue;

            BlockPos surf = terrain.findSurfaceOrWaterY(guess);
            if (surf == null) continue;

            // ✅ If candidate is water, snap it to the TOP of that water column
            if (terrain.isSwimmable(surf)) {
                BlockPos top = terrain.snapToWaterSurface(surf);
                if (top == null) continue; // no safe headroom / not loaded
                surf = top;
            }


            boolean swimmable = terrain.isSwimmable(surf);

// Only require "solid support" for LAND targets.
// If it's water, allow it (we will "land" as swimming).
            if (!swimmable) {
                boolean okSupport = terrain.isNaturalGroundSupport(surf.down());
                if (!okSupport) {
                    IBlockState sup = mob.world.getBlockState(surf.down());
                    okSupport = com.jubitus.travellers.world.ground.Footing.isFloor(mob.world, surf.down(), sup);
                }
                if (!okSupport) continue;

                // Only do tree-top correction for land
                surf = terrain.adjustTreeTopToGround(surf);
            }

// Must still be navigable (land OR water)
            if (!terrain.isNavigable(surf)) continue;


            double landY = terrain.candidateLandingY(surf);
            if (MillFootprints.isForbiddenLanding(mob.world, surf.getX() + 0.5D, landY, surf.getZ() + 0.5D)) continue;

            AxisAlignedBB aabb = landingBox(surf.getX() + 0.5D, landY, surf.getZ() + 0.5D);

            if (!terrain.isAabbAreaLoaded(aabb)) continue;
            if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) continue;
            if (TravelTerrain.intersectsForbiddenLiquid(mob.world, aabb)) continue;


            // forward + backslide gating
            double sx = (surf.getX() + 0.5) - (start.getX() + 0.5);
            double sz = (surf.getZ() + 0.5) - (start.getZ() + 0.5);
            Vec3d step = new Vec3d(sx, 0, sz);

            double forward = step.dotProduct(fwd);
            if (forward < MIN_FORWARD) continue;

            double candD2 = TravelTerrain.horizDistSq(surf, target);
            if (candD2 > curD2 + MAX_BACKSLIDE_SQ) continue;

            double score = (forward * 10.0) - (candD2 * 0.001) - (lat * lat * 0.01);
            if (score > bestScore) {
                bestScore = score;
                best = surf;
            }
        }

        if (best == null) return false;

        double y = terrain.isSwimmable(best) ? (best.getY() + 0.02D) : terrain.teleportLandingY(best);
        mob.setPositionAndUpdate(best.getX() + 0.5, y, best.getZ() + 0.5);
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        ai.clearNav();
        return true;
    }

    boolean teleportToSurfaceRescue(int radius, int minRise) {
        if (mob.world.isRemote) return false;

        World w = mob.world;
        BlockPos origin = mob.getPosition();
        int minY = origin.getY() + minRise;

        // If no target, keep old “closest wins”
        final boolean hasTarget = (ai.targetVillage != null);

        Vec3d fwd = hasTarget ? TravelTerrain.goalDirXZ(mob, ai.targetVillage) : new Vec3d(0, 0, 1);
        double curD2 = hasTarget ? TravelTerrain.horizDistSq(origin, ai.targetVillage) : 0.0;

        // Two buckets:
        //  - bestForward: must be forward-ish toward target
        //  - bestAny: any valid surface (fallback if no forward exists)
        BlockPos bestForward = null;
        double bestForwardScore = -1e18;

        BlockPos bestAny = null;
        double bestAnyScore = Double.MAX_VALUE; // fallback uses “closest” like before

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {

                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;

                BlockPos probe = new BlockPos(x, origin.getY(), z);
                if (!terrain.isAreaLoaded(probe, 1)) continue;

                BlockPos top = w.getTopSolidOrLiquidBlock(probe);

                // The rise must be measured on the spot actually landed on, not on the raw column
                // top: getTopSolidOrLiquidBlock returns the crown of a tree, and adjustTreeTopToGround
                // then walks back down to the forest floor. Tested before that adjustment, every
                // wooded tile passes a "6 blocks higher" filter it does not remotely satisfy, which
                // turned this from "dig me out, I'm buried" into an arbitrary hop under any canopy.
                BlockPos feet = terrain.adjustTreeTopToGround(top);
                if (feet.getY() < minY) continue;
                if (!terrain.isStandable(feet)) continue;
                boolean okSupport = terrain.isNaturalGroundSupport(feet.down());
                if (!okSupport) {
                    // stage 3: allow man-made solids too (still reject leaves/liquids via terrain.isStandable())
                    IBlockState sup = mob.world.getBlockState(feet.down());
                    okSupport = com.jubitus.travellers.world.ground.Footing.isFloor(mob.world, feet.down(), sup);
                }
                if (!okSupport) continue;

                double landY = terrain.candidateLandingY(feet);
                if (MillFootprints.isForbiddenLanding(w, feet.getX() + 0.5D, landY, feet.getZ() + 0.5D)) continue;

                AxisAlignedBB aabb = landingBox(feet.getX() + 0.5D, landY, feet.getZ() + 0.5D);
                if (!terrain.isAabbAreaLoaded(aabb)) continue;

                if (!w.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                if (TravelTerrain.intersectsForbiddenLiquid(w, aabb)) continue;


                // ---- Fallback (old behaviour): closest to origin ----
                double closestScore = feet.distanceSq(origin);
                if (closestScore < bestAnyScore) {
                    bestAnyScore = closestScore;
                    bestAny = feet;
                }

                if (!hasTarget) continue;

                // ---- PATCH C: prefer forward + closer-to-target ----
                double sx = (feet.getX() + 0.5) - (origin.getX() + 0.5);
                double sz = (feet.getZ() + 0.5) - (origin.getZ() + 0.5);
                double forward = new Vec3d(sx, 0, sz).dotProduct(fwd);

                double candD2 = TravelTerrain.horizDistSq(feet, ai.targetVillage);

                // Hard reject: “significantly behind AND not closer”
                if (forward < -1.0 && candD2 >= curD2) continue;

                // Score: strongly prefer closer-to-target, then forward, then not too far from origin
                double score = -(candD2 * 0.01) + (forward * 5.0) - (closestScore * 0.001);

                if (score > bestForwardScore) {
                    bestForwardScore = score;
                    bestForward = feet;
                }
            }
        }

        BlockPos best = (bestForward != null) ? bestForward : bestAny;
        if (best == null) return false;

        if (ai.isInBoat()) ai.dismountAndDeleteBoat();

        double y = terrain.teleportLandingY(best);
        mob.setPositionAndUpdate(best.getX() + 0.5, y, best.getZ() + 0.5);

        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        ai.clearNav();
        return true;
    }

    boolean teleportToSurfaceRescue(int radius) {
        return teleportToSurfaceRescue(radius, 6);
    }

    // Finds a dry standable tile near us, biased forward toward target, and teleports there.
// radius = XZ scan radius, minForward = require at least this many blocks forward (soft).
    boolean teleportToNearestShoreTowardTarget(int radius, int minForward) {
        if (mob.world.isRemote) return false;
        if (ai.targetVillage == null) return false;

        BlockPos origin = mob.getPosition();
        Vec3d fwd = TravelTerrain.goalDirXZ(mob, ai.targetVillage);

        BlockPos best = null;
        double bestScore = -1e18;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {

                BlockPos probe = origin.add(dx, 0, dz);
                if (!terrain.isAreaLoaded(probe, 1)) continue;

                BlockPos top = mob.world.getTopSolidOrLiquidBlock(probe);
                BlockPos feet = terrain.adjustTreeTopToGround(top);

                // We want DRY land only
                if (!terrain.isStandable(feet)) continue;
                if (MillFootprints.isForbiddenLanding(mob.world, feet.getX() + 0.5D,
                        terrain.teleportLandingY(feet), feet.getZ() + 0.5D)) continue;

                double sx = (feet.getX() + 0.5) - (origin.getX() + 0.5);
                double sz = (feet.getZ() + 0.5) - (origin.getZ() + 0.5);
                Vec3d step = new Vec3d(sx, 0, sz);

                double forward = step.dotProduct(fwd);
                if (forward < -2.0) continue; // don't go backwards unless desperate

                // Optional soft forward preference
                double forwardBonus = Math.max(0.0, forward - minForward);

                double d2ToTarget = TravelTerrain.horizDistSq(feet, ai.targetVillage);
                double dist2FromMe = sx * sx + sz * sz;

                // Score: prefer closer to target + forward + not too far
                double score = -(d2ToTarget * 0.01) + (forwardBonus * 5.0) - (dist2FromMe * 0.001);

                // Collision check at REAL landing Y (snow-safe)
                double landY = terrain.teleportLandingY(feet);
                AxisAlignedBB aabb = landingBox(feet.getX() + 0.5D, landY, feet.getZ() + 0.5D);
                if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) continue;
                if (TravelTerrain.intersectsForbiddenLiquid(mob.world, aabb)) continue;

                if (score > bestScore) {
                    bestScore = score;
                    best = feet;
                }
            }
        }

        if (best == null) return false;

        if (ai.isInBoat()) ai.dismountAndDeleteBoat();

        double y = terrain.teleportLandingY(best);
        mob.setPositionAndUpdate(best.getX() + 0.5, y, best.getZ() + 0.5);
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        ai.clearNav();
        return true;
    }
}
