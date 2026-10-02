package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.flight.ElytraCapability;
import com.jubitus.travellers.entity.entityai.flight.ElytraOpportunityScanner;
import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.MountedLeadController;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.world.mill.MillFootprints;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelPathScheduler;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.pathing.route.TravelPlan;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;
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
        Vec3d to = new Vec3d(target.getX() - start.getX(), 0, target.getZ() - start.getZ());
        if (to.lengthSquared() < 1.0) return false;

        Vec3d dir = to.normalize();
        Vec3d right = new Vec3d(-dir.z, 0, dir.x);

        int lateralBase = Math.max(4, stepBlocks / 2);
        int[] lateral = new int[]{0, +lateralBase, -lateralBase, +lateralBase * 2, -lateralBase * 2};

        BlockPos best = null;
        double bestScore = -1e18;
        StringBuilder why = new StringBuilder();

        for (int lat : lateral) {
            int nx = start.getX() + (int) Math.round(dir.x * stepBlocks + right.x * lat);
            int nz = start.getZ() + (int) Math.round(dir.z * stepBlocks + right.z * lat);

            BlockPos surf = landingAt(nx, start.getY(), nz, why, String.valueOf(lat));
            if (surf == null) continue;

            // forward + backslide gating
            double sx = (surf.getX() + 0.5) - (start.getX() + 0.5);
            double sz = (surf.getZ() + 0.5) - (start.getZ() + 0.5);
            Vec3d step = new Vec3d(sx, 0, sz);

            double forward = step.dotProduct(fwd);
            if (forward < MIN_FORWARD) { reject(why, String.valueOf(lat), "notForward"); continue; }

            double candD2 = TravelTerrain.horizDistSq(surf, target);
            if (candD2 > curD2 + MAX_BACKSLIDE_SQ) { reject(why, String.valueOf(lat), "backslide"); continue; }

            double score = (forward * 10.0) - (candD2 * 0.001) - (lat * lat * 0.01);
            if (score > bestScore) {
                bestScore = score;
                best = surf;
            }
        }

        if (best == null) {
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop toward step=" + stepBlocks + " refused:" + why);
            return false;
        }

        landOn(best);
        return true;
    }

    enum HopResult { MOVED, DEFERRED, FAILED }

    // A* checks per hop; the world-wide per-tick budget is TRAVEL.stuckHopChecksPerTick.
    private static final int MAX_CHECKS_PER_HOP = 2;
    private static final int[] LATERAL = {0, 2, -2};
    private static long checkBudgetTick = Long.MIN_VALUE;
    private static int checkBudgetUsed;

    /**
     * Hops to a point along the route the unit can resume from. {@code route} holds pairs
     * (landing column, onward point further along the same route), nearest first. A landing is
     * accepted once one A* proves the unit can walk on from it to its onward point; with no proof
     * within budget the farthest clear landing is used, so the next hop starts further along.
     */
    HopResult teleportToResumePoint(List<BlockPos[]> route) {
        if (mob.world.isRemote || route.isEmpty()) return HopResult.FAILED;
        if (mob.isInCombat() || mob.isAutoEating() || mob.isHandActive()) return HopResult.FAILED;
        TravellerNavigateGround nav = mob.getNavigator() instanceof TravellerNavigateGround
                ? (TravellerNavigateGround) mob.getNavigator() : null;

        StringBuilder why = new StringBuilder();
        BlockPos farthest = null;
        int checks = 0;
        int refY = mob.getPosition().getY();
        for (int i = 0; i < route.size(); i++) {
            BlockPos col = route.get(i)[0];
            BlockPos onward = route.get(i)[1];
            double ox = onward.getX() - col.getX(), oz = onward.getZ() - col.getZ();
            double len = Math.sqrt(ox * ox + oz * oz);
            double px = len < 1e-3 ? 0.0 : -oz / len, pz = len < 1e-3 ? 0.0 : ox / len;
            for (int lat : LATERAL) {
                if (lat != 0 && len < 1e-3) continue;
                BlockPos spot = landingAt(col.getX() + (int) Math.round(px * lat), refY,
                        col.getZ() + (int) Math.round(pz * lat), why, i + "/" + lat);
                if (spot == null) continue;
                farthest = spot;
                if (nav == null || checks >= MAX_CHECKS_PER_HOP) break;
                if (!acquireCheck()) {
                    if (checks == 0) return HopResult.DEFERRED;
                    break;
                }
                checks++;
                BlockPos onGround = terrain.findSurfaceOrWaterY(onward);
                double feet = terrain.isSwimmable(spot) ? spot.getY() + 0.02D : terrain.teleportLandingY(spot);
                if (nav.isReachableFrom(spot.getX() + 0.5D, feet, spot.getZ() + 0.5D, onGround != null ? onGround : onward)) {
                    com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop resume verified at " + spot.getX() + "," + spot.getY() + "," + spot.getZ() + " (" + i + "/" + lat + ")");
                    landOn(spot);
                    return HopResult.MOVED;
                }
                reject(why, i + "/" + lat, "noOnwardRoute");
                break;
            }
        }
        if (farthest == null) {
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop resume refused:" + why);
            return HopResult.FAILED;
        }
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop resume unverified at " + farthest.getX() + "," + farthest.getY() + "," + farthest.getZ() + " after" + why);
        landOn(farthest);
        return HopResult.MOVED;
    }

    private boolean acquireCheck() {
        long t = mob.world.getTotalWorldTime();
        if (t != checkBudgetTick) {
            checkBudgetTick = t;
            checkBudgetUsed = 0;
        }
        if (checkBudgetUsed >= TravellersConfig.TRAVEL.stuckHopChecksPerTick) return false;
        checkBudgetUsed++;
        return true;
    }

    private static void reject(StringBuilder why, String tag, String reason) {
        why.append(' ').append(tag).append('=').append(reason);
    }

    /**
     * The spot a hop would land on in column (x, z): its surface (or water top), checked clear for the
     * whole body. Null, with the reason appended to {@code why}, when nothing safe is there.
     */
    @Nullable
    private BlockPos landingAt(int x, int refY, int z, StringBuilder why, String tag) {
        BlockPos guess = new BlockPos(x, refY, z);
        if (!terrain.isAreaLoaded(guess, 2)) { reject(why, tag, "unloaded"); return null; }

        BlockPos surf = terrain.findSurfaceOrWaterY(guess);
        if (surf == null) { reject(why, tag, "noSurface"); return null; }

        if (terrain.isSwimmable(surf)) {
            BlockPos top = terrain.snapToWaterSurface(surf);
            if (top == null) { reject(why, tag, "waterNoHeadroom"); return null; }
            surf = top;
        }
        if (!terrain.isSwimmable(surf)) {
            boolean okSupport = terrain.isNaturalGroundSupport(surf.down());
            if (!okSupport) {
                IBlockState sup = mob.world.getBlockState(surf.down());
                okSupport = com.jubitus.travellers.ground.Footing.isFloor(mob.world, surf.down(), sup);
            }
            if (!okSupport) { reject(why, tag, "noSupport"); return null; }
            surf = terrain.adjustTreeTopToGround(surf);
        }
        if (!terrain.isNavigable(surf)) { reject(why, tag, "notNavigable"); return null; }

        double landY = terrain.candidateLandingY(surf);
        if (MillFootprints.isForbiddenLanding(mob.world, surf.getX() + 0.5D, landY, surf.getZ() + 0.5D)) { reject(why, tag, "building"); return null; }

        AxisAlignedBB aabb = landingBox(surf.getX() + 0.5D, landY, surf.getZ() + 0.5D);
        if (!terrain.isAabbAreaLoaded(aabb)) { reject(why, tag, "boxUnloaded"); return null; }
        if (!mob.world.getCollisionBoxes(mob, aabb).isEmpty()) { reject(why, tag, "blocked@" + surf.getY()); return null; }
        if (TravelTerrain.intersectsForbiddenLiquid(mob.world, aabb)) { reject(why, tag, "hazard"); return null; }
        return surf;
    }

    private void landOn(BlockPos spot) {
        if (ai.isInBoat()) ai.dismountAndDeleteBoat();
        double y = terrain.isSwimmable(spot) ? (spot.getY() + 0.02D) : terrain.teleportLandingY(spot);
        mob.setPositionAndUpdate(spot.getX() + 0.5, y, spot.getZ() + 0.5);
        mob.motionX = mob.motionY = mob.motionZ = 0.0D;
        mob.velocityChanged = true;
        ai.clearNav();
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
        int tooLow = 0, notStandable = 0, noSupport = 0, building = 0, blocked = 0, hazard = 0;

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
                if (feet.getY() < minY) { tooLow++; continue; }
                if (!terrain.isStandable(feet)) { notStandable++; continue; }
                boolean okSupport = terrain.isNaturalGroundSupport(feet.down());
                if (!okSupport) {
                    // stage 3: allow man-made solids too (still reject leaves/liquids via terrain.isStandable())
                    IBlockState sup = mob.world.getBlockState(feet.down());
                    okSupport = com.jubitus.travellers.ground.Footing.isFloor(mob.world, feet.down(), sup);
                }
                if (!okSupport) { noSupport++; continue; }

                double landY = terrain.candidateLandingY(feet);
                if (MillFootprints.isForbiddenLanding(w, feet.getX() + 0.5D, landY, feet.getZ() + 0.5D)) { building++; continue; }

                AxisAlignedBB aabb = landingBox(feet.getX() + 0.5D, landY, feet.getZ() + 0.5D);
                if (!terrain.isAabbAreaLoaded(aabb)) continue;

                if (!w.getCollisionBoxes(mob, aabb).isEmpty()) { blocked++; continue; }
                if (TravelTerrain.intersectsForbiddenLiquid(w, aabb)) { hazard++; continue; }


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
        if (best == null) {
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop surface radius=" + radius
                    + " minRise=" + minRise + " refused: tooLow=" + tooLow + " notStandable=" + notStandable
                    + " noSupport=" + noSupport + " building=" + building + " blocked=" + blocked + " hazard=" + hazard);
            return false;
        }

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
