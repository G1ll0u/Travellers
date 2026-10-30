package com.jubitus.travellers.pathing;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The single answer to "is there a climbable step ahead, and how far is it" for a mounted jump -
 * shared by the reactive jump ({@code TravellerMountSupport}) and the A* planner
 * ({@code TravellerNodeProcessor}) so the two can never disagree about what a horse can clear.
 *
 * <p>Replaces the older single-column model (first solid block found, its unbroken top). That model
 * assumed a built wall: a clean vertical run with a flat top, starting right at foot level. Natural
 * stone rarely obliges - overhangs put the solid above foot level with nothing directly underfoot,
 * and a stepped or canyon-like face is several columns deep, not one. Both read to the old model as
 * "no obstacle here", even while the horse was demonstrably jammed against something.
 *
 * <p><b>Deliberately bounded, not a general heightmap.</b> This walks a handful of columns straight
 * ahead (the direction actually being steered) and stops at the first one that is either climbable
 * or too tall to bother looking past - it does not attempt to describe the terrain, only to answer
 * "can a jump get me past what's in front of me right now".
 */
public final class MountTerrainProfile {

    private MountTerrainProfile() {
    }

    /** The resolved standing height for a whole footprint - see {@link #fitFootprint}. */
    public static final class FootprintFit {
        /** The node cell for this fit: the first free cell at or above the surface, matching what
         * the old integer-only surface always produced (a fractional surface still lives in the
         * cell below the body's feet cell). */
        public final int y;
        /** The exact surface height the body's feet rest on - fractional for slabs, snow layers. */
        public final double surface;

        FootprintFit(double surface) {
            this.y = MathHelper.ceil(surface);
            this.surface = surface;
        }
    }

    /**
     * The per-node fix for the walled-in-by-uneven-terrain problem. Vanilla's own node model
     * ({@code NodeProcessor.getPathNodeType}, the multi-cell overload) tests every cell of a
     * footprint at one shared integer Y and rejects the whole node the instant a single cell
     * disagrees - fine for a built wall, hopeless for natural stone where a two-cell-wide body's
     * two columns almost never share an exact block height at once.
     *
     * <p>This asks each column of the footprint for its own real standing surface (see {@link
     * #standingSurfaceY}) independently and settles the body on the <em>highest</em> one - exactly
     * where a rigid AABB physically rests on uneven ground, however much the other columns read
     * below it. There is deliberately no "columns must agree" spread limit: an earlier version
     * required the spread to stay within one step, which refused nearly every 2x2 window on an
     * ordinary steep mountain flank (any window spanning a 2-block cross-slope change) and made
     * whole mountainsides unpathable that a horse walks fine one step at a time. What actually
     * bounds walkability is the <em>move</em>, not the stance: the settled height may not rise more
     * than {@code maxRise} nor drop more than {@code maxDrop} from {@code refY}, and every caller
     * follows up with a real-AABB sweep of the body's volume at the settled height, which catches
     * anything solid poking into it that per-column classification missed.
     *
     * @return the fit, or {@code null} when any column has nothing to stand on in range, the net
     *     rise exceeds {@code maxRise}, or the net drop exceeds {@code maxDrop}.
     */
    @Nullable
    public static FootprintFit fitFootprint(IBlockAccess world, int cornerX, int cornerZ, int sizeX, int sizeZ,
                                             int refY, double maxRise, double maxDrop, int headroomNeeded) {
        return fitFootprint(world, cornerX, cornerZ, sizeX, sizeZ, refY, maxRise, maxDrop, headroomNeeded, false);
    }

