package com.jubitus.travellers.unit;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.Footing;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.village.MillFootprints;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * The one test of "may a unit be put down with its feet in this cell" (rescue hops, landings, spawns,
 * reappearing), and the ring search the placement paths share. Each caller keeps its own preference
 * (nearest, toward a leader, forward); the safety rule lives here only. Never loads a chunk.
 */
public final class LandingSpots {

    private LandingSpots() {
    }

    /**
     * Why a unit may not be put down here, or null when it may: the cell is loaded, holds a real floor
     * ({@link Footing}) or, with {@code allowWater}, swimmable water; it is not in or beside a Millénaire
     * building unless {@code allowInsideBuildings}; and with a {@code body}, its whole box (horse and
     * rider when riding) hits no block and touches no hazard. The reason is a short trace tag.
     */
    @Nullable
    public static String whyUnsafe(World world, BlockPos feet, @Nullable EntityTraveller body,
                                   boolean allowWater, boolean allowInsideBuildings) {
        if (!LoadedChunks.isAreaLoaded(world, feet, 1)) return "unloaded";
        boolean swim = allowWater && Footing.canSwimAt(world, feet);
        if (!swim && !Footing.canStandAt(world, feet)) return "noFloor";
        double y = feetY(world, feet);
        double cx = feet.getX() + 0.5D, cz = feet.getZ() + 0.5D;
        if (!allowInsideBuildings && MillFootprints.isForbiddenLanding(world, cx, y, cz)) return "building";
        if (body != null) {
            AxisAlignedBB box = bodyBox(body, cx, y, cz);
            if (touchesHazard(world, box)) return "hazard";
            if (!world.getCollisionBoxes(body, box).isEmpty()) return "blocked@" + feet.getY();
        }
        return null;
    }

    public static boolean isSafe(World world, BlockPos feet, @Nullable EntityTraveller body,
                                 boolean allowWater, boolean allowInsideBuildings) {
        return whyUnsafe(world, feet, body, allowWater, allowInsideBuildings) == null;
    }

    /**
     * First cell accepted by {@code ok}, ring by ring around {@code center} (r = 0..radius), each column
     * tried at the center's height, then one lower, one higher, two lower... up to {@code vertical}.
     */
    @Nullable
    public static BlockPos nearest(World world, BlockPos center, int radius, int vertical, Predicate<BlockPos> ok) {
        int cy = center.getY();
        for (int r = 0; r <= Math.max(0, radius); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = center.getX() + dx, z = center.getZ() + dz;
                    if (!LoadedChunks.isChunkLoaded(world, x >> 4, z >> 4)) continue;
                    for (int dy = 0; dy <= vertical; dy++) {
                        if (cy - dy >= 1) {
                            BlockPos down = new BlockPos(x, cy - dy, z);
                            if (ok.test(down)) return down;
                        }
                        if (dy > 0) {
                            BlockPos up = new BlockPos(x, cy + dy, z);
                            if (ok.test(up)) return up;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Feet Y once landed in this cell: just under the water surface when swimming, else on top of what is underfoot (snow layers included). */
    public static double feetY(World world, BlockPos feet) {
        if (Footing.canSwimAt(world, feet)) return feet.getY() + 0.02D;
        IBlockState atFeet = world.getBlockState(feet);
        if (atFeet.getBlock() == Blocks.SNOW_LAYER) return topOf(world, feet);
        return topOf(world, feet.down());
    }

    /** The unit's landing box at (cx, groundY, cz): horse and rider together when riding its bonded horse, else its own hitbox. */
    public static AxisAlignedBB bodyBox(EntityTraveller unit, double cx, double groundY, double cz) {
        AxisAlignedBB mounted = TravellerMountSupport.mountedLandingBox(unit, cx, groundY, cz);
        if (mounted != null) return mounted;
        return unit.getEntityBoundingBox().offset(cx - unit.posX, groundY - unit.posY, cz - unit.posZ);
    }

    /** True if any block the box overlaps is a hazard ({@link BlockRules#isHazard}) or is not loaded. */
    public static boolean touchesHazard(World world, AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX), maxX = MathHelper.floor(box.maxX + 1.0);
        int minY = MathHelper.floor(box.minY), maxY = MathHelper.floor(box.maxY + 1.0);
        int minZ = MathHelper.floor(box.minZ), maxZ = MathHelper.floor(box.maxZ + 1.0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                if (!LoadedChunks.isChunkLoaded(world, x >> 4, z >> 4)) return true;
                for (int y = minY; y < maxY; y++) {
                    pos.setPos(x, y, z);
                    if (BlockRules.isHazard(world.getBlockState(pos))) return true;
                }
            }
        }
        return false;
    }

    private static double topOf(World world, BlockPos ground) {
        AxisAlignedBB bb = world.getBlockState(ground).getCollisionBoundingBox(world, ground);
        return bb == null ? ground.getY() : ground.getY() + bb.maxY;
    }
}
