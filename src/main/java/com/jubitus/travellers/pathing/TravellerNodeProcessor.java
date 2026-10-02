package com.jubitus.travellers.pathing;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLilyPad;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathNodeType;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.pathfinding.WalkNodeProcessor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.IBlockAccess;
import org.millenaire.common.block.IBlockPath;

import javax.annotation.Nullable;

public class TravellerNodeProcessor extends WalkNodeProcessor {


    private static final ResourceLocation PATH_DIRT_ID = new ResourceLocation("millenaire", "pathdirt");

    @Nullable
    private Block pathDirt;
    @Nullable
    private Block mpbPathBlock;
    @Nullable
    private Block mpbPathSlab;
    private boolean followerMode = false;

    /** The body this search is planning for, resolved once in {@link #init} - the horse while
     * mounted, else the entity itself. See {@link PathBody}. */
    private net.minecraft.entity.Entity body;
    private boolean bodyIsMount;
    /** Height the horse AND its rider would like free - preferred, never required. */
    private double stackClearance;
    /** Diagnostic only: plan as if the rider were on foot, to tell "a horse cannot fit through
     * there" apart from "this sizing is wrong". See TravellerNavigateGround.canReachOnFootSized. */
    private boolean ignoreMount;
    /** How high the bonded horse can actually jump, resolved once per search. 0 when on foot. */
    private double mountJumpHeight;
    /** Set by {@link #findMountedWalkOptions} for the node currently being expanded: which of the 4
     * {@link #CARDINAL_DIRS} indices already produced a plain walk candidate there, so
     * {@link #addMountHopCandidates} right after it doesn't re-scan the same direction for a climb -
     * see Task 4 of the mounted-follow perf plan. Only meaningful within one {@code findPathOptions}
     * call; each call to findMountedWalkOptions resets it for the new current node. */
    private final boolean[] mountedWalkableDir = new boolean[CARDINAL_DIRS.length];

    void setIgnoreMount(boolean value) {
        this.ignoreMount = value;
    }

    @Override
    public void init(IBlockAccess worldIn, EntityLiving entityIn) {
        super.init(worldIn, entityIn);
        // Per-search block-state cache: neighbouring candidate nodes/columns in one search overlap
        // heavily (a 2-cell footprint stepping one block over shares half its columns with the
        // previous node), so the same position is asked for repeatedly even against the already-cheap
        // ChunkCache snapshot init() receives. A fresh wrapper every call means there is nothing to
        // separately clear - the old one (and everything cached in it) is simply discarded. See Task 7
        // of the mounted-follow perf plan. Every read in this class goes through this.blockaccess
        // (directly, or passed to MountTerrainProfile's static helpers), so wrapping it here alone is
        // enough - no call site needs to change.
        this.blockaccess = new CachingBlockAccess(this.blockaccess);

        // super sized the search grid from the entity that asked for the path. Mounted, that is the
        // rider (0.6 wide -> a 1-cell corridor aimed down the line of block centres) while the body
        // that collides is the horse (1.4 -> a 2-cell footprint walking the seam between blocks).
        // Re-deriving the sizes from the real body is the whole correction: everything else in
        // WalkNodeProcessor reads these fields.
        //
        // Resolved once here rather than per node: init() runs once per path search while the node
        // scan below runs thousands of times, and answering "am I mounted" involves a DataParameter
        // read and a UUID compare.
        this.body = ignoreMount ? entityIn : PathBody.of(entityIn);
        this.bodyIsMount = this.body != entityIn;
        this.mountJumpHeight = 0.0D;
        if (this.bodyIsMount) {
            this.entitySizeX = MathHelper.floor(this.body.width + 1.0F);
            this.entitySizeZ = MathHelper.floor(this.body.width + 1.0F);
            // Only the horse's own height is a hard requirement. The rider on its back reaches
            // nearly a block higher, but demanding that of the pathfinder makes low woodland
            // unpathable outright - measured at 43% of a route with no path at all, ending in
            // detours and teleports - and it traps a rider that has already walked under a canopy,
            // since every neighbouring node then fails the same test the one it stands on would.
            // The rider's extra headroom is a cost instead (see findPathOptions), and its head is
            // protected by MountHazardGuard rather than by refusing to go there.
            this.entitySizeY = MathHelper.floor(this.body.height + 1.0F);
            this.stackClearance = PathBody.clearanceHeight(entityIn);
            this.mountJumpHeight = this.body instanceof net.minecraft.entity.passive.EntityHorse
                    ? com.jubitus.travellers.mount.TravellerMountSupport
                        .jumpHeightOf((net.minecraft.entity.passive.EntityHorse) this.body)
                    : 0.0D;
        }

        if (pathDirt == null) {
            pathDirt = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS.getValue(PATH_DIRT_ID);
        }
        if (mpbPathBlock == null && MPBIntegration.PRESENT) {
            mpbPathBlock = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS
                    .getValue(new net.minecraft.util.ResourceLocation("ultreia", "path_block"));
            mpbPathSlab = net.minecraftforge.fml.common.registry.ForgeRegistries.BLOCKS
                    .getValue(new net.minecraft.util.ResourceLocation("ultreia", "path_slab"));
        }
    }

