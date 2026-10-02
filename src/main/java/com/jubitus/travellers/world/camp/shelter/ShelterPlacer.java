package com.jubitus.travellers.world.camp.shelter;

import com.jubitus.travellers.world.build.BuildStep;
import com.jubitus.travellers.world.build.StructureBuildPlan;
import com.jubitus.travellers.world.camp.ShelterSlot;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Mirror;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.gen.structure.template.PlacementSettings;
import net.minecraft.world.gen.structure.template.Template;

import java.util.List;

/**
 * Grades the footprint and computes the incremental {@link BuildStep} plan for a shelter template.
 * Rotation is derived so the authored entrance ends up facing the campfire; the footprint is anchored
 * on the slot regardless of rotation; the terrain it overwrites is snapshotted first so teardown
 * restores it exactly. Actual block-by-block placement/removal is driven by
 * {@link com.jubitus.travellers.world.build.IncrementalStructureBuilder} over time, not here.
 */
public final class ShelterPlacer {

    private ShelterPlacer() {
    }

    public static class BuildStart {
        public final TerrainSnapshot snapshot;
        /** Template steps only — the caller prepends the fill steps (see CampBuildCoordinator). */
        public final List<BuildStep> plan;
        /** Cells needing fill under/at floor level, bottom-up build order. Placed manually, not here. */
        public final List<BlockPos> fillCells;
        /** Fill material for cells at floor level (sampled from the local terrain surface). */
        public final IBlockState fillSurface;
        /** Fill material for cells below floor level (sampled from under the local surface). */
        public final IBlockState fillSub;

        public BuildStart(TerrainSnapshot snapshot, List<BuildStep> plan,
                          List<BlockPos> fillCells, IBlockState fillSurface, IBlockState fillSub) {
            this.snapshot = snapshot;
            this.plan = plan;
            this.fillCells = fillCells;
            this.fillSurface = fillSurface;
            this.fillSub = fillSub;
        }
    }

    /** Rotation that turns the authored entrance facing into the desired facing. */
    public static Rotation rotationToFace(EnumFacing authored, EnumFacing desired) {
        for (Rotation r : Rotation.values()) {
            if (r.rotate(authored) == desired) return r;
        }
        return Rotation.NONE;
    }

    private static final class Anchor {
        final PlacementSettings settings;
        final BlockPos anchor;
        final int sizeX, sizeZ, height;

        Anchor(PlacementSettings settings, BlockPos anchor, int sizeX, int sizeZ, int height) {
            this.settings = settings;
            this.anchor = anchor;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
            this.height = height;
        }
    }

    /** Pure geometry: rotation/anchor so the footprint's min XZ corner lands on the slot, entrance toward the firepit. */
    private static Anchor computeAnchor(ShelterSlot slot, ShelterTemplate shelter) {
        Rotation rot = rotationToFace(shelter.entranceFacing, slot.entranceFacing);
        PlacementSettings settings = new PlacementSettings()
                .setRotation(rot)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(false);

        BlockPos size = shelter.getSize();
        int minTX = Integer.MAX_VALUE, minTZ = Integer.MAX_VALUE;
        int maxTX = Integer.MIN_VALUE, maxTZ = Integer.MIN_VALUE;
        int[][] corners = {{0, 0}, {size.getX() - 1, 0}, {0, size.getZ() - 1}, {size.getX() - 1, size.getZ() - 1}};
        for (int[] c : corners) {
            BlockPos t = Template.transformedBlockPos(settings, new BlockPos(c[0], 0, c[1]));
            minTX = Math.min(minTX, t.getX());
            maxTX = Math.max(maxTX, t.getX());
            minTZ = Math.min(minTZ, t.getZ());
            maxTZ = Math.max(maxTZ, t.getZ());
        }
        // Template y=0 (the layer authored directly on the red-wool anchor) always lands at floorY,
        // replacing the ground surface — matches how it looked sitting on the wool during capture,
        // whatever's stacked above (bed straight on the ground, or a floor layer then a bed) follows naturally.
        int anchorY = slot.origin.getY();
        BlockPos anchor = new BlockPos(slot.origin.getX() - minTX, anchorY, slot.origin.getZ() - minTZ);
        return new Anchor(settings, anchor, maxTX - minTX + 1, maxTZ - minTZ + 1, size.getY());
    }

