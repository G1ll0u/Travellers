package com.jubitus.travellers.utils.world.pathing;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLiving;
import net.minecraft.pathfinding.PathFinder;
import net.minecraft.pathfinding.PathNavigateGround;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class TravellerNavigateGround extends PathNavigateGround {
    private TravellerNodeProcessor travellerProc;
    private PathFinder travellerFinder;
    // --- Gap jump state ---
    private int gapJumpTicks = 0;
    private Vec3d gapJumpDir = Vec3d.ZERO;
    private double gapJumpSpeed = 0.0;
    private double gapLandX = 0.0;
    private double gapLandZ = 0.0;
    private int stuckTicks = 0;
    private double stuckX = 0, stuckZ = 0;
    // --- Narrow-landing assist ---
    private boolean gapLandingIsNarrow = false;
    private int landCenterTicks = 0;
    private double landCenterX = 0.0;
    private double landCenterZ = 0.0;
    private int gapEtaTicks = 0; // how many ticks we EXPECT until we reach landing plane
    private boolean followerMode = false;
    private int losSkipCooldown = 0;
    private int losSkipInterval = 4;

    private Vec3d smoothedSteerDir = Vec3d.ZERO;
    private double lastSafeMoveX = Double.NaN;
    private double lastSafeMoveY = Double.NaN;
    private double lastSafeMoveZ = Double.NaN;

    public TravellerNavigateGround(EntityLiving entity, World worldIn) {
        super(entity, worldIn);
    }

    private static double horizontalDistanceFromInitialSpeed(double v0, int ticks) {
        double drag = 0.91;
        double sum = 0.0;
        double v = v0;
        for (int i = 0; i < ticks; i++) {
            sum += v;
            v *= drag;
        }
        return sum;
    }

    // Find the minimum initial horizontal speed needed to travel 'dist' blocks in 'ticks' airtime.
    private static double solveRequiredHorizontalSpeed(double dist, int ticks) {
        double lo = 0.0;
        double hi = 2.0;
        for (int it = 0; it < 30; it++) {
            double mid = (lo + hi) * 0.5;
            if (horizontalDistanceFromInitialSpeed(mid, ticks) >= dist) hi = mid;
            else lo = mid;
        }
        return hi;
    }

    // Simulate vanilla-ish jump airtime for vY=0.42 in 1.12.2
    private static int computeJumpFlightTicks(double deltaY) {
        double y = 0.0;
        double vy = 0.42;
        int t = 0;

        double maxY = 0.0;

        while (t < 200) { // was 60
            y += vy;
            if (y > maxY) maxY = y;

            vy = (vy - 0.08) * 0.98;
            t++;

            // If landing is ABOVE what we can ever reach, fail early
            if (deltaY > 0.0 && t > 5 && maxY + 1e-3 < deltaY) return -1;

            // “land” when we’re descending and have dropped to the landing plane
            if (t > 1 && vy < 0.0 && y <= deltaY) break;
        }

        return t;
    }

    public void setFollowerMode(boolean followerMode) {
        this.followerMode = followerMode;

        // make sure processor exists
        if (travellerProc != null) {
            travellerProc.setFollowerMode(followerMode);
        }
    }

    @Override
    protected PathFinder getPathFinder() {
        // IMPORTANT: lazy init because PathNavigate constructors may call this early
        if (travellerProc == null) {
            travellerProc = new TravellerNodeProcessor();
            travellerProc.setCanEnterDoors(true);
            travellerProc.setCanOpenDoors(true);
            travellerProc.setCanSwim(true);

            this.nodeProcessor = travellerProc;
            travellerFinder = new PathFinder(travellerProc);
        }
        return travellerFinder;
    }

    @Override
    protected Vec3d getEntityPosition() {
        // Important: use real feet Y, not rounded
        return new Vec3d(this.entity.posX, this.entity.getEntityBoundingBox().minY, this.entity.posZ);
    }

    // --- NEW: compute the true surface Y for a path node (handles snow layers) ---
    private double surfaceYForNode(int x, int y, int z) {
        // In vanilla, node y is typically the "feet cell". The floor is usually in y-1,
        // but snow layers can be in y itself.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        double best = y; // fallback

        // Check y (snow layer can be here), y-1 (normal ground), and y-2 (safety)
        for (int dy = 0; dy >= -2; dy--) {
            pos.setPos(x, y + dy, z);
            IBlockState st = this.world.getBlockState(pos);

            AxisAlignedBB bb = st.getCollisionBoundingBox(this.world, pos);
            if (bb == null) continue;

            double top = (y + dy) + bb.maxY;
            if (top > best) best = top;
        }

        return best;
    }


    // Sum of horizontal displacement with air drag factor per tick.
// In air, horizontal motion is damped each tick. 0.91 is a decent approximation.

    private Vec3d vecForPathPoint(PathPoint p) {
        BlockPos pos = new BlockPos(p.x, p.y, p.z);

        if (this.world.getBlockState(pos).getMaterial() == Material.WATER) {
            // Aim mid-block so we don't keep trying to climb to the air block above
            return new Vec3d(p.x + 0.5D, p.y + 0.5D, p.z + 0.5D);
        }

        double y = surfaceYForNode(p.x, p.y, p.z);
        return new Vec3d(p.x + 0.5D, y, p.z + 0.5D);
    }

    @Override
    protected void pathFollow() {
        if (landCenterTicks > 0 && this.entity.onGround && gapJumpTicks == 0) {
            this.entity.getMoveHelper().setMoveTo(landCenterX, this.entity.posY, landCenterZ, this.speed);
            landCenterTicks--;
            return;
        }

        if (gapJumpTicks > 0) {
            if (this.entity.isInWater()) {
                gapJumpTicks = 0;
                this.entity.motionX *= 0.2;
                this.entity.motionZ *= 0.2;
                return;
            }

            int ticksLeft = Math.max(1, gapEtaTicks > 0 ? gapEtaTicks : 1);

            this.entity.getMoveHelper().setMoveTo(this.entity.posX, this.entity.posY, this.entity.posZ, 0.0);
            this.entity.moveForward = 0.0F;
            this.entity.moveStrafing = 0.0F;
            this.entity.setAIMoveSpeed(0.0F);

            double rx = gapLandX - this.entity.posX;
            double rz = gapLandZ - this.entity.posZ;
            double remaining = Math.sqrt(rx * rx + rz * rz);

            if (remaining < 0.35 && this.entity.motionY < 0.0) {
                // final approach clamp handled below
            }

            double len = Math.sqrt(rx * rx + rz * rz);
            if (len > 1e-6) {
                gapJumpDir = new Vec3d(rx / len, 0, rz / len);
            }

            double drag = 0.91;
            double denom = 1.0 - Math.pow(drag, ticksLeft);
            double neededV0 = denom < 1e-6 ? remaining : remaining * (1.0 - drag) / denom;

            double desiredSpeed = Math.min(neededV0, 1.55);
            desiredSpeed = Math.max(desiredSpeed, 0.08);

            if (gapLandingIsNarrow && this.entity.motionY < 0.0 && remaining < 0.85) {
                desiredSpeed = Math.min(desiredSpeed, remaining * 0.45);
                desiredSpeed = Math.max(desiredSpeed, 0.06);
            }

            double dragComp = 0.91;
            this.entity.motionX = gapJumpDir.x * (desiredSpeed / dragComp);
            this.entity.motionZ = gapJumpDir.z * (desiredSpeed / dragComp);

            gapJumpTicks--;
            if (gapEtaTicks > 0) gapEtaTicks--;

            if (this.entity.onGround) {
                this.entity.motionX = 0.0;
                this.entity.motionZ = 0.0;
                gapJumpTicks = 0;
                forceGapJumpIfNeeded();
            }
            return;
        }

        antiStuckTick();
        if (this.currentPath == null || this.currentPath.isFinished()) {
            return;
        }

        int idx = this.currentPath.getCurrentPathIndex();
        int pathLen = this.currentPath.getCurrentPathLength();

        double reach = this.entity.width > 0.75F
                ? this.entity.width / 2.0F
                : 0.75D - this.entity.width / 2.0D;

        for (int i = idx; i < pathLen; i++) {
            PathPoint pp = this.currentPath.getPathPointFromIndex(i);
            Vec3d v = vecForPathPoint(pp);

            double dx = Math.abs(this.entity.posX - v.x);
            double dz = Math.abs(this.entity.posZ - v.z);

            if (dx < reach && dz < reach) {
                this.currentPath.setCurrentPathIndex(i + 1);
            } else {
                break;
            }
        }

        if (this.currentPath.isFinished()) return;

        Vec3d entityPos = this.getEntityPosition();

        if (followerMode && !shouldDoHeavyPathUpdatesThisTick()) {
            if (this.currentPath.isFinished()) return;

            int curIndex = this.currentPath.getCurrentPathIndex();
            PathPoint cur = this.currentPath.getPathPointFromIndex(curIndex);
            Vec3d target = vecForPathPoint(cur);

            Vec3d desired = new Vec3d(target.x - this.entity.posX, 0.0, target.z - this.entity.posZ);
            double len = desired.length();

            if (len > 1.0e-6) {
                desired = new Vec3d(desired.x / len, 0.0, desired.z / len);

                if (smoothedSteerDir.length() < 1.0e-6) {
                    smoothedSteerDir = desired;
                } else {
                    smoothedSteerDir = blendDir(smoothedSteerDir, desired, 0.35);
                }

                double dist = Math.sqrt(
                        (target.x - this.entity.posX) * (target.x - this.entity.posX) +
                                (target.z - this.entity.posZ) * (target.z - this.entity.posZ)
                );

                double step = Math.min(dist, 1.25);
                double mx = this.entity.posX + smoothedSteerDir.x * step;
                double mz = this.entity.posZ + smoothedSteerDir.z * step;

                if (this.entity.isInWater()) {
                    setSafeMoveTo(mx, this.entity.posY, mz, this.speed);
                    if (this.entity.collidedHorizontally) {
                        this.entity.getJumpHelper().setJumping();
                    }
                } else {
                    setSafeMoveTo(mx, target.y, mz, this.speed);
                }
            } else {
                setSafeMoveTo(target.x, target.y, target.z, this.speed);
            }
            return;
        }

        if (losSkipCooldown > 0) {
            losSkipCooldown--;
        } else {
            int curIdx = this.currentPath.getCurrentPathIndex();
            int remaining = this.currentPath.getCurrentPathLength() - curIdx;

            int lookahead;
            if (remaining >= 10) lookahead = 5;
            else if (remaining >= 6) lookahead = 4;
            else lookahead = 2;

            int maxIdx = Math.min(curIdx + lookahead, this.currentPath.getCurrentPathLength() - 1);

            int w = MathHelper.ceil(this.entity.width);
            int h = MathHelper.ceil(this.entity.height);

            for (int j = maxIdx; j > curIdx + 1; j--) {
                PathPoint pp = this.currentPath.getPathPointFromIndex(j);
                Vec3d v = vecForPathPoint(pp);

                if (this.isDirectPathBetweenPoints(entityPos, v, w, h, w)) {
                    this.currentPath.setCurrentPathIndex(j);
                    break;
                }
            }

            losSkipCooldown = followerMode ? 6 : 3;
        }

        if (this.currentPath.isFinished()) return;

        forceGapJumpIfNeeded();
        if (gapJumpTicks > 0) return;

        int curIndex = this.currentPath.getCurrentPathIndex();
        PathPoint cur = this.currentPath.getPathPointFromIndex(curIndex);
        Vec3d target = vecForPathPoint(cur);

        PathPoint prev = (curIndex > 0) ? this.currentPath.getPathPointFromIndex(curIndex - 1) : null;

        if (prev != null) {
            int dx = cur.x - prev.x;
            int dz = cur.z - prev.z;
            int manhattan = Math.abs(dx) + Math.abs(dz);

            if (manhattan >= 2 && (dx == 0 || dz == 0)) {
                BlockPos takeoff = findTakeoffBeforeGap(prev, cur);
                int sx = Integer.signum(cur.x - prev.x);
                int sz = Integer.signum(cur.z - prev.z);

                double edgeX = takeoff.getX() + 0.5 + sx * 0.45;
                double edgeZ = takeoff.getZ() + 0.5 + sz * 0.45;

                Vec3d to = new Vec3d(
                        edgeX,
                        surfaceYForNode(takeoff.getX(), takeoff.getY(), takeoff.getZ()),
                        edgeZ
                );

                forceGapJumpIfNeeded();
                if (gapJumpTicks > 0) return;

                this.entity.getMoveHelper().setMoveTo(to.x, to.y, to.z, this.speed);
                return;
            }
        }

        if (this.entity.isInWater()) {
            this.entity.getMoveHelper().setMoveTo(target.x, this.entity.posY, target.z, this.speed);

            if (this.entity.collidedHorizontally) {
                this.entity.getJumpHelper().setJumping();
            }
            return;
        }

        this.entity.getMoveHelper().setMoveTo(target.x, target.y, target.z, this.speed);
    }

    private void forceGapJumpIfNeeded() {
        if (this.currentPath == null) return;
        if (this.currentPath.isFinished()) return;
        if (!this.entity.onGround) return;
        if (gapJumpTicks > 0) return;

        int i = this.currentPath.getCurrentPathIndex();
        PathPoint next = this.currentPath.getPathPointFromIndex(i);
        PathPoint prev = (i > 0) ? this.currentPath.getPathPointFromIndex(i - 1) : null;
        if (prev == null) return;

        int dx = next.x - prev.x;
        int dz = next.z - prev.z;

        // must be a long edge (2..4 blocks away)
        int manhattan = Math.abs(dx) + Math.abs(dz);
        if (manhattan < 2) return;

        // only cardinal (your jump edges are cardinal)
        if (dx != 0 && dz != 0) return;

        // Compute the REAL takeoff block: last solid before the gap
        BlockPos takeoff = findTakeoffBeforeGap(prev, next);
// IMPORTANT: use actual mob position at the instant we fire the jump.
// (Using block centers causes consistent undershoot/overshoot on 1-block landings.)
        double takeX = this.entity.posX;
        double takeZ = this.entity.posZ;


        // Also require that the block *in front of takeoff* is actually a gap/hazard below
        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        gapLandingIsNarrow = isNarrowLanding(next.x, next.y, next.z, sx, sz);

// landing center
        double landX = next.x + 0.5;
        double landZ = next.z + 0.5;

        double aimX, aimZ;
        BlockPos landFeet = new BlockPos(next.x, next.y, next.z);

        boolean landingWater = this.world.getBlockState(landFeet).getMaterial() == Material.WATER;

        if (landingWater) {
            // Aim into the water (center + small forward) so we CLEAR the lip and actually enter the water block.
            double forward = 0.12; // 0.08..0.18
            aimX = landX + sx * forward;
            aimZ = landZ + sz * forward;
        } else {
            double back = gapLandingIsNarrow ? 0.34 : 0.14;
            aimX = landX - sx * back;
            aimZ = landZ - sz * back;
        }
        // require we're actually at the EDGE (player-like), not "near the center"
        if (!roughlyCenteredOnTakeoff(takeoff, sx, sz)) return;

        boolean okEdge = atTakeoffEdge(takeoff, sx, sz)
                || nearTakeoffEdgePoint(takeoff, sx, sz)
                || willCrossTakeoffEdgeNextTick(takeoff, sx, sz);

        if (!okEdge) return;


        BlockPos frontBelow = takeoff.add(sx, -1, sz);
        if (!isGapOrHazardBelowWorld(frontBelow)) return;


        // ---- NEW: runtime safety check (prevents jumping if water/blocks could disturb) ----

// takeoff must be dry and have headroom
        BlockPos takeFeet = new BlockPos(takeoff.getX(), prev.y, takeoff.getZ());
        if (!isPassThrough(takeFeet) || !isPassThrough(takeFeet.up())) return;
        if (isLiquid(takeFeet) || isLiquid(takeFeet.up())) return;

// landing must be dry, passable at feet/head, and have solid support below


        if (landingWater) {
            // must not be lava
            if (this.world.getBlockState(landFeet).getMaterial() != Material.WATER) return;

            // must have headroom-ish (no solid block right above water cell)
            if (!isPassThrough(landFeet.up()) && !isLiquid(landFeet.up())) return;

        } else {
            // normal solid landing rules
            if (!isPassThrough(landFeet) || !isPassThrough(landFeet.up())) return;
            if (isLiquid(landFeet) || isLiquid(landFeet.up())) return;
            if (!isSolidSupport(landFeet.down())) return;
        }


// the “air corridor” between takeoff and landing must be clear (no water, no blocks)
        int distBlocks = Math.abs(next.x - takeoff.getX()) + Math.abs(next.z - takeoff.getZ());
        for (int step = 1; step < distBlocks; step++) {
            BlockPos midFeet = takeoff.add(sx * step, 0, sz * step);
            if (!isPassThrough(midFeet) || !isPassThrough(midFeet.up())) {
                return;
            }
        }


        double jx = aimX - takeX;
        double jz = aimZ - takeZ;

        double dist = Math.sqrt(jx * jx + jz * jz);

        if (dist < 0.6) return; // too close / noisy
        Vec3d dir = new Vec3d(jx / dist, 0, jz / dist);

        double takeY = this.entity.getEntityBoundingBox().minY;

// landing “plane”: for solid use surface, for water use top of water block
        double landPlaneY = landingWater
                ? (next.y + 1.0) // top surface of water block
                : surfaceYForNode(next.x, next.y, next.z);

        double deltaY = landPlaneY - takeY;

        int flightTicks = computeJumpFlightTicks(deltaY);
        if (flightTicks <= 0) return; // can't reach that height


        // aim a bit short of the center for safety
        double desiredDist;
        if (landingWater) {
            // For water we want to GUARANTEE crossing into the block, not undershoot.
            desiredDist = dist + 0.16;   // 0.06..0.14
        } else {
            desiredDist = dist - (gapLandingIsNarrow ? 0.02 : 0.06);
        }

        double v0 = solveRequiredHorizontalSpeed(desiredDist, flightTicks);

        if (landingWater) {
            v0 *= 1.12;                 // a bit more margin than before
            v0 = Math.min(v0, 1.35);    // raise cap so 2-block hops can actually reach
        } else {
            v0 *= 1.12;
            if (dist >= 3.0) v0 *= 1.06;
            v0 = Math.min(v0, 1.55);
        }


        // Fire the jump
        this.entity.motionY = 0.42D;
        this.entity.isAirBorne = true;

        this.entity.motionX = dir.x * v0;
        this.entity.motionZ = dir.z * v0;

        // Keep pushing until landing (extra ticks so 3-wide always has time)
// For big drops (especially into water), keep control longer.
// We will end early onGround / isInWater anyway.
// lock steering for long enough (prevents mid-air path steering on big drops)
        int minHold = (landingWater || deltaY < -2.0) ? 120 : 40;
        this.gapJumpTicks = Math.max(flightTicks + 8, minHold);

// ETA is ONLY for speed math (should match actual landing time, not minHold!)
        this.gapEtaTicks = flightTicks + 2;

        this.gapJumpDir = dir;
        this.gapJumpSpeed = v0;
        this.gapLandX = aimX;
        this.gapLandZ = aimZ;

        // Small lateral correction toward landing center (helps 1-wide strips)
        double corr = 0.04; // small, safe
        double cx = landX - this.entity.posX;
        double cz = landZ - this.entity.posZ;
        double clen = Math.sqrt(cx * cx + cz * cz);
        if (clen > 1e-6) {
            this.entity.motionX += (cx / clen) * corr;
            this.entity.motionZ += (cz / clen) * corr;
        }
        if (gapLandingIsNarrow) {
            landCenterTicks = 3;
            landCenterX = landX;
            landCenterZ = landZ;
        } else {
            landCenterTicks = 0;
        }

    }

    private boolean isGapOrHazardBelowWorld(BlockPos below) {
        IBlockState s = this.world.getBlockState(below);
        Material m = s.getMaterial();

        if (m == Material.LAVA) return true;
        if (m == Material.WATER) return true;

        // hole if no collision
        return s.getCollisionBoundingBox(this.world, below) == null;
    }

    private BlockPos findTakeoffBeforeGap(PathPoint prev, PathPoint next) {
        int dx = next.x - prev.x;
        int dz = next.z - prev.z;

        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        // cardinal only (your jump neighbors are cardinal)
        int dist = Math.abs(dx) + Math.abs(dz);
        if (sx != 0 && sz != 0) return new BlockPos(prev.x, prev.y, prev.z); // ignore diagonals

        // scan along the segment until we see the first "gap under", then takeoff is the block BEFORE it
        for (int i = 1; i < dist; i++) {
            int x = prev.x + sx * i;
            int z = prev.z + sz * i;

            BlockPos below = new BlockPos(x, prev.y - 1, z);

            if (isGapOrHazardBelowWorld(below)) {
                int tx = prev.x + sx * (i - 1);
                int tz = prev.z + sz * (i - 1);
                return new BlockPos(tx, prev.y, tz);
            }
        }

        // no gap detected in-between
        return new BlockPos(prev.x, prev.y, prev.z);
    }

    private boolean isLiquid(BlockPos p) {
        return this.world.getBlockState(p).getMaterial().isLiquid();
    }

    private boolean isPassThrough(BlockPos p) {
        IBlockState s = this.world.getBlockState(p);
        if (s.getMaterial().isLiquid()) return false; // NEW: treat liquid as blocking for jumps
        return s.getMaterial().isReplaceable() || s.getCollisionBoundingBox(this.world, p) == null;
    }

    private boolean isSolidSupport(BlockPos p) {
        IBlockState s = this.world.getBlockState(p);
        if (s.getMaterial().isLiquid()) return false;
        return s.getCollisionBoundingBox(this.world, p) != null;
    }

    private boolean atTakeoffEdge(BlockPos takeoff, int sx, int sz) {
        AxisAlignedBB bb = this.entity.getEntityBoundingBox();
        double eps = 0.12; // how close to the edge we require

        if (sx == 1) return bb.maxX >= (takeoff.getX() + 1.0 - eps);
        if (sx == -1) return bb.minX <= (takeoff.getX() + eps);
        if (sz == 1) return bb.maxZ >= (takeoff.getZ() + 1.0 - eps);
        if (sz == -1) return bb.minZ <= (takeoff.getZ() + eps);

        return false;
    }

    // keep them centered on the takeoff block so they don't "graze" corners and miss
    private boolean roughlyCenteredOnTakeoff(BlockPos takeoff, int sx, int sz) {
        AxisAlignedBB bb = this.entity.getEntityBoundingBox();
        double cx = (bb.minX + bb.maxX) * 0.5;
        double cz = (bb.minZ + bb.maxZ) * 0.5;

        double tx = takeoff.getX() + 0.5;
        double tz = takeoff.getZ() + 0.5;

        double tol = 0.28; // tighter = safer for 1-wide landing strips
        if (sx != 0) return Math.abs(cz - tz) <= tol;
        if (sz != 0) return Math.abs(cx - tx) <= tol;
        return true;
    }

    private boolean nearTakeoffEdgePoint(BlockPos takeoff, int sx, int sz) {
        double edgeX = takeoff.getX() + 0.5 + sx * 0.45;
        double edgeZ = takeoff.getZ() + 0.5 + sz * 0.45;

        double dx = this.entity.posX - edgeX;
        double dz = this.entity.posZ - edgeZ;
        return (dx * dx + dz * dz) <= 0.20 * 0.20; // adjust if needed (0.18..0.25)
    }

    private boolean willCrossTakeoffEdgeNextTick(BlockPos takeoff, int sx, int sz) {
        AxisAlignedBB bb = this.entity.getEntityBoundingBox();
        double eps = 0.08; // a bit looser than 0.06

        if (sx == 1) return (bb.maxX + this.entity.motionX) >= (takeoff.getX() + 1.0 - eps);
        if (sx == -1) return (bb.minX + this.entity.motionX) <= (takeoff.getX() + eps);
        if (sz == 1) return (bb.maxZ + this.entity.motionZ) >= (takeoff.getZ() + 1.0 - eps);
        if (sz == -1) return (bb.minZ + this.entity.motionZ) <= (takeoff.getZ() + eps);
        return false;
    }

    private void antiStuckTick() {
        if (gapJumpTicks > 0) {
            stuckTicks = 0;
            stuckX = this.entity.posX;
            stuckZ = this.entity.posZ;
            return;
        }

        if (this.currentPath == null || this.currentPath.isFinished()) {
            stuckTicks = 0;
            stuckX = this.entity.posX;
            stuckZ = this.entity.posZ;
            return;
        }

        double dx = this.entity.posX - stuckX;
        double dz = this.entity.posZ - stuckZ;

        if (dx * dx + dz * dz < 0.03 * 0.03) {
            stuckTicks++;

            if (stuckTicks >= 40) {
                // Ne supprime pas le path ici.
                // Marque juste un besoin de repath si tu as ce mécanisme.
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
            stuckX = this.entity.posX;
            stuckZ = this.entity.posZ;
        }
    }

    private boolean isHazardBelow(BlockPos below) {
        IBlockState s = this.world.getBlockState(below);
        Material m = s.getMaterial();
        if (m == Material.LAVA || m == Material.WATER) return true;
        return s.getCollisionBoundingBox(this.world, below) == null;
    }

    // “narrow” if either side (perpendicular) has a drop/hazard immediately below
    private boolean isNarrowLanding(int x, int y, int z, int sx, int sz) {
        // perpendicular direction
        int px = (sx != 0) ? 0 : 1;
        int pz = (sx != 0) ? 1 : 0;

        BlockPos leftBelow = new BlockPos(x + px, y - 1, z + pz);
        BlockPos rightBelow = new BlockPos(x - px, y - 1, z - pz);

        // either side hazardous => treat as narrow (more conservative, fewer slips)
        return isHazardBelow(leftBelow) || isHazardBelow(rightBelow);
    }
    private static final int FOLLOWER_HEAVY_UPDATE_BUCKETS = 8;

    private boolean shouldDoHeavyPathUpdatesThisTick() {
        if (!followerMode) return true;

        int phase = Math.floorMod(this.entity.getEntityId(), FOLLOWER_HEAVY_UPDATE_BUCKETS);
        return (this.entity.ticksExisted % FOLLOWER_HEAVY_UPDATE_BUCKETS) == phase;
    }

    private Vec3d blendDir(Vec3d current, Vec3d target, double alpha) {
        Vec3d mixed = new Vec3d(
                current.x * (1.0 - alpha) + target.x * alpha,
                0.0,
                current.z * (1.0 - alpha) + target.z * alpha
        );

        double len = mixed.length();
        if (len < 1.0e-6) return target;
        return new Vec3d(mixed.x / len, 0.0, mixed.z / len);
    }

    private boolean isHazardousFeet(BlockPos feet) {
        IBlockState s = this.world.getBlockState(feet);
        Material m = s.getMaterial();

        if (m == Material.LAVA) return true;
        if (m == Material.FIRE) return true;
        if (m == Material.CACTUS) return true;

        AxisAlignedBB bb = s.getCollisionBoundingBox(this.world, feet);
        if (bb == null) {
            BlockPos below = feet.down();
            IBlockState sb = this.world.getBlockState(below);
            Material mb = sb.getMaterial();
            if (mb == Material.LAVA || mb == Material.FIRE) return true;
        }

        return false;
    }

    private boolean isSafeDirectMoveTarget(double x, double y, double z) {
        BlockPos feet = new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
        BlockPos head = feet.up();

        IBlockState fs = this.world.getBlockState(feet);
        IBlockState hs = this.world.getBlockState(head);

        if (fs.getMaterial().isLiquid() && fs.getMaterial() != Material.WATER) return false;
        if (hs.getMaterial().isLiquid() && hs.getMaterial() != Material.WATER) return false;

        if (isHazardousFeet(feet) || isHazardousFeet(feet.down())) return false;

        return true;
    }

    private void setSafeMoveTo(double x, double y, double z, double speed) {
        if (isSafeDirectMoveTarget(x, y, z)) {
            lastSafeMoveX = x;
            lastSafeMoveY = y;
            lastSafeMoveZ = z;
            this.entity.getMoveHelper().setMoveTo(x, y, z, speed);
            return;
        }

        if (!Double.isNaN(lastSafeMoveX)) {
            this.entity.getMoveHelper().setMoveTo(lastSafeMoveX, lastSafeMoveY, lastSafeMoveZ, Math.min(speed, 0.85));
            return;
        }

        this.entity.getMoveHelper().setMoveTo(this.entity.posX, this.entity.posY, this.entity.posZ, 0.0);
    }
}


