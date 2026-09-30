package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.flight.ElytraCapability;
import com.jubitus.travellers.entity.entityai.flight.ElytraOpportunityScanner;
import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import com.jubitus.travellers.entity.entityai.travel.mount.MountMode;
import com.jubitus.travellers.entity.entityai.travel.mount.MountedLeadController;
import com.jubitus.travellers.entity.entityai.travel.mount.TravellerMountSupport;
import com.jubitus.travellers.world.pathing.TravellerNavigateGround;
import com.jubitus.travellers.world.mill.MillVillageIndex;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelPathScheduler;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.pathing.route.TravelPlan;
import com.jubitus.travellers.world.pathing.itinerary.Itinerary;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryState;
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
 * Terrain questions the travel AI asks of the loaded world around one unit: where the ground or
 * water surface is, whether a cell is standable or swimmable, whether a landing spot is clear.
 * Loaded chunks only, never forces one.
 */
public final class TravelTerrain {

    // findGroundNear scoring, in "blocks of height difference"
    private static final double WAYPOINT_TREE_PENALTY = 8.0;
    private static final double WAYPOINT_GOOD_GROUND_SCORE = 2.0;

    private final EntityTraveller mob;

    TravelTerrain(EntityTraveller mob) {
        this.mob = mob;
    }

    static Vec3d goalDirXZ(EntityLiving mob, BlockPos target) {
        Vec3d pos = mob.getPositionVector();
        Vec3d v = new Vec3d(target.getX() + 0.5 - pos.x, 0, target.getZ() + 0.5 - pos.z);
        if (v.lengthSquared() < 1e-6) return new Vec3d(0, 0, 1);
        return v.normalize();
    }

