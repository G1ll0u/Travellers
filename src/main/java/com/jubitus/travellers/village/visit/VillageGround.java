package com.jubitus.travellers.village.visit;

import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.follow.FollowRecoveryHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/** Standing spots in and around Millénaire settlements, on loaded ground only. "Outdoor" means
 * outside every building footprint - which rules out roofs by construction. */
public final class VillageGround {

    private VillageGround() {
    }

    /** The feet position to stand at for a Millénaire point, which may be recorded on the floor block
     * itself or one above it. Null when nothing around it is standable (or not loaded). */
    @Nullable
    public static BlockPos standAt(World world, BlockPos p) {
        if (com.jubitus.travellers.ground.Footing.canStandAt(world, p)) return p;
        if (com.jubitus.travellers.ground.Footing.canStandAt(world, p.up())) return p.up();
        if (com.jubitus.travellers.ground.Footing.canStandAt(world, p.down())) return p.down();
        return null;
    }

    /** A standable outdoor spot in the column of {@code seed}, within {@code dy} blocks of its height. */
    @Nullable
    public static BlockPos outdoorInColumn(World world, BlockPos seed, int dy) {
        for (int i = 0; i <= dy; i++) {
            BlockPos up = seed.up(i);
            if (isOutdoorStand(world, up)) return up;
            if (i > 0) {
                BlockPos down = seed.down(i);
                if (isOutdoorStand(world, down)) return down;
            }
        }
        return null;
    }

    /** The closest outdoor spot (keeping the configured clearance from buildings) around {@code near}. */
    @Nullable
    public static BlockPos nearestOutdoor(World world, BlockPos near, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos spot = outdoorInColumn(world, near.add(dx, 0, dz), 6);
                    if (spot != null && !MillFootprints.isForbiddenLanding(world,
                            spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D)) {
                        return spot;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isOutdoorStand(World world, BlockPos feet) {
        return com.jubitus.travellers.ground.Footing.canStandAt(world, feet)
                && MillFootprints.at(world, feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, 0) == null;
    }
}
