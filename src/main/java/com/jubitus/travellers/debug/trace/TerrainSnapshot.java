package com.jubitus.travellers.debug.trace;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The local terrain picture used after every trace incident ({@link TravellerTracer}) and by the
 * {@code /travellersdebug path export} tool. Shared so the two can never disagree on what "the
 * ground here" means.
 *
 * <p>Two small grids centred on the body that actually moves (the horse while mounted): the floor
 * height of each column relative to the body's own feet (a wall reads {@code +2}, a drop {@code -3},
 * {@code ..} means no floor in range at all), and how many blocks are free above that floor.
 */
public final class TerrainSnapshot {

    private TerrainSnapshot() {
    }

    /** How far either side of the entity the terrain picture reaches. */
    private static final int TERRAIN_RADIUS = 4;
    /** How far above and below the body's feet a column is searched for its floor. */
    private static final int TERRAIN_SCAN_UP = 4;
    private static final int TERRAIN_SCAN_DOWN = 5;
    /** Ceilings higher than this above the floor are not named (a horse plus its step needs 3). */
    private static final int CEILING_SCAN = 4;

    /** w / l / s for the block, and remembers its name under that letter for the legend. */
    private static char kindLetter(World world, BlockPos pos, Map<String, Character> legend) {
        MountObstacle.Kind kind = MountObstacle.classify(world, pos);
        char c = kind == MountObstacle.Kind.WOOD ? 'w' : kind == MountObstacle.Kind.LEAVES ? 'l' : 's';
        net.minecraft.util.ResourceLocation id = world.getBlockState(pos).getBlock().getRegistryName();
        String name = id == null ? "?" : id.toString();
        if (legend.size() < 12) legend.putIfAbsent(name, c);
        return c;
    }

    /** Lines describing the terrain around {@code body}, unprefixed - callers pick their own marker. */
    public static List<String> lines(World world, Entity body, float yaw) {
        int feetY = MathHelper.floor(body.getEntityBoundingBox().minY + 0.001D);
        int cx = MathHelper.floor(body.posX);
        int cz = MathHelper.floor(body.posZ);
        return lines(world, cx, feetY, cz, yaw);
    }

    public static List<String> lines(World world, int cx, int feetY, int cz, float yaw) {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT,
                "terrain centre=%d,%d,%d yaw=%.0f  rows=north..south cols=west..east  floor dY relative to feet",
                cx, feetY, cz, yaw));

        out.add("  grids: floor dY | free blocks above floor | floor block + ceiling block "
                + "(w wood, l leaves, s other solid, . no ceiling within 4)");
        StringBuilder heights = new StringBuilder();
        StringBuilder head = new StringBuilder();
        StringBuilder kinds = new StringBuilder();
        Map<String, Character> legend = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = -TERRAIN_RADIUS; dz <= TERRAIN_RADIUS; dz++) {
            heights.setLength(0);
            head.setLength(0);
            kinds.setLength(0);
            heights.append("  ");
            head.append("  ");
            for (int dx = -TERRAIN_RADIUS; dx <= TERRAIN_RADIUS; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                int floor = floorYAt(world, x, feetY, z);
                if (floor == Integer.MIN_VALUE) {
                    heights.append(" ..");
                    head.append("  .");
                    kinds.append(" ..");
                } else {
                    int free = freeAbove(world, x, floor, z);
                    heights.append(String.format(Locale.ROOT, "%+3d", floor - feetY));
                    head.append(String.format(Locale.ROOT, "%3d", free));
                    kinds.append(' ').append(kindLetter(world, pos.setPos(x, floor - 1, z), legend));
                    kinds.append(free <= CEILING_SCAN ? kindLetter(world, pos.setPos(x, floor + free, z), legend) : '.');
                }
            }
            out.add(heights.toString() + "   |" + head.substring(2) + "   |" + kinds);
        }
        StringBuilder names = new StringBuilder("  blocks:");
        for (Map.Entry<String, Character> e : legend.entrySet()) {
            names.append(' ').append(e.getValue()).append('=').append(e.getKey());
        }
        out.add(names.toString());
        return out;
    }

    /**
     * Y of the topmost surface in this column that something could actually stand on, scanning down
     * from a little above the feet. {@link Integer#MIN_VALUE} when there is none in range.
     *
     * <p>"Could stand on" means solid below with two free blocks above - returning the first solid
     * block met on the way down instead reports <em>ceilings</em> as ground.
     */
    private static int floorYAt(World world, int x, int feetY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = feetY + TERRAIN_SCAN_UP; y >= feetY - TERRAIN_SCAN_DOWN; y--) {
            pos.setPos(x, y - 1, z);
            if (world.getBlockState(pos).getCollisionBoundingBox(world, pos) == null) continue;
            if (freeAbove(world, x, y, z) < 2) continue;
            return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Free blocks above a floor, capped at 9 so the grid stays one character per column. */
    private static int freeAbove(World world, int x, int floorY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < 9; n++) {
            pos.setPos(x, floorY + n, z);
            if (world.getBlockState(pos).getCollisionBoundingBox(world, pos) != null) return n;
        }
        return 9;
    }
}