    /**
     * True if the block at `pos` can support standing on top (accepts ice).
     */
    static boolean supportsStandingSurface(World w, BlockPos pos, IBlockState st) {
        if (!w.isBlockLoaded(pos)) return false;

        // NEW: never stand on leaves
        if (isLeafBlock(w, pos, st)) return false;

        if (st.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = st.getBlockFaceShape(w, pos, EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) return true;

        AxisAlignedBB bb = st.getBoundingBox(w, pos);
        if (bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001) return true;

        Block b = st.getBlock();
        if (b == Blocks.SNOW_LAYER) return true;

        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE
                || b == Blocks.GLASS || b == Blocks.STAINED_GLASS;
    }

    // BoP quicksand: user said it's registered as biomesoplenty:sand
    static boolean isBoPQuicksand(IBlockState s) {
        ResourceLocation rl = s.getBlock().getRegistryName();
        return rl != null && "biomesoplenty".equals(rl.getNamespace()) && "sand".equals(rl.getPath());
    }

    // Any liquid other than vanilla water is forbidden (lava, mod fluids, quicksand, etc.)
    static boolean isForbiddenLiquid(IBlockState s) {
        if (isBoPQuicksand(s)) return true;
        if (!s.getMaterial().isLiquid()) return false;
        return !isVanillaWater(s);
    }

    // AxisAlignedBB check that ignores vanilla water but flags any forbidden liquid
    public static boolean intersectsForbiddenLiquid(World w, AxisAlignedBB aabb) {
        int minX = MathHelper.floor(aabb.minX);
        int maxX = MathHelper.floor(aabb.maxX + 1.0);
        int minY = MathHelper.floor(aabb.minY);
        int maxY = MathHelper.floor(aabb.maxY + 1.0);
        int minZ = MathHelper.floor(aabb.minZ);
        int maxZ = MathHelper.floor(aabb.maxZ + 1.0);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    pos.setPos(x, y, z);
// ✅ DO NOT LOAD CHUNKS just to check fluids
                    if (!w.isBlockLoaded(pos)) return true; // treat unknown as unsafe
                    IBlockState s = w.getBlockState(pos);
                    if (isForbiddenLiquid(s)) return true;
                }
            }
        }
        return false;
    }

    // ---- Leaves helper (1.12.2 + modded leaves support) ----
    static boolean isLeafBlock(World w, BlockPos pos, IBlockState s) {
        Block b = s.getBlock();
        if (b instanceof net.minecraft.block.BlockLeaves) return true;
        try {
            return b.isLeaves(s, w, pos); // many mods override this
        } catch (Throwable ignore) {
            return false;
        }
    }

    // --- Snow height helpers (1.12.2) ---
    static int snowLayers(IBlockState s) {
        // BlockSnow (the thin "layer") has LAYERS 1..8; BlockSnow (full block) is Blocks.SNOW
        if (s.getBlock() == Blocks.SNOW_LAYER) {
            try {
                return s.getValue(net.minecraft.block.BlockSnow.LAYERS);
            } catch (Throwable ignore) {
            }
        }
        return 0;
    }

    static boolean isChunkSafeLoaded(World world, int cx, int cz) {
        return world instanceof WorldServer && ((WorldServer) world).getChunkProvider().chunkExists(cx, cz);
    }

    static boolean isBlockSafeLoaded(World world, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        return isChunkSafeLoaded(world, cx, cz);
    }

    static double horizDistSq(BlockPos a, BlockPos b) {
        double dx = (a.getX() + 0.5) - (b.getX() + 0.5);
        double dz = (a.getZ() + 0.5) - (b.getZ() + 0.5);
        return dx * dx + dz * dz;
    }

    BlockPos surfaceAtOffset(BlockPos from, Vec3d dir, int s, Vec3d lateral) {
        int nx = from.getX() + (int) Math.round(dir.x * s + lateral.x);
        int nz = from.getZ() + (int) Math.round(dir.z * s + lateral.z);
        BlockPos surface = findGroundNear(new BlockPos(nx, from.getY(), nz));
        return (surface != null && isAreaLoaded(surface, 1) && isNavigable(surface)) ? surface : null;
    }

    /**
     * Where a traveller walking at its current height would actually stand around this XZ column:
     * the standable cell (or water surface) closest in height to the traveller, so a waypoint under
     * a tree lands on the forest floor rather than the canopy. A column with no good ground at that
     * height (a trunk, a pillar) shifts to a neighbouring column. Null when nothing is loaded/standable.
     */
    @Nullable
    BlockPos findGroundNear(BlockPos xz) {
        if (!isAreaLoaded(xz, 1)) return null;
        int refY = MathHelper.floor(mob.posY);
        int maxRing = TravellersConfig.TRAVEL.waypointGroundSearchRadius;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int r = 0; r <= maxRing; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos c = groundInColumn(xz.getX() + dx, xz.getZ() + dz, refY);
                    if (c == null) continue;
                    double score = Math.abs(c.getY() - refY) + r * 2.0
                            + (isTreePart(c.down()) ? WAYPOINT_TREE_PENALTY : 0.0);
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            }
            if (best != null && bestScore <= WAYPOINT_GOOD_GROUND_SCORE + r * 2.0) break;
        }
        return best;
    }

    /** Standable cell or water surface in one column, closest in height to {@code refY}. */
    @Nullable
    BlockPos groundInColumn(int x, int z, int refY) {
        BlockPos col = new BlockPos(x, 0, z);
        if (!isBlockSafeLoaded(mob.world, col)) return null;
        int top = Math.min(mob.world.getHeight(col).getY() + 1, mob.world.getActualHeight() - 2);
        int bottom = Math.max(2, Math.max(top - 64, refY - 24));
        BlockPos best = null;
        int bestDy = Integer.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = top; y >= bottom; y--) {
            if (best != null && refY - y > bestDy) break;
            p.setPos(x, y, z);
            if (isVanillaWater(mob.world.getBlockState(p))) {
                BlockPos surface = snapToWaterSurface(p.toImmutable());
                if (surface != null && isSwimmable(surface) && Math.abs(surface.getY() - refY) < bestDy) {
                    bestDy = Math.abs(surface.getY() - refY);
                    best = surface;
                }
                break; // never stand on a seabed
            }
            if (isStandable(p)) {
                int dy = Math.abs(y - refY);
                if (dy < bestDy) {
                    bestDy = dy;
                    best = p.toImmutable();
                }
            }
            // Scan through trees and man-made blocks, never through natural terrain (cave below).
            BlockPos below = p.down();
            if (mob.world.getBlockState(below).getMaterial().blocksMovement() && isNaturalGroundSupport(below)) break;
        }
        return best;
    }

    boolean isTreePart(BlockPos pos) {
        IBlockState s = mob.world.getBlockState(pos);
        Block b = s.getBlock();
        if (b instanceof net.minecraft.block.BlockLog || isLeafBlock(mob.world, pos, s)) return true;
        try {
            if (b.isWood(mob.world, pos)) return true;
        } catch (Throwable ignored) {
        }
        ResourceLocation rl = b.getRegistryName();
        return rl != null && "dynamictrees".equals(rl.getNamespace());
    }

    Vec3d rotateY(Vec3d v, double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        return new Vec3d(v.x * cos - v.z * sin, 0, v.x * sin + v.z * cos);
    }

    /**
     * Find a reasonable Y at/near the given XZ by scanning down/up without loading chunks.
     */
    BlockPos findSurfaceOrWaterY(BlockPos guess) {
        final World world = mob.world;
        if (!isAreaLoaded(guess, 1)) return null;

        // 1. Base sample (1.12: returns the top *solid or liquid* at XZ)
        final BlockPos top = world.getTopSolidOrLiquidBlock(guess);

        // Quick hit: often top is already good.
        if (isSwimmable(top)) {
            return snapToWaterSurface(top);
        }
        if (isStandable(top)) {
            // If we're on ground (not swimming), avoid tree canopies
            if (!mob.isInWater()) {
                return adjustTreeTopToGround(top);
            }
            return top;
        }

        // 2. Small upward probe (bridges, slabs under ceilings, leaf canopies)
        final int actualMaxY = world.getActualHeight() - 1;
        BlockPos p = top;
        for (int i = 0; i < 3 && p.getY() < actualMaxY; i++) {
            p = p.up();
            if (isSwimmable(p)) {
                return snapToWaterSurface(p);
            }
            if (isStandable(p)) {
                // If p itself is a snow layer, treat feet as the AIR block above it.
                if (world.getBlockState(p).getBlock() == Blocks.SNOW_LAYER) {
                    BlockPos airAbove = p.up();
                    if (isAreaLoaded(airAbove, 1) && isStandable(airAbove)) return airAbove;
                }
                if (!mob.isInWater()) return adjustTreeTopToGround(p);
                return p;
            }
        }

        // 3. Deeper downward probe (valleys, shallow water tops, snow layers)
        p = top;
        for (int dy = 0; dy < 12 && p.getY() > 1; dy++) {
            p = p.down();
            if (isSwimmable(p)) {
                return snapToWaterSurface(p);
            }
            if (isStandable(p)) {
                if (!mob.isInWater()) {
                    return adjustTreeTopToGround(p);
                }
                return p;
            }
        }

        return null;
    }

    /**
     * Chunk-safe "isAreaLoaded" that never loads chunks.
     */
    boolean isAreaLoaded(BlockPos pos, int radius) {
        BlockPos from = pos.add(-radius, -radius, -radius);
        BlockPos to = pos.add(radius, radius, radius);

        // allowLoading = true → will load missing chunks
        return mob.world.isAreaLoaded(from, to, false);
    }

    boolean isNavigable(BlockPos feet) {
        return isStandable(feet) || isSwimmable(feet);
    }

    /**
     * “Can the mob stand with feet at `feet`?” (land version)
     */
    boolean isStandable(BlockPos feet) {
        World world = mob.world;


        if (!isBlockSafeLoaded(world, feet) ||
                !isBlockSafeLoaded(world, feet.up()) ||
                !isBlockSafeLoaded(world, feet.down())) {
            return false;
        }

        final IBlockState below = world.getBlockState(feet.down());
        final IBlockState atFeet = world.getBlockState(feet);
        final IBlockState atHead = world.getBlockState(feet.up());

        if (isLeafBlock(world, feet.down(), below)) return false;

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;
        if (!supportsStandingSurface(world, feet.down(), below)) return false;


        boolean feetOk =
                atFeet.getBlock() == Blocks.SNOW_LAYER
                        || atFeet.getMaterial().isReplaceable()
                        || atFeet.getCollisionBoundingBox(world, feet) == null;

        boolean headOk =
                atHead.getMaterial().isReplaceable()
                        || atHead.getCollisionBoundingBox(world, feet.up()) == null
                        || atHead.getBlock() == Blocks.SNOW_LAYER;

        return feetOk && headOk;
    }

    boolean isSwimmable(BlockPos feetPos) {
        World world = mob.world;
        if (!world.isBlockLoaded(feetPos) ||
                !world.isBlockLoaded(feetPos.up()) ||
                !world.isBlockLoaded(feetPos.down())) {
            return false;
        }

        IBlockState feet = world.getBlockState(feetPos);
        IBlockState head = world.getBlockState(feetPos.up());
        IBlockState below = world.getBlockState(feetPos.down());

        // Feet must be vanilla water
        if (!isVanillaWater(feet)) return false;

        // Absolutely forbid any non-water liquids / quicksand / lava
        if (isForbiddenLiquid(feet) || isForbiddenLiquid(head) || isForbiddenLiquid(below)) {
            return false;
        }

        // Head can be air OR water; just don't allow solid, non-replaceable stuff there.
        // solid block right in front of face → not a swimming cell
        return head.getMaterial().isReplaceable() || head.getMaterial().isLiquid();

        // If you really want to forbid "head in lava / mod fluids", that’s already covered by isForbiddenLiquid above.
    }

    // Forward component (in blocks) from 'from' to 'p' along the direction to the target (XZ only)
    double forwardComponent(BlockPos p, BlockPos from, Vec3d toDir) {
        Vec3d deltaXZ = new Vec3d(p.getX() + 0.5 - (from.getX() + 0.5), 0, p.getZ() + 0.5 - (from.getZ() + 0.5));
        return deltaXZ.dotProduct(toDir);
    }



    /**
     * Return true if the next 'probe' blocks in the travel direction are mostly water
     * (without loading chunks).
     */
    boolean isMostlyWaterAhead(BlockPos from, Vec3d dir, int probe, int minWater) {
        int wx = from.getX(), wy = from.getY(), wz = from.getZ();
        int waterCount = 0, samples = 0;

        for (int s = 2; s <= probe; s += 2) {
            int nx = wx + (int) Math.round(dir.x * s);
            int nz = wz + (int) Math.round(dir.z * s);
            BlockPos col = new BlockPos(nx, wy, nz);
            if (!isAreaLoaded(col, 1)) break; // don't load chunks

            BlockPos surf = findSurfaceOrWaterY(col);
            if (surf == null) continue;

            IBlockState feet = mob.world.getBlockState(surf);
            IBlockState below = mob.world.getBlockState(surf.down());

            boolean waterFeet = isVanillaWater(feet);
            boolean waterBelow = isVanillaWater(below);

            if (waterFeet || waterBelow) waterCount++;
            samples++;
        }
        return samples >= 6 && waterCount >= Math.min(samples, minWater);
    }

    /**
     * Try a water-favoring surface pick (keeps you in water lanes if present).
     */
    @Nullable
    BlockPos surfaceAtOffsetWater(BlockPos from, Vec3d dir, int s) {
        int nx = from.getX() + (int) Math.round(dir.x * s);
        int nz = from.getZ() + (int) Math.round(dir.z * s);
        BlockPos guess = new BlockPos(nx, from.getY(), nz);

        BlockPos surf = findSurfaceOrWaterY(guess);
        if (surf == null || !isAreaLoaded(surf, 1)) return null;

        if (isVanillaWater(mob.world.getBlockState(surf))) {
            BlockPos snapped = snapToWaterSurface(surf);
            if (snapped != null) surf = snapped;
        }

        if (isSwimmable(surf)) return surf;
        if (isNavigable(surf)) return surf;
        return null;
    }

    /**
     * True if feet cell is solid/walkable land (not liquid).
     */
    boolean isDryStandable(BlockPos feet) {
        return isStandable(feet) && !mob.world.getBlockState(feet).getMaterial().isLiquid();
    }

    /**
     * Try to get a land surface at/near XZ (without loading chunks).
     */
    @Nullable
    BlockPos findDrySurfaceNear(BlockPos guess) {
        BlockPos surf = findSurfaceOrWaterY(guess);
        if (surf == null) return null;
        if (isDryStandable(surf)) return surf;

        // small lateral nudge to hop off pond tiles (±1 in a plus pattern)
        final int[][] off = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : off) {
            BlockPos n = new BlockPos(surf.getX() + d[0], surf.getY(), surf.getZ() + d[1]);
            if (!isAreaLoaded(n, 1)) continue;
            if (isDryStandable(n)) return n;
        }
        return null;
    }

    /**
     * True if this block is likely part of a natural tree (log or leaf).
     * We still allow standing on wood floors for buildings – this is just a heuristic,
     * used only when we ask for "ground-ish" surface for waypoints.
     */
    boolean isTreeSupportBlock(World w, BlockPos pos, IBlockState s) {
        Block b = s.getBlock();

        // Vanilla & basic mod detection first
        if (b instanceof net.minecraft.block.BlockLog) return true;
        if (b instanceof net.minecraft.block.BlockLeaves) return true;

        // Millénaire / Modded overrides (instance methods)
        try {
            if (b.isLeaves(s, w, pos)) return true;
        } catch (Throwable ignored) {
        }

        try {
            if (b.isWood(w, pos)) return true;
        } catch (Throwable ignored) {
        }

        // Fallback on material
        Material m = s.getMaterial();
        return (m == Material.WOOD || m == Material.LEAVES);
    }

    /**
     * If 'standPos' is standing on a tree trunk/canopy, try to push it down to the
     * first "ground-like" solid below (dirt/stone/grass/sand/etc) with free headroom.
     * If nothing suitable is found, returns the original standPos.
     */
    BlockPos adjustTreeTopToGround(BlockPos standPos) {
        World w = mob.world;

        BlockPos below = standPos.down();
        if (!w.isBlockLoaded(below)) return standPos;

        IBlockState under = w.getBlockState(below);
        if (!isTreeSupportBlock(w, below, under)) {
            // Already standing on something non-tree-like
            return standPos;
        }

        // Scan downward looking for a "ground" support
        BlockPos p = below;
        for (int i = 0; i < 16 && p.getY() > 1; i++) {
            p = p.down();
            if (!w.isBlockLoaded(p) || !w.isBlockLoaded(p.up())) break;

            IBlockState ground = w.getBlockState(p);
            Material m = ground.getMaterial();

            // "Ground-ish" materials – tweak to taste
            if (!isNaturalGroundSupport(p)) continue;

            BlockPos feet = p.up();
            // reuse your nav test
            if (isStandable(feet)) {
                return feet;
            }
        }

        // No better ground found, keep the original
        return standPos;
    }

    boolean isPathBelow(BlockPos feet) {
        BlockPos below = feet.down();
        if (!mob.world.isBlockLoaded(below)) return false;
        IBlockState st = mob.world.getBlockState(below);
        return st.getBlock() instanceof IBlockPath;
    }

    // ---- Liquid filters: only vanilla water is allowed ----
    static boolean isVanillaWater(IBlockState s) {
        return TravellerBoats.isVanillaWater(s);
    }

    float yawFromDirection(Vec3d dir) {
        // Minecraft yaw: 0 = south (+Z), 90 = west (-X)
        // Standard conversion:
        // yaw = atan2(z, x) * 180/PI - 90
        double yawRad = Math.atan2(dir.z, dir.x);
        return (float) (yawRad * (180D / Math.PI) - 90.0D);
    }

    boolean isDryStandableForBoatExit(BlockPos feet) {
        World w = mob.world;

        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down()))
            return false;

        IBlockState below = w.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());

        boolean free =
                (atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(w, feet) == null)
                        && (atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(w, feet.up()) == null);

        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        boolean solidBelow =
                shape == BlockFaceShape.SOLID
                        || below.getBlock() == Blocks.SNOW_LAYER
                        || below.getBlock() == Blocks.GRASS_PATH
                        || below.getBlock() == Blocks.ICE
                        || below.getBlock() == Blocks.PACKED_ICE;

        return free && solidBelow;
    }

    /**
     * Returns true if the straight XZ corridor from 'from' to 'to' is "pure water":
     * - every sampled column has a water surface (top block is vanilla water)
     * - does not force-load chunks.
     */
    boolean isWaterCorridorClear(BlockPos from, BlockPos to) {
        World w = mob.world;

        double dx = to.getX() + 0.5 - (from.getX() + 0.5);
        double dz = to.getZ() + 0.5 - (from.getZ() + 0.5);
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1.0) return false;

        int steps = (int) Math.ceil(dist); // ~1 sample per block

        double stepx = dx / steps;
        double stepz = dz / steps;

        double x = from.getX() + 0.5;
        double z = from.getZ() + 0.5;

        for (int i = 1; i <= steps; i++) {
            int sx = MathHelper.floor(x + stepx * i);
            int sz = MathHelper.floor(z + stepz * i);
            BlockPos col = new BlockPos(sx, from.getY(), sz);

            // never load chunks
            if (!isAreaLoaded(col, 1)) return false;

            // Use your existing "findSurfaceOrWaterY" to find the top navigable cell
            BlockPos surf = findSurfaceOrWaterY(col);
            if (surf == null) return false;

            IBlockState s = w.getBlockState(surf);

            // corridor is only valid if the *surface* is vanilla water
            if (!isVanillaWater(s)) {
                return false; // solid/ice/etc. -> obstacle
            }
        }
        return true;
    }

    boolean isUndergroundNow() {
        BlockPos p = mob.getPosition();
        World w = mob.world;

        // If we can see sky, definitely not underground
        if (w.canSeeSky(p.up())) return false;

        // If skylight is still high, we're outdoors under leaves/foliage, not in a cave
        int sky = w.getLightFor(net.minecraft.world.EnumSkyBlock.SKY, p.up());
        if (sky >= 10) return false;

        // If the "roof" is close above us, it's probably a building/interior, not a cave
        BlockPos top = w.getTopSolidOrLiquidBlock(p);
        int roofDelta = top.getY() - p.getY();
        return roofDelta >= 8;

        // Otherwise: deep under something with low skylight -> cave-like
    }

    boolean isNaturalGroundSupport(BlockPos supportPos) {
        World w = mob.world;
        if (!w.isBlockLoaded(supportPos)) return false;

        IBlockState s = w.getBlockState(supportPos);
        Block b = s.getBlock();

        // Never tree blocks
        if (b instanceof net.minecraft.block.BlockLog) return false;
        if (b instanceof net.minecraft.block.BlockLeaves) return false;
        try {
            if (b.isWood(w, supportPos)) return false;
        } catch (Throwable ignore) {
        }
        try {
            if (b.isLeaves(s, w, supportPos)) return false;
        } catch (Throwable ignore) {
        }

        // Reject "construction-ish" blocks by class
        if (b instanceof net.minecraft.block.BlockStairs) return false;
        if (b instanceof net.minecraft.block.BlockSlab) return false;
        if (b instanceof net.minecraft.block.BlockFence) return false;
        if (b instanceof net.minecraft.block.BlockWall) return false;
        if (b instanceof net.minecraft.block.BlockGlass) return false;

        // Reject by registry name keyword (catches stonebrick, mod bricks, etc.)
        ResourceLocation rl = b.getRegistryName();
        if (rl != null) {
            String p = rl.getPath();
            if (p.contains("brick") || p.contains("stonebrick") || p.contains("stairs") || p.contains("slab")
                    || p.contains("plank") || p.contains("wood") || p.contains("log")
                    || p.contains("cobble") || p.contains("wall") || p.contains("fence")) {
                return false;
            }
        }

        // Allow only “natural-ish” materials
        Material m = s.getMaterial();
        return (m == Material.GRASS
                || m == Material.GROUND
                || m == Material.SAND
                || m == Material.ROCK
                || m == Material.CLAY
                || m == Material.SNOW
                || m == Material.ICE);
    }

    /**
     * Checks the direct walking path toward {@code waypoint} for immediate hazards:
     * forbidden liquids (lava, mod fluids) and cliff drops deeper than 3 blocks.
     * Samples at 1-block intervals, up to 4 steps. Returns false (unsafe) if any
     * hazard is found or a block is unloaded. Vanilla water is treated as safe ground
     * since it breaks falls. Trees and fences are handled by entity collision; this
     * method only guards against non-recoverable hazards.
     */
    boolean isDirectPathSafe(BlockPos waypoint) {
        Vec3d pos = mob.getPositionVector();
        double dx = waypoint.getX() + 0.5 - pos.x;
        double dz = waypoint.getZ() + 0.5 - pos.z;
        double horizLen = Math.sqrt(dx * dx + dz * dz);
        if (horizLen < 1e-6) return true;
        dx /= horizLen;
        dz /= horizLen;

        int mobY = MathHelper.floor(pos.y);
        int steps = Math.min(4, (int) Math.ceil(horizLen));

        for (int i = 1; i <= steps; i++) {
            int nx = MathHelper.floor(pos.x + dx * i);
            int nz = MathHelper.floor(pos.z + dz * i);

            BlockPos feet = new BlockPos(nx, mobY, nz);
            BlockPos head = feet.up();

            // Don't steer into unloaded territory
            if (!mob.world.isBlockLoaded(feet) || !mob.world.isBlockLoaded(head)) return false;

            // Lava or other forbidden liquids at feet/head level
            if (isForbiddenLiquid(mob.world.getBlockState(feet))) return false;
            if (isForbiddenLiquid(mob.world.getBlockState(head))) return false;

            // Cliff: require solid ground (or vanilla water) within 3 blocks below
            boolean hasSupport = false;
            for (int dy = 0; dy >= -3; dy--) {
                BlockPos check = new BlockPos(nx, mobY + dy, nz);
                if (!mob.world.isBlockLoaded(check)) { hasSupport = true; break; }
                IBlockState s = mob.world.getBlockState(check);
                if (isVanillaWater(s)) { hasSupport = true; break; }
                if (!s.getMaterial().isLiquid() && s.getMaterial().blocksMovement()) {
                    hasSupport = true;
                    break;
                }
            }
            if (!hasSupport) return false;
        }
        return true;
    }

    double feetYOnTopOf(BlockPos ground) {
        IBlockState st = mob.world.getBlockState(ground);
        AxisAlignedBB bb = st.getCollisionBoundingBox(mob.world, ground);
        if (bb == null) return ground.getY();          // no collision box => not standable
        return ground.getY() + bb.maxY;                // top surface height (handles snow layers!)
    }

    double teleportLandingY(BlockPos feetPos) {
        World w = mob.world;
        IBlockState atFeet = w.getBlockState(feetPos);

        // If we're standing "in" a thin block (snow layer), stand on top of it.
        if (atFeet.getBlock() == Blocks.SNOW_LAYER) {
            return feetYOnTopOf(feetPos); // uses that layer's collision height
        }

        // Normal case: feetPos is air/replaceable; stand on the block below.
        return feetYOnTopOf(feetPos.down());
    }

    double candidateLandingY(BlockPos feetPos) {
        return isSwimmable(feetPos) ? (feetPos.getY() + 0.02D) : teleportLandingY(feetPos);
    }

    boolean isAabbAreaLoaded(AxisAlignedBB aabb) {
        BlockPos min = new BlockPos(
                MathHelper.floor(aabb.minX),
                MathHelper.floor(aabb.minY),
                MathHelper.floor(aabb.minZ)
        );
        BlockPos max = new BlockPos(
                MathHelper.floor(aabb.maxX),
                MathHelper.floor(aabb.maxY),
                MathHelper.floor(aabb.maxZ)
        );
        return mob.world.isAreaLoaded(min, max, false); // false = never load
    }

    // If p is in vanilla water, move it up to the TOP of that water column (surface water block).
