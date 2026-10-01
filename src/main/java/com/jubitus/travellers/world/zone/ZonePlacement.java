package com.jubitus.travellers.world.zone;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.ground.Footing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/** Zone-side searches for a spot to put a worker or horse down, built on {@link Footing}. Never loads a chunk. */
public final class ZonePlacement {

    private ZonePlacement() {
    }

    /**
     * A standable column at {@code anchor}, or the closest one within {@code vTolerance} blocks
     * above/below it (checked nearest-first: anchor, then down 1 / up 1, then down 2 / up 2, ...).
     * Null if nothing in range.
     */
    @Nullable
    public static BlockPos findStandableNear(World world, BlockPos anchor, int vTolerance) {
        if (Footing.canStandAt(world, anchor)) return anchor;
        for (int dy = 1; dy <= vTolerance; dy++) {
            BlockPos down = anchor.down(dy);
            if (Footing.canStandAt(world, down)) return down;
            BlockPos up = anchor.up(dy);
            if (Footing.canStandAt(world, up)) return up;
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
                    if (!Footing.isColumnLoaded(world, p)) break;
                    if (!Footing.isFloor(world, p.down(), world.getBlockState(p.down()))) continue;
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