    /**
     * The start node of a mounted search, aligned to the horse's real footprint. Vanilla's
     * {@code getStart()} derives it from the asking entity's own {@code BlockPos} - the rider's,
     * which is the horse's <em>centre</em> cell - while every mounted node in this processor means
     * the <em>min corner</em> of a 2-cell footprint. Depending on where the horse stands inside its
     * block, the true min corner is one cell lower, so up to half the time every search used to
     * start from a footprint the horse does not occupy. Two field symptoms follow directly: a start
     * whose misaligned cells clip an adjacent wall fails every expansion ("no path" on rideable
     * ground, read by the terrain-dismount diagnosis as "the footprint is the blocker" - a spurious
     * dismount), and the first path point aims the horse's centre up to a full block sideways of
     * where it stands - a sidle into clutter at the start of every leg.
     *
     * <p>The ideal corner is the one whose footprint centre is nearest the horse's actual centre;
     * when {@link MountTerrainProfile#fitFootprint} refuses it, the three neighbouring corner
     * windows still covering the body's occupied cells are tried - the footprint-sized mirror of
     * vanilla's own corner fallback. If nothing fits, the ideal corner is returned anyway and the
     * search fails honestly from there.
     */
    @Override
    public PathPoint getStart() {
        if (!this.bodyIsMount) return super.getStart();

        double off = PathBody.nodeCentreOffset(this.entity);
        double idealX = this.body.posX - off;
        double idealZ = this.body.posZ - off;
        int ix = MathHelper.floor(idealX + 0.5D);
        int iz = MathHelper.floor(idealZ + 0.5D);

        int iy = MathHelper.floor(this.body.getEntityBoundingBox().minY + 0.5D);
        if (this.body.isInWater()) {
            // Standing on a fordable stream bed: the feet cell is inside the water, which is exactly
            // where the ford rule expects the search to start. Only genuinely swimming (feet well
            // below the surface) has no valid start, and the search failing there is correct.
            iy = MathHelper.floor(this.body.getEntityBoundingBox().minY);
        }

        // The alternate corner on each axis: the other cell window still covering the body.
        int ax = idealX < ix ? ix - 1 : ix + 1;
        int az = idealZ < iz ? iz - 1 : iz + 1;
        int[][] candidates = {{ix, iz}, {ax, iz}, {ix, az}, {ax, az}};
        for (int[] c : candidates) {
            MountTerrainProfile.FootprintFit fit = MountTerrainProfile.fitFootprint(
                    this.blockaccess, c[0], c[1], this.entitySizeX, this.entitySizeZ,
                    iy, this.body.stepHeight, TravellersConfig.MOUNTS.mountedMaxStraightDrop,
                    this.entitySizeY);
            if (fit != null) return this.openPoint(c[0], fit.y, c[1]);
        }
        return this.openPoint(ix, iy, iz);
    }

    @Override
    public int findPathOptions(PathPoint[] options, PathPoint current, PathPoint target, float maxDistance) {
        int count = this.bodyIsMount
                ? findMountedWalkOptions(options, current, target, maxDistance)
                : super.findPathOptions(options, current, target, maxDistance);
        for (int i = 0; i < count; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            if (isImmediateHazard(feet) || isImmediateHazard(feet.down())) {
                options[i] = null;
                continue;
            }
            Block under = this.blockaccess.getBlockState(feet.down()).getBlock();
            if (under == Blocks.WATERLILY || under instanceof BlockLilyPad) {
                options[i] = null;
            }

        }
        if (this.body != null && !this.body.isInWater()) {
            // Low enough that paths through water are chosen when the land detour is long,
            // high enough that land routes are preferred when only a few blocks longer.
            float waterMalus = followerMode ? 1.5F : 2.5F;
            for (int i = 0; i < count; i++) {
                PathPoint p = options[i];
                if (p == null) continue;

                PathNodeType t = this.getPathNodeType(this.blockaccess, p.x, p.y, p.z);
                if (t == PathNodeType.WATER) {
                    int man = Math.abs(p.x - current.x) + Math.abs(p.z - current.z);
                    boolean isTarget = (p.x == target.x && p.y == target.y && p.z == target.z);
                    if (man <= 1 && !isTarget) {
                        p.costMalus += waterMalus;
                    }
                }
            }
        }


        // 4) compact after nulls
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (options[i] != null) {
                options[w++] = options[i];
            }
        }
// --- NEW: filter neighbors by TRUE step height (snow layers / fractional surfaces) ---
        double curSurfY = surfaceYForNode(current.x, current.y, current.z);
        double maxUp = (this.body != null ? this.body.stepHeight : 1.0F) + 1e-3;
        // A mounted candidate from findMountedWalkOptions already passed a footprint-aware rise check
        // (MountTerrainProfile.fitFootprint, sampling every column of the body). surfaceYForNode below
        // only ever samples the single corner column at (p.x, p.z), which on a two-cell footprint is
        // not necessarily the column fitFootprint judged the rise from - re-rejecting on that narrower
        // probe would silently undo the whole point of the tolerant check. The uphill cost malus below
        // is still worth keeping (it only ever adds cost, never a hard rejection).
        boolean skipHardRiseReject = this.bodyIsMount;

