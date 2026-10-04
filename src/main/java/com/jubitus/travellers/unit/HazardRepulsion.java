package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.pathing.GapJumpEvaluator;
import com.jubitus.travellers.pathing.GapJumpResult;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/** The background nudge that keeps every unit off lava, fire, deep drops and (weakly) water edges. */
final class HazardRepulsion {

    private HazardRepulsion() {
    }

    /** Every entity re-checks its own hazard scan on a different tick, spreading the cost. */
    private static final int HAZARD_TICK_BUCKETS = 4;

    /**
     * Continuous, cheap safety nudge away from lava/fire/configured hazard blocks and away from
     * deep drops/void, applied to every traveller/raider/worker every tick regardless of which AI
     * task (if any) currently has movement control - idle, travelling, following, camping,
     * fighting. A crowded group jostles its own members via ordinary entity collision every tick,
     * which alone is enough to shove one sideways over an edge before any reactive AI even gets a
     * chance to run; this is a background bias against exactly that, not a hard wall - it's a
     * small motionX/Z nudge, easily overridden by real movement (a deliberate gap-jump, a
     * deliberate lava/water crossing, a player pushing hard and sustained).
     */
    static void tick(EntityTraveller u) {
        if (u.world.isRemote || u.isDead) return;
        if (!TravellersConfig.HAZARD_SAFETY.enabled) return;
        if (u.isRiding() || u.sleep.isSleeping() || u.isCampingPassive()) return;
        // A glide is a deliberate flight over exactly the drops this nudge exists to avoid.
        if (u.elytraFlight.isActive()) return;

        PathNavigate nav = u.getNavigator();
        if (nav instanceof TravellerNavigateGround && ((TravellerNavigateGround) nav).isGapJumping()) return;

        // Staggered like the follow AI's own heavy-update cadence - the scan itself is cheap per
        // entity, but this now runs for every traveller/raider/worker in the world, not just
        // hired formation followers, so spreading the cost across ticks matters at scale.
        if (Math.floorMod(u.ticksExisted, HAZARD_TICK_BUCKETS) != Math.floorMod(u.getEntityId(), HAZARD_TICK_BUCKETS)) {
            return;
        }

        TravellersConfig.HazardSafety cfg = TravellersConfig.HAZARD_SAFETY;
        double radius = cfg.scanRadius;
        BlockPos feet = new BlockPos(u.posX, u.getEntityBoundingBox().minY, u.posZ);
        int r = MathHelper.ceil(radius);

        // If a valid jump is available in the direction the entity is currently moving, don't let
        // the cliff-type push fight that approach (hazard-material push stays - it's already weak
        // enough not to meaningfully fight a deliberate lava-gap jump, same as before this change).
        double moveLenSq = u.motionX * u.motionX + u.motionZ * u.motionZ;
        boolean hasJumpAhead = false;
        double jumpDirX = 0.0D, jumpDirZ = 0.0D;
        if (moveLenSq > 1.0E-4D) {
            double moveLen = Math.sqrt(moveLenSq);
            jumpDirX = u.motionX / moveLen;
            jumpDirZ = u.motionZ / moveLen;
            GapJumpResult gap = GapJumpEvaluator.evaluate(u.world, u.world.getTotalWorldTime(),
                    feet.getX(), feet.getY(), feet.getZ(), jumpDirX, jumpDirZ);
            hasJumpAhead = gap.type == GapJumpResult.Type.JUMPABLE;
        }

        // Whether the entity's OWN column is already the danger (shoved past the edge, not just
        // near it). The edge-repulsion loop below only ever pushes AWAY from a neighbor column, so
        // once the entity is centered on the hazard itself there is no "away from self" direction
        // left to push along - it silently stopped resisting right when it mattered most and just
        // fell in. Handled separately below: search for the nearest safe neighbor and push TOWARD
        // it instead, at full strength.
        boolean selfHazardMaterial = isHazardMaterialColumn(u.world, feet);
        boolean selfCliffDanger = !selfHazardMaterial
                && computeCliffDropDepth(u.world, feet, cfg.cliffMaxScanDepth) >= cfg.cliffMinDropDepth;
        boolean selfDangerous = selfHazardMaterial || selfCliffDanger;

        double pushX = 0.0D;
        double pushZ = 0.0D;

        double bestSafeDistSq = Double.MAX_VALUE;
        double bestSafeDirX = 0.0D;
        double bestSafeDirZ = 0.0D;
        boolean hasSafeDir = false;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean isSelf = dx == 0 && dz == 0;
                BlockPos col = feet.add(dx, 0, dz);

                double colX = col.getX() + 0.5D;
                double colZ = col.getZ() + 0.5D;
                double awayX = u.posX - colX;
                double awayZ = u.posZ - colZ;
                double distSq = awayX * awayX + awayZ * awayZ;
                if (distSq > radius * radius) continue;

                boolean hazardMat = isSelf ? selfHazardMaterial : isHazardMaterialColumn(u.world, col);
                boolean waterCol = !hazardMat && isWaterColumn(u.world, col);
                boolean cliffDanger = !hazardMat && !waterCol
                        && computeCliffDropDepth(u.world, col, cfg.cliffMaxScanDepth) >= cfg.cliffMinDropDepth;

                if (selfDangerous && !isSelf && !hazardMat && !cliffDanger && distSq < bestSafeDistSq) {
                    bestSafeDistSq = distSq;
                    double dist0 = Math.sqrt(Math.max(distSq, 1.0E-4D));
                    bestSafeDirX = -awayX / dist0;
                    bestSafeDirZ = -awayZ / dist0;
                    hasSafeDir = true;
                }

                if (isSelf) continue; // no "away from self" direction for the edge-repulsion pass below

                double dist = Math.sqrt(Math.max(distSq, 1.0E-4D));
                double falloff = 1.0D - dist / radius;

                double t;
                boolean cliffType = false;

                if (hazardMat) {
                    t = cfg.hazardStrength * falloff;
                } else if (waterCol) {
                    t = cfg.waterStrength * falloff;
                } else if (cliffDanger) {
                    int depth = computeCliffDropDepth(u.world, col, cfg.cliffMaxScanDepth);
                    int span = Math.max(1, cfg.cliffMaxScanDepth - cfg.cliffMinDropDepth);
                    double factor = Math.min(1.0D, (depth - cfg.cliffMinDropDepth) / (double) span);
                    t = cfg.cliffStrength * factor * falloff;
                    cliffType = true;
                } else {
                    continue;
                }

                if (t <= 0.0D) continue;

                if (cliffType && hasJumpAhead) {
                    double toColX = colX - u.posX;
                    double toColZ = colZ - u.posZ;
                    double toColLen = Math.sqrt(toColX * toColX + toColZ * toColZ);
                    if (toColLen > 1.0E-4D
                            && (toColX / toColLen) * jumpDirX + (toColZ / toColLen) * jumpDirZ > 0.3D) {
                        continue; // roughly ahead in the direction of a validated jump - don't resist it
                    }
                }

                pushX += (awayX / dist) * t;
                pushZ += (awayZ / dist) * t;
            }
        }

        if (u.getAttackTarget() != null || u.getRevengeTarget() != null) {
            pushX *= cfg.combatRepulsionFactor;
            pushZ *= cfg.combatRepulsionFactor;
        }

        // Emergency push is deliberately NOT reduced by the combat factor above - standing on the
        // hazard itself is worse than a moment's hesitation approaching an enemy near a ledge.
        if (selfDangerous && hasSafeDir) {
            double strength = selfHazardMaterial ? cfg.hazardStrength : cfg.cliffStrength;
            pushX += bestSafeDirX * strength;
            pushZ += bestSafeDirZ * strength;
        }

        if (pushX == 0.0D && pushZ == 0.0D) return;

        u.motionX += pushX;
        u.motionZ += pushZ;

        if (cfg.debugHazardRepulsion && (Math.abs(pushX) + Math.abs(pushZ)) > 0.002D) {
            net.minecraftforge.fml.common.FMLLog.log(
                    org.apache.logging.log4j.Level.INFO,
                    "[Travellers/HazardRepulsion] %s#%d at %.1f,%.1f,%.1f push=(%.4f,%.4f) selfDangerous=%b",
                    u.getClass().getSimpleName(), u.getEntityId(), u.posX, u.posY, u.posZ,
                    pushX, pushZ, selfDangerous);
        }
    }

    /** Column hazard check ({@link BlockRules#isHazard}) at the column's own level or one below. */
    private static boolean isHazardMaterialColumn(World world, BlockPos col) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos p = col.up(dy);
            if (!world.isBlockLoaded(p)) continue;
            if (BlockRules.isHazard(world.getBlockState(p))) return true;
        }
        return false;
    }

    /** Water is a legitimate crossing medium (fording, swimming after a leader) - kept a separate, much weaker case than real hazards. */
    private static boolean isWaterColumn(World world, BlockPos col) {
        for (int dy = 0; dy >= -1; dy--) {
            BlockPos p = col.up(dy);
            if (!world.isBlockLoaded(p)) continue;
            if (BlockRules.isVanillaWater(world.getBlockState(p))) return true;
        }
        return false;
    }

    /**
     * Scans straight down from the entity's own feet level at the neighbor column, returning how
     * many blocks below that level the first solid floor is found (0 = flush with the entity's own
     * footing, no drop). No floor within {@code maxDepth} is treated as bottomless/void and clamped
     * to {@code maxDepth}. A liquid encountered along the way returns -1 (handled separately by the
     * hazard/water column checks, never treated as a "cliff").
     */
    private static int computeCliffDropDepth(World world, BlockPos colAtFeetLevel, int maxDepth) {
        for (int d = 0; d <= maxDepth; d++) {
            BlockPos p = colAtFeetLevel.down(d);
            if (!world.isBlockLoaded(p)) return maxDepth; // unloaded ahead: be conservative
            IBlockState state = world.getBlockState(p);
            if (state.getMaterial().isLiquid()) return -1;
            if (state.getBlockFaceShape(world, p, EnumFacing.UP) == BlockFaceShape.SOLID) return d;
        }
        return maxDepth;
    }
}
