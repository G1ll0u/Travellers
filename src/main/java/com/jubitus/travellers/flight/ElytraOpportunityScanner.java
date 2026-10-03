package com.jubitus.travellers.flight;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Decides whether gliding from here toward a destination actually pays off, and where it would land.
 *
 * <p>The whole test is one geometric question: how far along the way can the entity get before its
 * glide line - the straight descent a steady glide traces from its launch height - runs into the
 * ground? Terrain that stays well below that line is a glide; terrain that doesn't is a walk. Flat
 * ground fails on the first sample or two, which is what keeps travellers walking almost all of the
 * time without needing a separate "is this flat" rule.
 *
 * <p>Never loads a chunk: an unloaded column ends the scan rather than being probed.
 */
public final class ElytraOpportunityScanner {

    private ElytraOpportunityScanner() {}

    /** Spacing between samples along the corridor, in blocks. */
    private static final double SCAN_STEP = 8.0D;
    /** How far below the entity a landing surface is still looked for. */
    private static final int MAX_DROP_SCAN = 64;
    /** How far above the entity terrain is checked for, to catch rising ground and hillsides. */
    private static final int HEADROOM_SCAN = 8;
    /** Returned by {@link #surfaceHeight} when the column is unloaded or has no ground in range. */
    private static final int NO_SURFACE = Integer.MIN_VALUE;

    /**
     * @return where to glide to, or {@code null} when walking is the better option from here.
     */
    @Nullable
    public static Vec3d findGlideTarget(EntityTraveller mob, BlockPos destination) {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;
        World world = mob.world;

        double dx = (destination.getX() + 0.5D) - mob.posX;
        double dz = (destination.getZ() + 0.5D) - mob.posZ;
        double distToDest = Math.sqrt(dx * dx + dz * dz);

        if (distToDest < cfg.minTravelDistance) return null;

        double dirX = dx / distToDest;
        double dirZ = dz / distToDest;

        // The assisted jump buys a little height before the wings take over, and on gentle terrain
        // that is the entire margin the glide has to work with - so it belongs in the budget.
        double launchGain = cfg.assistedLaunch ? launchHeightGain(cfg) : 0.0D;
        double startY = mob.posY + launchGain;
        double glideSlope = 1.0D / Math.max(0.1D, cfg.glideRatio);

        double maxScan = Math.min(cfg.maxScanDistance, distToDest);
        double bestDistance = 0.0D;
        int bestSurface = NO_SURFACE;

        for (double d = SCAN_STEP; d <= maxScan; d += SCAN_STEP) {
            int x = MathHelper.floor(mob.posX + dirX * d);
            int z = MathHelper.floor(mob.posZ + dirZ * d);

            int surface = surfaceHeight(world, x, z,
                    MathHelper.floor(mob.posY) + HEADROOM_SCAN,
                    MathHelper.floor(mob.posY) - MAX_DROP_SCAN);

            // Unknown terrain (unloaded, or a drop deeper than we scan): stop here rather than guess.
            if (surface == NO_SURFACE) break;

            double glideY = startY - d * glideSlope;
            if (surface > glideY - cfg.corridorClearance) break;

            bestDistance = d;
            bestSurface = surface;
        }

        if (bestSurface == NO_SURFACE || bestDistance < cfg.minGlideDistance) return null;

        return new Vec3d(
                mob.posX + dirX * bestDistance,
                bestSurface,
                mob.posZ + dirZ * bestDistance);
    }

    /**
     * Height gained by the assisted jump before the glide starts, from the launch impulse and vanilla
     * gravity (v^2 / 2g, with g = 0.08 per tick^2).
     */
    private static double launchHeightGain(TravellersConfig.Elytra cfg) {
        return (cfg.launchVerticalBoost * cfg.launchVerticalBoost) / 0.16D;
    }

    /**
     * Y level of the top of the highest solid block in the column between {@code topY} and
     * {@code bottomY}, or {@link #NO_SURFACE} if the column isn't loaded or has no ground in range.
     */
    private static int surfaceHeight(World world, int x, int z, int topY, int bottomY) {
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(x, Math.max(0, topY), z);
        if (!world.isBlockLoaded(probe)) return NO_SURFACE;

        for (int y = Math.min(255, topY); y >= Math.max(0, bottomY); y--) {
            probe.setPos(x, y, z);
            net.minecraft.block.material.Material material = world.getBlockState(probe).getMaterial();
            // Liquids count as the surface, not as air to be flown through: reporting the seabed under
            // an ocean would both aim the landing into the water and make the corridor look clear.
            if (material.blocksMovement() || material.isLiquid()) {
                return y + 1;
            }
        }
        return NO_SURFACE;
    }
}