        for (int i = 0; i < w; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            double nextSurfY = surfaceYForNode(p.x, p.y, p.z);
            double rise = nextSurfY - curSurfY;

            if (rise > maxUp && !skipHardRiseReject) {
                options[i] = null;
                continue;
            }

            // Penalize uphill moves so travellers prefer flat terrain over mountains.
            // Path blocks (roads, village paths) are exempt so road-following is not disrupted.
            if (rise > 0.5) {
                BlockPos feet = new BlockPos(p.x, p.y, p.z);
                if (!isPathPreferred(feet)) {
                    p.costMalus += (float) Math.min(rise * 0.8, 2.5f);
                }
            }
        }

// compact again
        int w3 = 0;
        for (int i = 0; i < w; i++) {
            if (options[i] != null) {
                options[w3++] = options[i];
            }
        }
        w = w3;
/// --- 4b) Allow diagonals, but forbid "corner cutting" ---
// If moving diagonally, both side cardinals must be passable too.
// This prevents trying to squeeze between two diagonal solid blocks.
        int cx = current.x;
        int cy = current.y;
        int cz = current.z;

        // Only meaningful for a one-cell body. This rule inspects the single column each side of the
        // node, which is exactly the pair of cells a 0.6-wide body skims past on a diagonal - for a
        // two-cell footprint those are the wrong cells entirely, and vanilla's own diagonal rule
        // (both cardinal neighbours must be walkable, tested over the full footprint) is already
        // correct for that case.
        boolean checkCornerCutting = !this.bodyIsMount;

        for (int i = 0; checkCornerCutting && i < w; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            int dx = p.x - cx;
            int dz = p.z - cz;

            // Diagonal move?
            if (dx != 0 && dz != 0) {
                int stepX = Integer.signum(dx);
                int stepZ = Integer.signum(dz);

                // check the two "side" squares we would skim past
                // use a conservative Y check if your diagonal can be up/down
                int yCheck = Math.max(cy, p.y);

                boolean side1Ok = isCardinalColumnClear(cx + stepX, yCheck, cz);
                boolean side2Ok = isCardinalColumnClear(cx, yCheck, cz + stepZ);

                if (!side1Ok || !side2Ok) {
                    options[i] = null; // reject only the unsafe diagonal
                }
            }
        }


// Re-compact after killing unsafe diagonals
        int w2 = 0;
        for (int i = 0; i < w; i++) {
            if (options[i] != null) {
                options[w2++] = options[i];
            }
        }
        w = w2;

        // 4c) Low headroom under leaves/thin cover is a preference for a mounted rider, never a
        // refusal - the horse fits, the rider's head scrapes through something survivable
        // (MountHazardGuard). Low headroom under something genuinely solid is a different case: that
        // is not scraping through, it is riding under a ceiling too tight for the rider, and the
        // pathfinder must never choose it over going round - see classifyStackClearance. Always a
        // hard refusal, not a tunable: a "cost, never a ban" mode for this case can only ever mean
        // "sometimes route the rider's head through solid rock when every alternative costs more",
        // which is a correctness bug, not a legitimate preference to expose.
        if (this.bodyIsMount) {
            int w4 = 0;
            for (int i = 0; i < w; i++) {
                PathPoint p = options[i];
                if (p == null) continue;
                StackClearance clearance = classifyStackClearance(p.x, p.y, p.z);
                if (clearance == StackClearance.SOLID_BLOCKED) {
                    continue; // dropped, not carried into the compaction below
                }
                if (clearance != StackClearance.CLEAR) {
                    p.costMalus += (float) TravellersConfig.MOUNTS.mountLowHeadroomMalus;
                }
                options[w4++] = p;
            }
            w = w4;
        }

