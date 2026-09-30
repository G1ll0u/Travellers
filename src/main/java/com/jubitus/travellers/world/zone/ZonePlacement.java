package com.jubitus.travellers.world.zone;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/**
 * The one place any zone type answers "is this a spot something could stand on" / "find me a standable
 * spot near here". Replaces the several near-identical {@code standable()} helpers that had drifted
 * across {@code StableCoordinator}, {@code PlayerZoneManager} and {@code StableHandTask} - the same
 * "many disagreeing clearance models" trap CLAUDE.md calls out for the mount system.
 */
public final class ZonePlacement {

    private ZonePlacement() {
    }

    /** Solid top below, two air blocks at {@code p} - a single-column stand check, no body width. */
    public static boolean isStandableColumn(World world, BlockPos p) {
        return world.getBlockState(p.down()).isSideSolid(world, p.down(), EnumFacing.UP)
                && world.isAirBlock(p) && world.isAirBlock(p.up());
    }

    /**
     * A standable column at {@code anchor}, or the closest one within {@code vTolerance} blocks
     * above/below it (checked nearest-first: anchor, then down 1 / up 1, then down 2 / up 2, ...).
     * Null if nothing in range.
     */
    @Nullable
    public static BlockPos findStandableNear(World world, BlockPos anchor, int vTolerance) {
        if (isStandableColumn(world, anchor)) return anchor;
        for (int dy = 1; dy <= vTolerance; dy++) {
            BlockPos down = anchor.down(dy);
            if (isStandableColumn(world, down)) return down;
            BlockPos up = anchor.up(dy);
            if (isStandableColumn(world, up)) return up;
        }
        return null;
    }

    /**
     * Closest cell to {@code (tx,ty,tz)} within a small ring (same room as the target), highest valid
     * Y per column, checked against {@code ref}'s real render-scaled hitbox so the body won't wedge.
     * Used by duty snap-hops to pick a landing next to a walled-in chest / horse. Null if none.
     */
    @Nullable
    public static BlockPos findHitboxStandableNear(WorldServer world, double tx, double ty, double tz,
                                                   EntityTraveller ref) {
        BlockPos target = new BlockPos(tx, ty, tz);
        BlockPos best = null;
        double bestHoriz = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                double horiz = dx * dx + dz * dz;
                if (horiz >= bestHoriz) continue;
                for (int dy = 1; dy >= -2; dy--) {
                    BlockPos p = target.add(dx, dy, dz);
                    if (!world.getBlockState(p.down()).isSideSolid(world, p.down(), EnumFacing.UP)) continue;
                    if (!ref.fitsStandingAt(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D)) continue;
                    best = p;
                    bestHoriz = horiz;
                    break;
                }
            }
        }
        return best;
    }
}
