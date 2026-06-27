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
    private int stuckTicks = 0;
    private double stuckX = 0, stuckZ = 0;

    private boolean followerMode = false;
    private int losSkipCooldown = 0;

    // Gap-jump state
    private int gapJumpCooldown = 0;
    // Tracks ticks remaining while a gap jump is still in flight (entity is airborne)
    private int jumpInFlightTicks = 0;

    private Vec3d smoothedSteerDir = Vec3d.ZERO;
    private double lastSafeMoveX = Double.NaN;
    private double lastSafeMoveY = Double.NaN;
    private double lastSafeMoveZ = Double.NaN;

    public TravellerNavigateGround(EntityLiving entity, World worldIn) {
        super(entity, worldIn);
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

    /** True while a gap jump was recently fired and the entity is still airborne. */
    public boolean isGapJumping() {
        return jumpInFlightTicks > 0 && !this.entity.onGround;
    }

    @Override
    protected void pathFollow() {
        // Don't steer while a gap jump is in flight — let physics carry the entity.
        if (isGapJumping()) {
            jumpInFlightTicks--;
            return;
        }
        if (this.entity.onGround) jumpInFlightTicks = 0;
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
                    if (!offerGapJump(mx, mz)) {
                        setSafeMoveTo(mx, target.y, mz, this.speed);
                    }
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

            losSkipCooldown = followerMode ? 12 : 4;
        }

        if (this.currentPath.isFinished()) return;

        int curIndex = this.currentPath.getCurrentPathIndex();
        PathPoint cur = this.currentPath.getPathPointFromIndex(curIndex);
        Vec3d target = vecForPathPoint(cur);
        if (this.entity.isInWater()) {
            this.entity.getMoveHelper().setMoveTo(target.x, this.entity.posY, target.z, this.speed);

            if (this.entity.collidedHorizontally) {
                this.entity.getJumpHelper().setJumping();
            }
            return;
        }

        if (!offerGapJump(target.x, target.z)) {
            this.entity.getMoveHelper().setMoveTo(target.x, target.y, target.z, this.speed);
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


    private void antiStuckTick() {

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

            // Safety net for fractional-height surfaces (path slabs, snow layers, etc.).
            // The primary fix is MixinBlockPathSlab, but this catches any remaining
            // cases where the entity is on a surface whose height is between 0 and 1
            // and cannot step off due to vanilla EntityMoveHelper jump-threshold behavior.
            if (stuckTicks >= 40 && this.entity.onGround) {
                double feetY = this.entity.getEntityBoundingBox().minY;
                double frac = feetY - Math.floor(feetY);
                if (frac > 0.02 && frac < 0.98) {
                    this.entity.getJumpHelper().setJumping();
                    stuckTicks = 0;
                }
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
    private static final int FOLLOWER_HEAVY_UPDATE_BUCKETS = 16;

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

    public boolean offerGapJump(double targetX, double targetZ) {
        return offerGapJump(targetX, targetZ, this.speed);
    }

    /**
     * Call before commanding movement. Detects a gap in the movement direction and, if
     * jumpable, fires the jump with physically correct horizontal velocity.
     *
     * Returns true when a jump was initiated — caller must NOT call its own setMoveTo.
     */
    public boolean offerGapJump(double targetX, double targetZ, double speed) {
        if (gapJumpCooldown > 0) { gapJumpCooldown--; return false; }
        if (!this.entity.onGround || this.entity.isInWater() || this.entity.isInLava()) return false;

        double dirX = targetX - this.entity.posX;
        double dirZ = targetZ - this.entity.posZ;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.5) return false;
        dirX /= len;
        dirZ /= len;

        int cx = MathHelper.floor(this.entity.posX);
        int cy = MathHelper.floor(this.entity.getEntityBoundingBox().minY);
        int cz = MathHelper.floor(this.entity.posZ);

        // Distance from entity's continuous position to the next block boundary in the
        // movement direction. Only commit to a jump when close enough to the edge so the
        // jump fires while the entity is still safely on solid ground.
        int dx = (int) Math.signum(Math.round(dirX * 2.0));
        int dz = (int) Math.signum(Math.round(dirZ * 2.0));
        double distToEdge;
        if (dx != 0 && dz != 0) {
            double ex = dx > 0 ? (cx + 1.0) - this.entity.posX : this.entity.posX - cx;
            double ez = dz > 0 ? (cz + 1.0) - this.entity.posZ : this.entity.posZ - cz;
            distToEdge = Math.min(ex, ez);
        } else if (dx != 0) {
            distToEdge = dx > 0 ? (cx + 1.0) - this.entity.posX : this.entity.posX - cx;
        } else {
            distToEdge = dz > 0 ? (cz + 1.0) - this.entity.posZ : this.entity.posZ - cz;
        }
        if (distToEdge > 0.8) {
            // Allow jump anyway when idle — applyJumpMotion supplies the missing momentum.
            double motionSq = this.entity.motionX * this.entity.motionX + this.entity.motionZ * this.entity.motionZ;
            if (motionSq >= 0.005) return false;
        }

        GapJumpResult gap = GapJumpEvaluator.evaluate(
                this.world, this.world.getTotalWorldTime(), cx, cy, cz, dirX, dirZ);
        if (gap.type != GapJumpResult.Type.JUMPABLE) return false;

        // Reject jumps that would take the entity sideways or backward relative to its target.
        // This prevents jumping into water when walking alongside a shore.
        double lDx = gap.landX - this.entity.posX;
        double lDz = gap.landZ - this.entity.posZ;
        double lDist = Math.sqrt(lDx * lDx + lDz * lDz);
        if (lDist > 1e-6) {
            double cosAngle = (dirX * lDx + dirZ * lDz) / lDist;
            if (cosAngle < 0.3) return false; // jump is too perpendicular to travel direction
        }

        // --- Commit to the jump ---

        // Clear the active path so the path-follower cannot steer the entity toward a detour
        // waypoint (wrong direction) while it is airborne.
        this.clearPath();

        // Fire the jump THIS tick by writing motionY directly.
        // JumpHelper.doJump() runs BEFORE the navigator each tick, so setJumping() would only
        // take effect next tick — by which point the entity has already stepped off the edge
        // (onGround=false) and doJump silently no-ops. Direct assignment avoids that race.
        this.entity.motionY = 0.42;

        // Apply the horizontal impulse needed to reach the landing zone.
        applyJumpMotion(gap);

        gapJumpCooldown = 28 + gap.gapWidth * 10;
        // Track flight time so pathFollow doesn't steer while airborne (approx 20 ticks for same-height jump)
        jumpInFlightTicks = 25 + gap.gapWidth * 5;

        // Keep the move helper pointed at the landing so the entity stays oriented correctly
        // while airborne (air forces are weak but accumulate over many ticks).
        this.entity.getMoveHelper().setMoveTo(gap.landX, gap.landY, gap.landZ,
                Math.min(speed * 1.1, 1.0));
        return true;
    }

    private void applyJumpMotion(GapJumpResult gap) {
        double hDx = gap.landX - this.entity.posX;
        double hDz = gap.landZ - this.entity.posZ;
        double hDist = Math.sqrt(hDx * hDx + hDz * hDz);
        if (hDist < 0.1) return;

        double heightDiff = gap.landY - this.entity.getEntityBoundingBox().minY;
        double airTicks   = estimateAirTicks(heightDiff);

        // Horizontal displacement model for a jump that starts on the ground:
        //   tick 0 (jump tick, still on ground at evaluation time):
        //     entity moves by motionX, then ground friction applied: motionX *= 0.6
        //   ticks 1..N (airborne):
        //     motionX *= 0.91 each tick (air drag)
        //
        // Total displacement ≈ v0 + v0*0.6 * sum(0.91^i, i=0..airTicks-2)
        //                     = v0 * (1 + 0.6 * (1 - 0.91^(airTicks-1)) / 0.09)
        //
        // Ground forces from the MoveHelper add ~0.2–0.35 blocks in the same tick, so we
        // scale down by 0.8 to avoid overshooting (landing 1-2 blocks past target is fine).
        double airDragFactor = (airTicks > 1)
                ? 1.0 + 0.6 * (1.0 - Math.pow(0.91, airTicks - 1)) / 0.09
                : 1.0;
        double v0 = (hDist / airDragFactor) * 0.95;

        this.entity.motionX = (hDx / hDist) * v0;
        this.entity.motionZ = (hDz / hDist) * v0;
    }

    private static double estimateAirTicks(double heightDiff) {
        // Standard jump (motionY₀ = 0.42) airtime by landing height delta.
        if (heightDiff >= 1.0) return 5.0;
        if (heightDiff >= 0.5) return 7.0;
        if (heightDiff >= 0.0) return 10.0;
        if (heightDiff >= -1.0) return 13.0;
        if (heightDiff >= -2.0) return 16.0;
        return 19.0;
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


