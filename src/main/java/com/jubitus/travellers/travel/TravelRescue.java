package com.jubitus.travellers.travel;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.unit.LandingSpots;
import com.jubitus.travellers.unit.Relocate;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.flight.ElytraCapability;
import com.jubitus.travellers.flight.ElytraOpportunityScanner;
import com.jubitus.travellers.follow.FollowRuntimeConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.MountedLeadController;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.village.MillFootprints;
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
                double feet = LandingSpots.feetY(mob.world, spot);
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
        if (!LoadedChunks.isAreaLoaded(mob.world, guess, 2)) { reject(why, tag, "unloaded"); return null; }

        BlockPos surf = terrain.findSurfaceOrWaterY(guess);
        if (surf == null) { reject(why, tag, "noSurface"); return null; }

        String unsafe = LandingSpots.whyUnsafe(mob.world, surf, mob, true, false);
        if (unsafe != null) { reject(why, tag, unsafe); return null; }
        return surf;
    }

    private void landOn(BlockPos spot) {
        if (ai.isInBoat()) ai.dismountAndDeleteBoat();
        Relocate.teleport(mob, spot.getX() + 0.5, LandingSpots.feetY(mob.world, spot), spot.getZ() + 0.5);
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
        int tooLow = 0;
        java.util.Map<String, Integer> refused = new java.util.TreeMap<>();

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {

                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;

                BlockPos probe = new BlockPos(x, origin.getY(), z);
                if (!LoadedChunks.isAreaLoaded(mob.world, probe, 1)) continue;

                // The column's surface (forest floor under a canopy); the rise is measured on it.
                int groundY = ColumnGround.topLayer(w, x, z, false);
                if (groundY == ColumnGround.NONE) { refused.merge("noGround", 1, Integer::sum); continue; }
                BlockPos feet = new BlockPos(x, groundY, z);
                if (feet.getY() < minY) { tooLow++; continue; }
                String why = LandingSpots.whyUnsafe(w, feet, mob, false, false);
                if (why != null) { refused.merge(why.startsWith("blocked") ? "blocked" : why, 1, Integer::sum); continue; }


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
                    + " minRise=" + minRise + " refused: tooLow=" + tooLow + " " + refused);
            return false;
        }

        if (ai.isInBoat()) ai.dismountAndDeleteBoat();

        Relocate.teleport(mob, best.getX() + 0.5, LandingSpots.feetY(w, best), best.getZ() + 0.5);
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
                if (!LoadedChunks.isAreaLoaded(mob.world, probe, 1)) continue;

                // We want DRY land only
                int groundY = ColumnGround.topLayer(mob.world, probe.getX(), probe.getZ(), false);
                if (groundY == ColumnGround.NONE) continue;
                BlockPos feet = new BlockPos(probe.getX(), groundY, probe.getZ());
                if (!LandingSpots.isSafe(mob.world, feet, mob, false, false)) continue;

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

                if (score > bestScore) {
                    bestScore = score;
                    best = feet;
                }
            }
        }

        if (best == null) return false;

        if (ai.isInBoat()) ai.dismountAndDeleteBoat();

        Relocate.teleport(mob, best.getX() + 0.5, LandingSpots.feetY(mob.world, best), best.getZ() + 0.5);
        ai.clearNav();
        return true;
    }
}