        // 5) path preference, using compacted count
        for (int i = 0; i < w; i++) {
            PathPoint p = options[i];
            if (p == null) continue;

            BlockPos feet = new BlockPos(p.x, p.y, p.z);
            if (isPathPreferred(feet)) {
                p.costMalus -= isMPBPath(feet) ? 1.2f : 0.75f;
            } else if (isPathNearby(feet)) {
                p.costMalus -= 0.5f;
            }
        }

        // 6) gap-jump candidates: lets the planner itself route across a jumpable gap (a wide
        // trench, a pond) instead of only ever detouring around one, or only reacting to a jump
        // opportunistically while blindly walking toward some other waypoint. Each candidate is
        // the exact landing GapJumpEvaluator would compute for that direction — when the entity
        // later walks the resulting path, TravellerNavigateGround's existing reactive gap-jump
        // fires naturally once it nears the edge, since its target is already that same landing.
        // Never while mounted: TravellerNavigateGround.evaluateJump refuses to fire a pedestrian gap
        // jump for a rider whose position comes from its horse, so routing a mounted rider across one
        // plans a step it can physically never take - it walks to the edge and stalls there.
        // 6b) Hop-over candidates: a mounted body may clear an obstacle one cell thick rather than
        // walk round it. Without this the planner only knows "walk, one block of rise at most", and
        // everything beyond that is left to the reactive jump - which by definition only fires once
        // the horse has already run into the thing. At walking pace that reads as a stumble; at any
        // real speed it reads as charging walls. It is also the only way a two-cell body gets past a
        // wall one block thick at all: it cannot stand astride one, so stepping over is out and going
        // over is the only route.
        if (this.bodyIsMount && this.mountJumpHeight > 1.0D
                && TravellersConfig.MOUNTS.mountedJumpEnabled
                && w < options.length - 4) {
            w = addMountHopCandidates(options, w, current, target, maxDistance);
        }

        if (TravellersConfig.GAP_JUMP.pathfinderAware && this.entity != null
                && !this.bodyIsMount
                && w < options.length - 4) {
            w = addGapJumpCandidates(options, w, current, target, maxDistance);
        }