    /** {@code swim}: deep water counts as a surface at the water's top layer (see {@link #standingSurfaceY}). */
    @Nullable
    public static FootprintFit fitFootprint(IBlockAccess world, int cornerX, int cornerZ, int sizeX, int sizeZ,
                                             int refY, double maxRise, double maxDrop, int headroomNeeded,
                                             boolean swim) {
        double ceiling = refY + maxRise;
        double feetBound = refY - maxDrop;
        double best = Double.NaN;

        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                double s = standingSurfaceY(world, cornerX + dx, cornerZ + dz, feetBound, ceiling, headroomNeeded, swim);
                if (Double.isNaN(s)) return null; // a column with nothing to stand on at all - infeasible
                if (Double.isNaN(best) || s > best) best = s;
            }
        }

        if (Double.isNaN(best)) return null;
        if (best - refY > maxRise) return null;  // net rise is more than walking covers - leave it to the jump path
        if (refY - best > maxDrop) return null;
        return new FootprintFit(best);
    }

    /** A single column, found some number of steps ahead, that a jump could land on. */
    public static final class ClimbTarget {
        /** How many steps ahead of the origin this column is - 1 is the column directly in front. */
        public final int columnsAhead;
        /** The Y a body would stand at on this column's surface. */
        public final double landingY;

        ClimbTarget(int columnsAhead, double landingY) {
            this.columnsAhead = columnsAhead;
            this.landingY = landingY;
        }
    }

    /**
     * The topmost surface in this single column that a body could actually stand on: solid
     * directly below it, {@code headroomNeeded} free cells above. Scanned from the ceiling down -
     * not from the feet up - so a surface starting above foot level (an overhang's underside, a
     * ledge) is still found; the old up-from-feet scan required solid contact right at the feet and
     * missed exactly this case.
     *
     * <p><b>Water is fordable ground, up to {@code MOUNTS.mountedMaxFordDepth} deep.</b> A horse
     * wades a shallow stream on the bottom exactly like a player rides one - the standing surface is
     * the solid floor under the water. Deeper water is refused, except with {@code swim} (planner
     * nodes only): there the surface is the top water layer, so a mounted route may lead into a river
     * like a player riding in - the rider steps off once the horse swims and crosses on foot. Corridor
     * shortcuts and jump scans never swim. Lava anywhere in the scanned column refuses it outright.
     *
     * @return the standing Y, or {@code NaN} when nothing in range qualifies.
     */
    public static double standingSurfaceY(IBlockAccess world, int x, int z, double feet, double ceiling,
                                           int headroomNeeded) {
        return standingSurfaceY(world, x, z, feet, ceiling, headroomNeeded, false);
    }

    public static double standingSurfaceY(IBlockAccess world, int x, int z, double feet, double ceiling,
                                           int headroomNeeded, boolean swim) {
        int top = MathHelper.floor(ceiling);
        int bottom = MathHelper.floor(feet) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int waterTop = Integer.MIN_VALUE;

        for (int y = top; y >= bottom; y--) {
            pos.setPos(x, y, z);
            IBlockState state = world.getBlockState(pos);
            Material material = state.getMaterial();
            if (BlockRules.isHazard(state)) return Double.NaN;
            if (material == Material.WATER && waterTop == Integer.MIN_VALUE) waterTop = y;
            AxisAlignedBB bb = state.getCollisionBoundingBox(world, pos);
            if (bb == null) continue;

            if (waterTop != Integer.MIN_VALUE
                    && waterTop - y > TravellersConfig.MOUNTS.mountedMaxFordDepth) {
                return swim && waterTop >= feet ? waterTop : Double.NaN;
            }

            boolean clear = true;
            for (int c = 1; c <= headroomNeeded; c++) {
                pos.setPos(x, y + c, z);
                if (world.getBlockState(pos).getCollisionBoundingBox(world, pos) != null) {
                    clear = false;
                    break;
                }
            }
            // The real box top, not y + 1 - slabs and snow layers stand fractionally lower, and the
            // rise/step math downstream is honest only if the surface is.
            if (clear) return y + bb.maxY;
        }
        // Water deeper than the scanned window: no floor found at all.
        return swim && waterTop != Integer.MIN_VALUE && waterTop >= feet ? waterTop : Double.NaN;
    }

    /** Half a block: fine enough that a 1-wide pillar between two samples is impossible, coarse
     * enough that a 16-block probe stays around 30 iterations. */
    private static final double CORRIDOR_STEP = 0.5D;

    /**
     * True when a body of the given footprint can walk the straight line between two points,
     * following the terrain. The corridor counterpart of {@link #fitFootprint}, and deliberately
     * built on it: each half-block sample resolves the footprint's real standing surface under the
     * planner's own per-column rules (tolerant spread, ford-depth water, lava refusal), then proves
     * the body's full continuous volume is collision-free <em>at that surface</em>.
     *
     * <p>Sweeping from the resolved surface up - not from {@code ground + stepHeight} as the old
     * three-thin-lines test did - is what closes the leg-height blind zone: an obstacle occupying
     * only the first block above ground used to be exempted as "a step" even when it sat under one
     * flank only, and chords were approved through gaps the body then wedged in. Here a real step
     * raises the resolved surface itself, so the sweep follows the body up it, while anything the
     * footprint cannot actually settle on fails {@code fitFootprint} outright.
     *
     * <p>The cumulative-rise cap ({@code maxRise}, measured against the corridor's start height)
     * still applies: a chain of individually-legal steps adding up to a real climb is a slope to
     * walk node by node, not a line to charge.
     *
     * <p>{@code maxStepRise} is how much one sample may rise over the previous one - the mounted
     * caller passes the horse's own jump capability here, not its step height, because charging a
     * jumpable ledge in a straight line IS how a ridden horse takes it: the reactive jump
     * ({@code MountRiding.tickMountedJump}) fires on contact. Refusing those lines was
     * what kept handing mountain travel to the route planner at every 2-block step.
     *
     * <p>{@code stackClearance} is the rider's full silhouette above the ground
     * ({@code PathBody.clearanceHeight}). Between the body's own height and that, anything
     * <b>suffocating</b> (real solid - stone, dirt, logs) refuses the corridor outright: the horse
     * would fit under it but the rider's head would be dragged through it, which is exactly the
     * "rode into a 2-block passage and suffocated" report. Leaves and other non-suffocating cover
     * stay rideable-under - a rider ducks through a canopy, never through rock.
     */
    /** Why the last {@link #corridorClear} on this (server) thread refused: read right after the call, for profiling. */
    public static String lastRefusal = "";

    private static boolean refuse(String why) {
        lastRefusal = why;
        return false;
    }

    public static boolean corridorClear(World world, Vec3d from, Vec3d to, double halfWidth,
                                        double bodyHeight, double maxStepRise, double maxDrop,
                                        double maxRise, double stackClearance) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4D) return true;
        dx /= len;
        dz /= len;

        int headroom = MathHelper.ceil(bodyHeight);
        double startY = from.y;
        double surf = from.y;
        double prevX = from.x;
        double prevZ = from.z;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Walk to the end inclusive - the destination's own column is the one that matters most.
        for (double d = CORRIDOR_STEP; d < len + CORRIDOR_STEP; d += CORRIDOR_STEP) {
            double travelled = Math.min(d, len);
            double cx = from.x + dx * travelled;
            double cz = from.z + dz * travelled;

            int cornerX = MathHelper.floor(cx - halfWidth);
            int cornerZ = MathHelper.floor(cz - halfWidth);
            int sizeX = MathHelper.floor(cx + halfWidth) - cornerX + 1;
            int sizeZ = MathHelper.floor(cz + halfWidth) - cornerZ + 1;

            // Rounded, not floored: a fractional surface (snow, slab) floored down would bias the
            // whole rise/drop window half a block low.
            FootprintFit fit = fitFootprint(world, cornerX, cornerZ, sizeX, sizeZ,
                    MathHelper.floor(surf + 0.5D), maxStepRise, maxDrop, headroom);
            if (fit == null) return refuse("no footing");
            // INVARIANT: the rise is measured from the real surface; fitFootprint's rounded reference reads a
            // path block (15/16) to full block step of 1.06 as 1.0.
            if (fit.surface - surf > maxStepRise + 1.0E-3D) return refuse("step too high");
            if (fit.surface - startY > maxRise) return refuse("climb too long");

            AxisAlignedBB sweep = new AxisAlignedBB(
                    cx - halfWidth, fit.surface + 1.0E-3D, cz - halfWidth,
                    cx + halfWidth, fit.surface + bodyHeight - 1.0E-3D, cz + halfWidth);
            if (collidesWithBlocks(world, sweep)) return refuse("body hits blocks");
            // A step up: the body first rises in place at the previous sample (vanilla step-up).
            if (fit.surface > surf + 1.0E-3D && collidesWithBlocks(world, new AxisAlignedBB(
                    prevX - halfWidth, surf + bodyHeight + 1.0E-3D, prevZ - halfWidth,
                    prevX + halfWidth, fit.surface + bodyHeight - 1.0E-3D, prevZ + halfWidth))) {
                return refuse("no headroom to step up");
            }

            // The rider band: from the top of the body to the top of the stack, suffocating blocks
            // only. The body sweep above already caught everything with a collision box up to the
            // horse's height; this is specifically "would the RIDER be dragged through solid rock".
            int bandFrom = MathHelper.floor(fit.surface + bodyHeight);
            int bandTo = MathHelper.floor(fit.surface + stackClearance - 1.0E-3D);
            for (int y = bandFrom; y <= bandTo; y++) {
                for (int ox = 0; ox < sizeX; ox++) {
                    for (int oz = 0; oz < sizeZ; oz++) {
                        pos.setPos(cornerX + ox, y, cornerZ + oz);
                        if (world.getBlockState(pos).causesSuffocation()) return refuse("rider hits rock");
                    }
                }
            }

            surf = fit.surface;
            prevX = cx;
            prevZ = cz;
        }
        return true;
    }

    /**
     * {@code World.collidesWithAnyBlock} without its per-block Forge collision event: same cells
     * (one-block margin for fences and walls, unloaded columns skipped), same block boxes.
     */
    private static boolean collidesWithBlocks(World world, AxisAlignedBB box) {
        int minX = MathHelper.floor(box.minX) - 1;
        int maxX = MathHelper.ceil(box.maxX) + 1;
        int minY = MathHelper.floor(box.minY) - 1;
        int maxY = MathHelper.ceil(box.maxY) + 1;
        int minZ = MathHelper.floor(box.minZ) - 1;
        int maxZ = MathHelper.ceil(box.maxZ) + 1;
        java.util.List<AxisAlignedBB> hits = new java.util.ArrayList<>(2);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) {
                boolean edgeX = x == minX || x == maxX - 1;
                boolean edgeZ = z == minZ || z == maxZ - 1;
                if (edgeX && edgeZ) continue;
                if (!world.isBlockLoaded(pos.setPos(x, 64, z))) continue;
                for (int y = minY; y < maxY; y++) {
                    if ((edgeX || edgeZ) && y == maxY - 1) continue;
                    pos.setPos(x, y, z);
                    world.getBlockState(pos).addCollisionBoxToList(world, pos, box, hits, null, false);
                    if (!hits.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /**
     * The surface a body could stand on in the single column at ({@code x}, {@code z}), reachable
     * from {@code fromY} - within one {@code stepUp} up and {@code maxDrop} down - or {@code NaN}.
     * The direct-steer counterpart of a path node's ground lookup, under the same column rules as
     * everything else here (ford-depth water, lava refusal, real headroom).
     */
    public static double groundYNear(IBlockAccess world, double x, double z, double fromY,
                                     double stepUp, double maxDrop, int headroomNeeded) {
        double surface = standingSurfaceY(world, MathHelper.floor(x), MathHelper.floor(z),
                fromY - maxDrop, fromY + stepUp, headroomNeeded);
        if (Double.isNaN(surface)) return Double.NaN;
        return surface > fromY + stepUp + 1.0E-3D ? Double.NaN : surface;
    }

    /**
     * Walks forward up to {@code maxColumns} steps in the given (unit) direction, sampling each
     * column across {@code halfWidth} either side exactly as the single-column check used to,
     * looking for the nearest one a jump could land on.
     *
     * <p>Stops at the first column with anything solid in range: if it is within jump reach that is
     * the landing, and if it is not the search gives up right there rather than looking past it - a
     * wall too tall to reach is a wall to jump, not a wall to fly over, and any ledge sitting behind
     * it can't be reached first. Columns with nothing solid at all are skipped over freely: an empty
     * gap column ahead of a stepped face is exactly the case a single-column scan could not see past.
     *
     * @return the nearest climbable column, or {@code null} if none qualifies within {@code
     *     maxColumns} (either everything was empty, or the first solid thing found was out of reach).
     */
    public static ClimbTarget scanClimbColumns(IBlockAccess world, double originX, double originZ,
                                                double dirX, double dirZ, double halfWidth, double reachBase,
                                                double feet, double jumpHeight, double clearanceMargin,
                                                int maxColumns, int headroomNeeded) {
        double ceiling = feet + jumpHeight;
        double reachLimit = feet + jumpHeight - clearanceMargin;

        for (int col = 1; col <= maxColumns; col++) {
            double dist = reachBase + (col - 1);
            double best = Double.NaN;
            for (int side = -1; side <= 1; side++) {
                int bx = MathHelper.floor(originX + dirX * dist + (-dirZ) * halfWidth * side);
                int bz = MathHelper.floor(originZ + dirZ * dist + dirX * halfWidth * side);
                double surface = standingSurfaceY(world, bx, bz, feet, ceiling, headroomNeeded);
                if (Double.isNaN(surface)) continue;
                if (Double.isNaN(best) || surface > best) best = surface;
            }

            if (Double.isNaN(best)) continue; // nothing solid on this column - keep looking ahead
            if (best <= feet + 1.0E-3D) continue; // no real rise here, an ordinary step already covers it
            if (best > reachLimit) return null; // the nearest solid thing is already out of reach
            return new ClimbTarget(col, best);
        }
        return null;
    }
}