// Returns null if chunks aren't loaded or if head space is blocked.
    @Nullable
    BlockPos snapToWaterSurface(BlockPos p) {
        World w = mob.world;
        if (!isAreaLoaded(p, 1)) return null;

        BlockPos q = p;

        // If we're not actually in water, don't try to "surface" it.
        if (!isVanillaWater(w.getBlockState(q))) return q;

        int maxY = w.getActualHeight() - 1;

        // Climb up through water until the next block is NOT water
        while (q.getY() < maxY && isVanillaWater(w.getBlockState(q.up()))) {
            q = q.up();
            if (!isAreaLoaded(q, 1)) return null;
        }

        // Make sure there is space above (so the mob isn't trying to swim into a ceiling)
        BlockPos head = q.up();
        if (!w.isBlockLoaded(head)) return null;

        IBlockState headState = w.getBlockState(head);
        if (!headState.getMaterial().isReplaceable()
                && headState.getCollisionBoundingBox(w, head) != null) {
            return null;
        }

        return q;
    }

    boolean isCoveredGoal(BlockPos pos, int minCover) {
        if (pos == null || !mob.world.isBlockLoaded(pos)) return false;
        if (mob.world.canSeeSky(pos)) return false;

        BlockPos top = mob.world.getTopSolidOrLiquidBlock(new BlockPos(pos.getX(), 0, pos.getZ()));
        return top != null && (top.getY() - pos.getY()) >= minCover;
    }

    double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