    /**
     * Prepares {@code slot}'s footprint for {@code shelter} (entrance toward the firepit): snapshots the
     * terrain it will occupy, clears everything above floor level, samples fill materials from the local
     * terrain, and computes the template build plan plus the list of fill cells the ground needs under
     * the structure. Neither shelter blocks nor fill blocks are placed here — the caller advances the
     * (fill + template) plan incrementally. Call once, the first time a slot starts building.
     */
    public static BuildStart startBuild(World world, ShelterSlot slot, ShelterTemplate shelter) {
        Anchor a = computeAnchor(slot, shelter);
        int floorY = slot.origin.getY();

        // Snapshot covers grading depth below floor + full shelter height above it.
        int snapBottomY = floorY - GRADE_DEPTH;
        int snapTopY = floorY + a.height - 1;
        int snapTotalHeight = snapTopY - snapBottomY + 1;
        BlockPos snapOrigin = new BlockPos(slot.origin.getX(), snapBottomY, slot.origin.getZ());
        TerrainSnapshot snapshot = TerrainSnapshot.capture(world, snapOrigin, a.sizeX, snapTotalHeight, a.sizeZ);

        // Sample before clearing: the surface we want to imitate is the untouched one.
        net.minecraft.block.state.IBlockState[] materials =
                sampleFillMaterials(world, slot.origin.getX(), slot.origin.getZ(), a.sizeX, a.sizeZ);

        clearAboveFloor(world, slot.origin.getX(), slot.origin.getZ(), a.sizeX, a.sizeZ, floorY, a.height);
        if (world instanceof net.minecraft.world.WorldServer) {
            net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) world;
            CampLighting.refresh(ws, snapOrigin, a.sizeX, snapTotalHeight, a.sizeZ);
            // Clearing can leave anyone standing in the footprint mid-air/embedded (e.g. the builder
            // itself, approaching before the reach/LOS gate catches up) — pop them back onto solid ground.
            com.jubitus.travellers.virtual.MaterializePositionResolver.unstickEntitiesInVolume(
                    ws, snapOrigin, snapOrigin.add(a.sizeX - 1, snapTotalHeight - 1, a.sizeZ - 1));
        }