        return w;
    }

    /** How much of the rider's own headroom this node leaves clear, worst cell wins. */
    private enum StackClearance {
        /** Nothing in the way. */
        CLEAR,
        /** Something with a collision box but not solid enough to suffocate a living entity in it -
         * leaves, chiefly. Survivable, so only ever a cost (see the call site). */
        SOFT_BLOCKED,
        /** Something that would actually suffocate a rider standing in it - a real ceiling. */
        SOLID_BLOCKED
    }

    /**
     * How much of the rider's extra headroom (beyond the horse's own body, already a hard
     * requirement elsewhere) this node leaves clear.
     *
     * <p>The leaves/solid distinction is exactly vanilla's own {@code IBlockState.causesSuffocation()}
     * - the same test {@code EntityLivingBase} uses to decide whether standing inside a block actually
     * hurts. Leaves already return {@code false} from it (see {@code BlockLeaves.causesSuffocation}),
     * which is what makes riding under a canopy survivable in the first place; a log, a slab of stone,
     * a solid roof does not. Reusing it here means the pathfinder's idea of "safe to ride under" can
     * never drift from what would actually damage the rider.
     */
    private StackClearance classifyStackClearance(int x, int y, int z) {
        int top = MathHelper.floor(y + this.stackClearance - 1.0E-3D);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        StackClearance worst = StackClearance.CLEAR;
        for (int cy = y; cy <= top; cy++) {
            for (int ox = 0; ox < this.entitySizeX; ox++) {
                for (int oz = 0; oz < this.entitySizeZ; oz++) {
                    pos.setPos(x + ox, cy, z + oz);
                    IBlockState state = this.blockaccess.getBlockState(pos);
                    if (state.getCollisionBoundingBox(this.blockaccess, pos) == null) continue;
                    if (state.causesSuffocation()) return StackClearance.SOLID_BLOCKED;
                    worst = StackClearance.SOFT_BLOCKED;
                }
            }
        }
        return worst;
    }

    private static final int[][] CARDINAL_DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private int addGapJumpCandidates(PathPoint[] options, int w, PathPoint current, PathPoint target, float maxDistance) {
        boolean debug = TravellersConfig.GAP_JUMP.debugGapJump;
        long worldTime = this.entity.world.getTotalWorldTime();
        for (int[] d : CARDINAL_DIRS) {
            GapJumpResult gap = GapJumpEvaluator.evaluate(
                    this.blockaccess, worldTime, current.x, current.y, current.z, d[0], d[1]);
            if (gap.type != GapJumpResult.Type.JUMPABLE) continue;

            PathPoint p = this.openPoint(gap.landCellX, gap.landCellY, gap.landCellZ);
            if (p.visited || p.distanceTo(target) >= maxDistance) {
                if (debug) {
                    net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                            "[Travellers/GapJump] node search: candidate at %d,%d,%d from %d,%d,%d REJECTED (visited=%b distToTarget=%.1f maxDist=%.1f)",
                            gap.landCellX, gap.landCellY, gap.landCellZ, current.x, current.y, current.z,
                            p.visited, p.distanceTo(target), maxDistance);
                }
                continue;
            }

            p.nodeType = PathNodeType.WALKABLE;
            p.costMalus = Math.max(p.costMalus, (float) TravellersConfig.GAP_JUMP.pathfinderJumpCostMalus);
            options[w++] = p;

            if (debug) {
                net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                        "[Travellers/GapJump] node search: candidate at %d,%d,%d from %d,%d,%d ADDED (gapWidth=%d)",
                        gap.landCellX, gap.landCellY, gap.landCellZ, current.x, current.y, current.z, gap.gapWidth);
            }
        }
        return w;
    }

    /**
     * Cardinal-only walk candidates for a mounted body, using {@link MountTerrainProfile#fitFootprint}
     * instead of vanilla's {@code WalkNodeProcessor} scan - this replaces the whole
     * same-height-everywhere requirement with a per-column tolerant one. See the class doc on {@code
     * fitFootprint} for why that tolerance is physically honest rather than a relaxation.
     *
     * <p><b>Deliberately no diagonals.</b> A diagonal step for a two-cell body risks clipping a corner
     * neither cardinal probe alone would catch, and vanilla's own corner-cutting rule (checked below,
     * elsewhere in this class) is built around a one-cell body's skim path - not this one's. What a
     * diagonal would have saved in route length, {@code TravellerNavigateGround.isBodyCorridorClear}
     * already recovers by letting the rider cut straight lines across whatever the resulting path
     * approximates in stair-steps.
     *
     * <p>Jump transitions are deliberately not generated here - {@link #addMountHopCandidates}, called
     * right after this in {@link #findPathOptions}, is the single place that decides whether a rise
     * this method refused is climbable instead. The two never have to agree on where "walk" ends and
     * "jump" begins: a rejection here simply means the direction has nothing to offer *by walking*.
     */
    private int findMountedWalkOptions(PathPoint[] options, PathPoint current, PathPoint target, float maxDistance) {
        int count = 0;
        java.util.Arrays.fill(this.mountedWalkableDir, false);
        for (int i = 0; i < CARDINAL_DIRS.length; i++) {
            int[] d = CARDINAL_DIRS[i];
            if (count >= options.length - 1) break;

            int nx = current.x + d[0];
            int nz = current.z + d[1];
            MountTerrainProfile.FootprintFit fit = MountTerrainProfile.fitFootprint(
                    this.blockaccess, nx, nz, this.entitySizeX, this.entitySizeZ,
                    current.y, this.body.stepHeight, TravellersConfig.MOUNTS.mountedMaxStraightDrop,
                    this.entitySizeY);
            if (fit == null) continue;

            // fitFootprint judges each column's standing height on its own - a per-cell
            // classification, exactly like vanilla's. It never verifies the real continuous volume
            // the body must occupy at that resting height is actually collision-free, and neither did
            // vanilla's own multi-cell node model (see class doc: same-height-everywhere, per cell).
            // Vanilla's OPEN-node branch (WalkNodeProcessor.getSafePoint) carries exactly this
            // real-AABB safety net for a one-cell body; a two-cell mounted one needs it just as much,
            // sized for its own real footprint and height instead. Without it a trace caught a horse
            // planted dead still against something for 25+ ticks on a path this generator had
            // approved - the plan was accepted, the physics refused it. That is the one failure this
            // whole system is never allowed to produce, so this check is not optional.
            if (this.entity != null) {
                net.minecraft.util.math.AxisAlignedBB bodyBox = new net.minecraft.util.math.AxisAlignedBB(
                        nx, fit.surface + 0.001D, nz,
                        nx + this.entitySizeX, fit.surface + this.body.height, nz + this.entitySizeZ);
                if (bodyCollidesWithTerrain(bodyBox)) continue;
            }

            PathPoint p = this.openPoint(nx, fit.y, nz);
            if (p.visited || p.distanceTo(target) >= maxDistance) continue;
            p.nodeType = PathNodeType.WALKABLE;
            options[count++] = p;
            this.mountedWalkableDir[i] = true;
        }
        return count;
    }

    /**
     * Body-vs-terrain collision test against this search's own {@link #blockaccess} snapshot,
     * instead of the live {@code World}. Mirrors vanilla's own {@code World.collidesWithAnyBlock} /
     * {@code Block.addCollisionBoxToList} idiom - offset each block-local collision box into world
     * space, test intersection - but reads the {@code ChunkCache} {@code PathNavigate.getPathToPos}
     * already built for the whole search instead of firing a live chunk lookup and
     * {@code ForgeEventFactory.gatherCollisionBoxes} per candidate node. Every cell this checks is
     * guaranteed loaded (that's the point of the cache), so this changes nothing about which cells
     * are actually solid.
     *
     * <p>This is a planning check, not the final word on whether a step is taken: the reactive
     * movement/jump code that actually walks the entity through the world still goes through the
     * real {@code World} and will refuse a step a Forge collision-modifying event would have
     * blocked. Skipping those events here only means this planner doesn't see them.
     */
    private boolean bodyCollidesWithTerrain(AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX);
        int maxX = MathHelper.floor(box.maxX);
        int minY = MathHelper.floor(box.minY);
        int maxY = MathHelper.floor(box.maxY);
        int minZ = MathHelper.floor(box.minZ);
        int maxZ = MathHelper.floor(box.maxZ);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.setPos(x, y, z);
                    IBlockState state = this.blockaccess.getBlockState(pos);
                    AxisAlignedBB blockBox = state.getCollisionBoundingBox(this.blockaccess, pos);
                    if (blockBox == null || blockBox == Block.NULL_AABB) continue;
                    if (blockBox.offset(pos).intersects(box)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Adds a landing node on the far side of the nearest column the horse could jump onto.
     *
     * <p>Looks up to {@code MOUNTS.mountedJumpLookaheadColumns} columns ahead via
     * {@link MountTerrainProfile}, not only the one cell directly next to {@code current} - a single
     * column reads an overhang, or a multi-cell-deep stepped/canyon face, as "nothing to jump", the
     * same limitation the reactive jump had. The obstacle's top must fit inside this individual
     * horse's own jump height, the landing must be walkable for the whole footprint, and the hop
     * carries a cost - scaled by how many columns it spans - so that walking round remains the
     * preferred answer whenever there is a way round.
     */
    private int addMountHopCandidates(PathPoint[] options, int w, PathPoint current, PathPoint target,
                                      float maxDistance) {
        double feet = surfaceYForNode(current.x, current.y, current.z);
        double half = this.body.width / 2.0D;

        for (int i = 0; i < CARDINAL_DIRS.length; i++) {
            if (w >= options.length - 1) break;
            // findMountedWalkOptions already opened a plain walk candidate this way - climbing is
            // only ever a useful alternative where walking was refused (see Task 4 of the mounted-
            // follow perf plan). Skipping the scan here changes nothing reachable, only the cost of
            // reconfirming a direction that already worked.
            if (this.mountedWalkableDir[i]) continue;
            int[] d = CARDINAL_DIRS[i];

            MountTerrainProfile.ClimbTarget climb = MountTerrainProfile.scanClimbColumns(
                    this.blockaccess, current.x + 0.5D, current.z + 0.5D, d[0], d[1], half, 1.0D,
                    feet, this.mountJumpHeight, JUMP_CLEARANCE_MARGIN,
                    TravellersConfig.MOUNTS.mountedJumpLookaheadColumns, this.entitySizeY);
            if (climb == null) continue;
            if (climb.landingY - feet <= this.body.stepHeight + 1.0E-3D) continue;

            int lx = current.x + d[0] * (climb.columnsAhead + 1);
            int lz = current.z + d[1] * (climb.columnsAhead + 1);
            // The landing footprint settles anywhere from the obstacle's top (the arc comes down on
            // the far side, it does not carry on climbing) down to a block below the takeoff -
            // judged by the same tolerant footprint model every walk candidate uses. The previous
            // version asked vanilla's same-height multi-cell scan instead, which natural terrain
            // almost never passes - so planned jumps effectively never existed in mountains, and a
            // route up a stepped face dead-ended at the first 2-block step the horse could have
            // cleared.
            int refY = MathHelper.floor(climb.landingY);
            MountTerrainProfile.FootprintFit fit = MountTerrainProfile.fitFootprint(
                    this.blockaccess, lx, lz, this.entitySizeX, this.entitySizeZ,
                    refY, 0.5D, refY - (current.y - 1), this.entitySizeY);
            if (fit == null) continue;
            if (this.entity != null) {
                net.minecraft.util.math.AxisAlignedBB bodyBox = new net.minecraft.util.math.AxisAlignedBB(
                        lx, fit.surface + 0.001D, lz,
                        lx + this.entitySizeX, fit.surface + this.body.height, lz + this.entitySizeZ);
                if (bodyCollidesWithTerrain(bodyBox)) continue;
            }

            PathPoint p = this.openPoint(lx, fit.y, lz);
            if (p.visited || p.distanceTo(target) >= maxDistance) continue;
            p.nodeType = PathNodeType.WALKABLE;
            p.costMalus = Math.max(p.costMalus,
                    (float) (TravellersConfig.MOUNTS.mountedJumpCostMalus * climb.columnsAhead));
            options[w++] = p;
        }
        return w;
    }

    /** Same margin the reactive jump requires - see {@code TravellerMountSupport.JUMP_CLEARANCE_MARGIN}. */
    private static final double JUMP_CLEARANCE_MARGIN = 0.2D;

    private double surfaceYForNode(int x, int y, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double best = y;

        for (int dy = 0; dy >= -2; dy--) {
            pos.setPos(x, y + dy, z);
            IBlockState st = this.blockaccess.getBlockState(pos);

            AxisAlignedBB bb = st.getBoundingBox(this.blockaccess, pos);
            if (bb == null || bb == Block.NULL_AABB) continue;

            double top = (y + dy) + bb.maxY;
            if (top > best) best = top;
        }
        return best;
    }

    private boolean isCardinalColumnClear(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);

        // liquids should block corner-cutting
        IBlockState sf = this.blockaccess.getBlockState(feet);
        if (sf.getMaterial().isLiquid()) return false;

        // feet/head must be empty-ish (no collision box)
        if (sf.getCollisionBoundingBox(this.blockaccess, feet) != null) return false;

        BlockPos head = feet.up();
        IBlockState sh = this.blockaccess.getBlockState(head);
        if (sh.getMaterial().isLiquid()) return false;
        if (sh.getCollisionBoundingBox(this.blockaccess, head) != null) return false;

        // Optional: prevent diagonals that skim a drop/hazard
        return !isGapOrHazardBelow(feet.down());
    }

    private boolean isAnyPathBlock(Block b) {
        if (b instanceof IBlockPath) return true;
        if (mpbPathBlock != null && b == mpbPathBlock) return true;
        if (mpbPathSlab != null && b == mpbPathSlab) return true;
        return false;
    }

    private boolean isPathPreferred(BlockPos feet) {
        return isAnyPathBlock(this.blockaccess.getBlockState(feet.down()).getBlock());
    }

    private boolean isMPBPath(BlockPos feet) {
        Block b = this.blockaccess.getBlockState(feet.down()).getBlock();
        return (mpbPathBlock != null && b == mpbPathBlock) || (mpbPathSlab != null && b == mpbPathSlab);
    }

    // check a 1-block ring for path (helps the path “lock on” to gates/roads)
    private boolean isPathNearby(BlockPos feet) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                Block b = this.blockaccess.getBlockState(
                        new BlockPos(feet.getX() + dx, feet.getY() - 1, feet.getZ() + dz)).getBlock();
                if (isAnyPathBlock(b)) return true;
            }
        }
        return false;
    }

    private boolean isGapOrHazardBelow(BlockPos below1) {
        IBlockState s1 = this.blockaccess.getBlockState(below1);
        Material m1 = s1.getMaterial();

        // liquids count as hazards
        if (m1 == Material.LAVA) return true;
        if (m1 == Material.WATER) return true;

        // 2-deep hole: below1 is air-like AND below2 is air-like
        if (s1.getCollisionBoundingBox(this.blockaccess, below1) != null) return false;

        BlockPos below2 = below1.down();
        IBlockState s2 = this.blockaccess.getBlockState(below2);
        Material m2 = s2.getMaterial();

        if (m2 == Material.LAVA) return true;
        if (m2 == Material.WATER) return true;

        return s2.getCollisionBoundingBox(this.blockaccess, below2) == null;
    }

    @Override
    protected PathNodeType getPathNodeTypeRaw(IBlockAccess worldIn, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState state = worldIn.getBlockState(pos);
        Block block = state.getBlock();

        // ... your existing hard blocks ...

        PathNodeType base = super.getPathNodeTypeRaw(worldIn, x, y, z);

        // A closed fence gate is vanilla PathNodeType.FENCE - an impassable wall the pathfinder
        // routes around, so a traveller whose only way out of an enclosure is a shut gate never
        // gets a path and ends up teleport-rescued. Travellers open gates exactly like doors
        // (EntityAIPassDoor), so on foot treat a closed gate as a closed wooden door: this
        // processor has canOpenDoors + canEnterDoors on, so getPathNodeType() downstream turns
        // DOOR_WOOD_CLOSED into WALKABLE. Left as FENCE for a mounted search - a horse cannot
        // work a latch, the mount system's on-foot LED handles that separately.
        if (!this.bodyIsMount
                && block instanceof net.minecraft.block.BlockFenceGate
                && !state.getValue(net.minecraft.block.BlockFenceGate.OPEN)) {
            return PathNodeType.DOOR_WOOD_CLOSED;
        }

        if (this.entity != null && this.body != null) {
            double mobFeetY = this.body.getEntityBoundingBox().minY;

            // ---- NEW: snow-layer fractional step check ----
            // If the target node is a snow layer, stepping onto it is "y + layers/8" not "y".
            if (base == PathNodeType.WALKABLE && block == Blocks.SNOW_LAYER) {
                int layers = snowLayersAt(state);          // 1..8
                double extra = layers / 8.0;               // 0.125..1.0
                double dyEff = (y + extra) - mobFeetY;     // <-- effective vertical step

                // Use actual stepHeight (yours is 1.0)
                if (dyEff > this.body.stepHeight + 1e-3) {
                    return PathNodeType.BLOCKED;
                }
            }

            // Keep your water-exit limiter (works fine)
            if (this.body.isInWater()) {
                double dy = y - mobFeetY;
                final double maxExitStep = Math.min(this.body.stepHeight, 0.8D);
                if (base == PathNodeType.WALKABLE && dy > maxExitStep) {
                    return PathNodeType.BLOCKED;
                }
            }
        }

        return base;
    }

    private static int snowLayersAt(IBlockState s) {
        if (s.getBlock() == Blocks.SNOW_LAYER) {
            try {
                return s.getValue(net.minecraft.block.BlockSnow.LAYERS);
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }


    public void setFollowerMode(boolean followerMode) {
        this.followerMode = followerMode;
    }
    private boolean isImmediateHazard(BlockPos pos) {
        IBlockState s = this.blockaccess.getBlockState(pos);
        Block b = s.getBlock();
        Material m = s.getMaterial();

        if (m == Material.LAVA) return true;
        if (b == Blocks.FIRE) return true;
        if (b == Blocks.MAGMA) return true; // if this version has it

        return false;
    }

    /**
     * Per-search {@link IBlockState} cache over an underlying {@link IBlockAccess} - see Task 7 of
     * the mounted-follow perf plan. Everything in this class reads terrain through
     * {@code this.blockaccess}, either directly or by passing it into {@link MountTerrainProfile}'s
     * static helpers, so wrapping the field once in {@link #init} is enough to cover every call site
     * without touching them individually.
     *
     * <p>Caches only {@code getBlockState} - the one call actually repeated across overlapping
     * candidate nodes/columns. Everything derived from a state ({@code getCollisionBoundingBox},
     * {@code causesSuffocation}...) is computed from the cached {@link IBlockState} itself and needs
     * no caching of its own. A fresh instance is created every {@link #init}, so there is nothing to
     * separately invalidate between searches - the old cache (and the {@code ChunkCache} it wrapped)
     * is simply dropped.
     */
    private static final class CachingBlockAccess implements IBlockAccess {
        private final IBlockAccess delegate;
        private final java.util.HashMap<Long, IBlockState> cache = new java.util.HashMap<>();

        CachingBlockAccess(IBlockAccess delegate) {
            this.delegate = delegate;
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            long key = pos.toLong();
            IBlockState cached = cache.get(key);
            if (cached != null) return cached;
            IBlockState state = delegate.getBlockState(pos);
            cache.put(key, state);
            return state;
        }

        @Nullable
        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
            return delegate.getTileEntity(pos);
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            return delegate.getCombinedLight(pos, lightValue);
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            return delegate.isAirBlock(pos);
        }

        @Override
        public net.minecraft.world.biome.Biome getBiome(BlockPos pos) {
            return delegate.getBiome(pos);
        }

        @Override
        public int getStrongPower(BlockPos pos, net.minecraft.util.EnumFacing direction) {
            return delegate.getStrongPower(pos, direction);
        }

        @Override
        public net.minecraft.world.WorldType getWorldType() {
            return delegate.getWorldType();
        }

        @Override
        public boolean isSideSolid(BlockPos pos, net.minecraft.util.EnumFacing side, boolean _default) {
            return delegate.isSideSolid(pos, side, _default);
        }
    }
}