        List<BuildStep> plan = StructureBuildPlan.build(shelter.template, a.settings, a.anchor);
        List<BlockPos> fillCells = computeFillCells(world, slot.origin.getX(), slot.origin.getZ(),
                a.sizeX, a.sizeZ, floorY, plan);
        return new BuildStart(snapshot, plan, fillCells, materials[0], materials[1]);
    }

    /**
     * Recomputes just the ordered build plan (no grading/snapshot side effects) — for resuming a build
     * already in progress (e.g. after a restart) without re-touching terrain.
     */
    public static List<BuildStep> planOnly(ShelterSlot slot, ShelterTemplate shelter) {
        Anchor a = computeAnchor(slot, shelter);
        return StructureBuildPlan.build(shelter.template, a.settings, a.anchor);
    }

    /** Depth (blocks) of fill placed under the floor to support a shelter over a dip. */
    private static final int GRADE_DEPTH = 4;

    /**
     * Inclusive [min, max] cuboid this shelter would grade/occupy at {@code slot} — exactly what
     * {@link #startBuild} snapshots. Pure; used to validate the terrain is still natural (no player
     * build appeared here since the slot was computed) right before the first grading touch.
     */
    public static BlockPos[] buildVolume(ShelterSlot slot, ShelterTemplate shelter) {
        Anchor a = computeAnchor(slot, shelter);
        int floorY = slot.origin.getY();
        BlockPos min = new BlockPos(slot.origin.getX(), floorY - GRADE_DEPTH, slot.origin.getZ());
        BlockPos max = new BlockPos(slot.origin.getX() + a.sizeX - 1, floorY + a.height - 1, slot.origin.getZ() + a.sizeZ - 1);
        return new BlockPos[]{min, max};
    }

    /**
     * Clears everything (terrain bumps, foliage) from just above the floor up through the shelter
     * height so nothing pokes into the interior. Uses flag 2 (no neighbour notify). Filling BELOW the
     * floor is deliberately not done here anymore — it's returned as fill cells the builder places by
     * hand as the first steps of the plan (see {@link #computeFillCells}).
     */
    private static void clearAboveFloor(World world, int ox, int oz, int sizeX, int sizeZ, int floorY, int height) {
        net.minecraft.block.state.IBlockState air = net.minecraft.init.Blocks.AIR.getDefaultState();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                for (int y = floorY + 1; y < floorY + height; y++) {
                    BlockPos p = new BlockPos(ox + dx, y, oz + dz);
                    if (!world.isAirBlock(p)) world.setBlockState(p, air, 2);
                }
            }
        }
    }

    /**
     * {surface, subsurface} fill materials matching the terrain the shelter sits on: majority vote of
     * the footprint columns' actual top-solid block (used for cells at floor level) and the block one
     * below it (used for the buried support fill) — so a shelter on sand gets banked up with sand, on
     * mesa with clay, and modded surface blocks are picked up as-is. Falls back to the biome's own
     * topBlock/fillerBlock, then plain grass/dirt.
     */
    private static net.minecraft.block.state.IBlockState[] sampleFillMaterials(World world, int ox, int oz, int sizeX, int sizeZ) {
        java.util.Map<net.minecraft.block.state.IBlockState, Integer> surf = new java.util.HashMap<>();
        java.util.Map<net.minecraft.block.state.IBlockState, Integer> sub = new java.util.HashMap<>();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                BlockPos top = world.getTopSolidOrLiquidBlock(new BlockPos(ox + dx, 0, oz + dz));
                if (top.getY() <= 1) continue;
                tallyFillCandidate(world, top.down(), surf);
                tallyFillCandidate(world, top.down(2), sub);
            }
        }
        net.minecraft.world.biome.Biome biome = world.getBiome(new BlockPos(ox + sizeX / 2, 0, oz + sizeZ / 2));
        net.minecraft.block.state.IBlockState surface = majorityOr(surf,
                isFillMaterial(biome.topBlock) ? biome.topBlock : net.minecraft.init.Blocks.GRASS.getDefaultState());
        net.minecraft.block.state.IBlockState subsurface = majorityOr(sub,
                isFillMaterial(biome.fillerBlock) ? biome.fillerBlock : net.minecraft.init.Blocks.DIRT.getDefaultState());
        return new net.minecraft.block.state.IBlockState[]{surface, subsurface};
    }

    private static void tallyFillCandidate(World world, BlockPos pos,
                                           java.util.Map<net.minecraft.block.state.IBlockState, Integer> tally) {
        net.minecraft.block.state.IBlockState st = world.getBlockState(pos);
        if (!isFillMaterial(st)) return;
        if (st.getBlock().hasTileEntity(st)) return;
        tally.merge(st, 1, Integer::sum);
    }

    /** Solid, full-cube, natural-terrain-looking state usable as fill. */
    private static boolean isFillMaterial(net.minecraft.block.state.IBlockState st) {
        net.minecraft.block.material.Material m = st.getMaterial();
        return m.isSolid() && !m.isLiquid() && st.isFullCube()
                && m != net.minecraft.block.material.Material.LEAVES
                && m != net.minecraft.block.material.Material.WOOD
                && m != net.minecraft.block.material.Material.ICE
                && m != net.minecraft.block.material.Material.PACKED_ICE
                && m != net.minecraft.block.material.Material.CRAFTED_SNOW;
    }

    private static net.minecraft.block.state.IBlockState majorityOr(
            java.util.Map<net.minecraft.block.state.IBlockState, Integer> tally,
            net.minecraft.block.state.IBlockState fallback) {
        net.minecraft.block.state.IBlockState best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<net.minecraft.block.state.IBlockState, Integer> e : tally.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best != null ? best : fallback;
    }

    /**
     * Cells the ground needs banked up so the shelter sits flush on solid terrain: for each footprint
     * column, the floor cell if it isn't solid (and the template doesn't put its own block there), plus
     * the contiguous non-solid run directly below it down to {@link #GRADE_DEPTH}. Bottom-up order so
     * each fill block is placed on support. This is exactly what the old instant grading filled with
     * dirt — now it's returned as work for the builder to do by hand.
     */
    private static List<BlockPos> computeFillCells(World world, int ox, int oz, int sizeX, int sizeZ,
                                                   int floorY, List<BuildStep> plan) {
        java.util.Set<BlockPos> covered = new java.util.HashSet<>();
        for (BuildStep s : plan) {
            covered.add(s.pos);
            if (s.companion != null) covered.add(s.companion.pos);
        }
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int x = ox + dx, z = oz + dz;
                for (int y = floorY - 1; y >= floorY - GRADE_DEPTH; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (world.getBlockState(p).getMaterial().isSolid()) break;
                    cells.add(p);
                }
                BlockPos floor = new BlockPos(x, floorY, z);
                if (!world.getBlockState(floor).getMaterial().isSolid() && !covered.contains(floor)) {
                    cells.add(floor);
                }
            }
        }
        cells.sort(java.util.Comparator
                .comparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getZ)
                .thenComparingInt(BlockPos::getX));
        return cells;
    }

    /** Tears down a previously placed shelter by restoring the snapshotted terrain. */
    public static void remove(World world, TerrainSnapshot snapshot) {
        snapshot.restore(world);
    }
}
