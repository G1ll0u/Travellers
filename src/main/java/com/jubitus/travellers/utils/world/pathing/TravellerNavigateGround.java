package com.jubitus.travellers.utils.world.pathing;

import com.jubitus.travellers.config.TravellersConfig;
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
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;

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
    // Set while isGapJumping() is true; used by isGapJumping() itself to detect the landing tick.
    private boolean wasGapJumping = false;
    // The landing column this navigator's current/last jump was aimed at, cached at commit time
    // so the landing tick can snap onto it - see isGapJumping()'s landing branch.
    private double pendingLandX, pendingLandZ;
    private boolean hasPendingLand = false;
    // Set by pathFollow() whenever it deliberately commands a "stay here" hold (waiting out
    // gapJumpCooldown at the edge of a jump). Read by the overridden onUpdateNavigation() to
    // suppress vanilla's own unconditional re-steer - see that override's comment for why.
    private boolean holdingForJumpCooldown = false;

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

    /**
     * True while a gap jump was recently fired and the entity is still airborne. Also detects the
     * exact tick a jump lands (the moment this flips from true to false) and, right then, damps
     * most of the leftover horizontal momentum from the jump - a real landing "sticks" instead of
     * skating through at near-full jump speed, which is what carried entities clean off a small
     * landing (a narrow pillar, a tight obstacle course) before anything got a chance to react.
     * This is a stateful check, not a pure getter, but it's called at most meaningfully once per
     * tick (whichever AI task is currently executing - {@link com.jubitus.travellers.entity.entityAI.travel.EntityAITravel}
     * or the follower AI - already calls this once at its own top-of-tick guard); extra calls in
     * the same tick (e.g. also from {@link #pathFollow()}) are safe no-ops once the transition has
     * already been handled.
     */
    public boolean isGapJumping() {
        boolean jumping = jumpInFlightTicks > 0 && !this.entity.onGround;
        if (jumping) {
            wasGapJumping = true;
        } else if (wasGapJumping) {
            wasGapJumping = false;
            jumpInFlightTicks = 0;
            double retain = TravellersConfig.GAP_JUMP.landingMomentumRetention;
            this.entity.motionX *= retain;
            this.entity.motionZ *= retain;

            // Snap onto the exact column the jump was computed for, if actual physics landed
            // close enough to it. A 1-wide landing (a narrow pillar) has barely half a block of
            // margin on any side, so even the small remaining imprecision in the flight model
            // (drag/friction is only an approximation of vanilla's real per-tick physics) was
            // enough to land a little off-center and immediately put the entity within an edge's
            // reach - this removes that imprecision entirely instead of chasing it further.
            // Bounded on purpose: a real mid-air disruption (a hit, a shove from another entity)
            // can and should still land the entity somewhere else - only a SMALL deviation (the
            // model's own imprecision, not an actual disruption) gets corrected.
            if (hasPendingLand) {
                double dx = this.entity.posX - pendingLandX;
                double dz = this.entity.posZ - pendingLandZ;
                double devSq = dx * dx + dz * dz;
                boolean snapped = devSq <= TravellersConfig.GAP_JUMP.landingSnapMaxCorrection
                        * TravellersConfig.GAP_JUMP.landingSnapMaxCorrection;
                if (snapped) {
                    this.entity.setPosition(pendingLandX, this.entity.posY, pendingLandZ);
                }
                debugJump("LANDED at %.2f,%.2f (aimed %.2f,%.2f, deviation=%.2f) snapped=%b motionX=%.3f motionZ=%.3f",
                        this.entity.posX, this.entity.posZ, pendingLandX, pendingLandZ, Math.sqrt(devSq), snapped,
                        this.entity.motionX, this.entity.motionZ);
            }
            hasPendingLand = false;
        }
        return jumping;
    }

    /**
     * Overridden because vanilla's own {@code PathNavigate.onUpdateNavigation()} does something
     * dangerous for us: right after calling our overridden {@link #pathFollow()}, it UNCONDITIONALLY
     * re-issues its own {@code moveHelper.setMoveTo(...)} toward {@code currentPath.getPosition(entity)}
     * at full {@code speed} - completely independent of whatever pathFollow() just decided.
     *
     * This silently undid every steering decision this class makes around a jump: while airborne
     * it kept nudging weak air-control acceleration toward the ground path node every tick, and -
     * the actual bug behind repeated "nothing changed" reports - the instant pathFollow() commanded
     * a deliberate "stay here, wait out the cooldown" hold (see the two {@code isJumpPendingCooldown}
     * call sites in pathFollow()), this vanilla tail call ran immediately afterward in the very same
     * tick and overwrote it with a real walk toward the path's current point at normal speed. On a
     * small landing (a 1-wide pillar) that alone was enough to walk the entity off the edge every
     * time, regardless of any hold/damp/snap logic pathFollow() itself performed - which is exactly
     * why none of those fixes changed anything observable.
     *
     * This is a straight copy of vanilla's {@code onUpdateNavigation()} with one change: the final
     * unconditional move call is skipped while {@link #isGapJumping()} or {@link #holdingForJumpCooldown}
     * is true this tick. Every other line matches vanilla's own logic exactly.
     */
    @Override
    public void onUpdateNavigation() {
        ++this.totalTicks;
        if (this.tryUpdatePath) {
            this.updatePath();
        }

        if (!this.noPath()) {
            if (this.canNavigate()) {
                this.pathFollow();
            } else if (this.currentPath != null && this.currentPath.getCurrentPathIndex() < this.currentPath.getCurrentPathLength()) {
                Vec3d vec3d = this.getEntityPosition();
                Vec3d vec3d1 = this.currentPath.getVectorFromIndex(this.entity, this.currentPath.getCurrentPathIndex());

                if (vec3d.y > vec3d1.y && !this.entity.onGround
                        && MathHelper.floor(vec3d.x) == MathHelper.floor(vec3d1.x)
                        && MathHelper.floor(vec3d.z) == MathHelper.floor(vec3d1.z)) {
                    this.currentPath.setCurrentPathIndex(this.currentPath.getCurrentPathIndex() + 1);
                }
            }

            this.debugPathFinding();

            if (!this.noPath() && !isGapJumping() && !holdingForJumpCooldown) {
                Vec3d vec3d2 = this.currentPath.getPosition(this.entity);
                BlockPos blockpos = (new BlockPos(vec3d2)).down();
                AxisAlignedBB axisalignedbb = this.world.getBlockState(blockpos).getBoundingBox(this.world, blockpos);
                vec3d2 = vec3d2.subtract(0.0D, 1.0D - axisalignedbb.maxY, 0.0D);
                this.entity.getMoveHelper().setMoveTo(vec3d2.x, vec3d2.y, vec3d2.z, this.speed);
            }
        }
    }

    @Override
    protected void pathFollow() {
        holdingForJumpCooldown = false;

        // Don't steer while a gap jump is in flight — let physics carry the entity.
        if (isGapJumping()) {
            jumpInFlightTicks--;
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
                    if (!offerGapJump(mx, mz)) {
                        // A real jump is validated and queued up, only waiting on its own cooldown
                        // - hold here instead of walking toward a target across the gap, which on
                        // a small landing (a narrow pillar, a tight obstacle course) would step the
                        // entity clean off the edge and into the gap it hasn't been allowed to
                        // jump yet. Actively command a stay-put (not just skip the call) - the
                        // move helper otherwise keeps chasing whatever target it was last given.
                        if (isJumpPendingCooldown(mx, mz)) {
                            this.entity.getMoveHelper().setMoveTo(this.entity.posX, this.entity.posY, this.entity.posZ, 0.0);
                            holdingForJumpCooldown = true;
                        } else {
                            setSafeMoveTo(mx, target.y, mz, this.speed);
                        }
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
            // Same hold-instead-of-fall-in reasoning as the followerMode branch above.
            if (isJumpPendingCooldown(target.x, target.z)) {
                this.entity.getMoveHelper().setMoveTo(this.entity.posX, this.entity.posY, this.entity.posZ, 0.0);
                holdingForJumpCooldown = true;
            } else {
                this.entity.getMoveHelper().setMoveTo(target.x, target.y, target.z, this.speed);
            }
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
        if (gapJumpCooldown > 0) {
            gapJumpCooldown--;
            debugJump("skip: gapJumpCooldown=%d remaining", gapJumpCooldown + 1);
            return false;
        }

        GapJumpResult gap = evaluateJump(targetX, targetZ);
        if (gap == null) return false;

        commitJump(gap, speed);
        return true;
    }

    private void debugJump(String format, Object... args) {
        if (!TravellersConfig.GAP_JUMP.debugGapJump) return;
        net.minecraftforge.fml.common.FMLLog.log(
                org.apache.logging.log4j.Level.INFO,
                "[Travellers/GapJump] " + this.entity.getName() + "#" + this.entity.getEntityId() + ": " + format,
                args);
    }

    /**
     * True when a jump toward the given target is fully validated and ready to fire, but is
     * being withheld purely by {@link #gapJumpCooldown} - i.e. {@link #offerGapJump} would
     * succeed the instant the cooldown clears. Callers can use this to hold the entity in place
     * instead of falling back to a plain walk, which would step it straight off a small landing
     * (a narrow pillar, a tight obstacle course) toward a target on the far side of the very gap
     * it isn't allowed to jump yet - the entity would simply fall in.
     *
     * Deliberately does NOT touch {@link #gapJumpCooldown} itself (no decrement) - it's a pure
     * read used alongside the real per-tick {@link #offerGapJump} call, not a replacement for it.
     */
    public boolean isJumpPendingCooldown(double targetX, double targetZ) {
        return gapJumpCooldown > 0 && evaluateJump(targetX, targetZ) != null;
    }

    /**
     * Full jump validation (edge proximity, gap geometry, landing angle) with no side effects and
     * no cooldown check - shared by {@link #offerGapJump} (which gates + commits) and
     * {@link #isJumpPendingCooldown} (which only needs to know a jump is ready).
     */
    @Nullable
    private GapJumpResult evaluateJump(double targetX, double targetZ) {
        // While mounted, the rider's own motionY/motionX impulse below is meaningless - its position
        // is overwritten by the horse's every tick regardless. TravellerMountSupport.tickMountedNavigation
        // drives the horse's own jump instead, sized off that specific horse's jump-strength attribute.
        if (this.entity instanceof com.jubitus.travellers.entity.travellers.EntityTraveller
                && ((com.jubitus.travellers.entity.travellers.EntityTraveller) this.entity).isMountedOnBondedMount()) {
            return null;
        }
        if (!this.entity.onGround || this.entity.isInWater() || this.entity.isInLava()) {
            debugJump("skip: onGround=%b inWater=%b inLava=%b", this.entity.onGround, this.entity.isInWater(), this.entity.isInLava());
            return null;
        }

        double dirX = targetX - this.entity.posX;
        double dirZ = targetZ - this.entity.posZ;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.5) {
            debugJump("skip: target too close (len=%.3f) target=%.2f,%.2f pos=%.2f,%.2f", len, targetX, targetZ, this.entity.posX, this.entity.posZ);
            return null;
        }
        dirX /= len;
        dirZ /= len;

        int cx = MathHelper.floor(this.entity.posX);
        int cy = MathHelper.floor(this.entity.getEntityBoundingBox().minY);
        int cz = MathHelper.floor(this.entity.posZ);

        // Distance from entity's continuous position to the next block boundary in the
        // movement direction. Only commit to a jump when close enough to the edge so the
        // jump fires while the entity is still safely on solid ground.
        // Cardinal-only, matching GapJumpEvaluator's own dominant-axis snap - keeps this
        // edge-distance check and the actual jump scan agreeing on the same direction.
        int dx, dz;
        if (Math.abs(dirX) >= Math.abs(dirZ)) {
            dx = dirX > 0.0D ? 1 : (dirX < 0.0D ? -1 : 0);
            dz = 0;
        } else {
            dz = dirZ > 0.0D ? 1 : -1;
            dx = 0;
        }
        double distToEdge = dx != 0
                ? (dx > 0 ? (cx + 1.0) - this.entity.posX : this.entity.posX - cx)
                : (dz > 0 ? (cz + 1.0) - this.entity.posZ : this.entity.posZ - cz);
        if (distToEdge > 0.8) {
            // Allow jump anyway when idle — applyJumpMotion supplies the missing momentum.
            double motionSq = this.entity.motionX * this.entity.motionX + this.entity.motionZ * this.entity.motionZ;
            if (motionSq >= 0.005) {
                debugJump("skip: too far from edge (distToEdge=%.2f) and already moving (motionSq=%.4f) pos=%.2f,%.2f dir=%.2f,%.2f",
                        distToEdge, motionSq, this.entity.posX, this.entity.posZ, dirX, dirZ);
                return null;
            }
        }

        GapJumpResult gap = GapJumpEvaluator.evaluate(
                this.world, this.world.getTotalWorldTime(), cx, cy, cz, dirX, dirZ);
        if (gap.type != GapJumpResult.Type.JUMPABLE) {
            debugJump("skip: gap.type=%s at cell %d,%d,%d dir=%.2f,%.2f (quantized %d,%d) pos=%.2f,%.2f",
                    gap.type, cx, cy, cz, dirX, dirZ, dx, dz, this.entity.posX, this.entity.posZ);
            return null;
        }

        // Reject jumps that would take the entity sideways or backward relative to its target.
        // This prevents jumping into water when walking alongside a shore.
        double lDx = gap.landX - this.entity.posX;
        double lDz = gap.landZ - this.entity.posZ;
        double lDist = Math.sqrt(lDx * lDx + lDz * lDz);
        if (lDist > 1e-6) {
            double cosAngle = (dirX * lDx + dirZ * lDz) / lDist;
            if (cosAngle < 0.3) {
                debugJump("skip: landing too sideways/backward (cosAngle=%.2f) land=%.2f,%.2f pos=%.2f,%.2f dir=%.2f,%.2f",
                        cosAngle, gap.landX, gap.landZ, this.entity.posX, this.entity.posZ, dirX, dirZ);
                return null; // jump is too perpendicular to travel direction
            }
        }

        // Never jump onto a spot another living entity is already occupying - a crowded group
        // landing on top of each other is exactly the kind of shove that can knock one of them
        // straight into the gap it just crossed. Checked fresh on every real jump attempt
        // (unlike GapJumpEvaluator's own terrain scan, this is deliberately NOT cached - entity
        // positions change every tick, and a stale "occupied" verdict would needlessly block a
        // jump long after the spot cleared).
        AxisAlignedBB landingBox = new AxisAlignedBB(
                gap.landX - 0.4, gap.landY, gap.landZ - 0.4,
                gap.landX + 0.4, gap.landY + 2.0, gap.landZ + 0.4);
        if (!this.world.getEntitiesWithinAABB(net.minecraft.entity.EntityLivingBase.class, landingBox,
                e -> e != this.entity).isEmpty()) {
            debugJump("skip: landing occupied by another entity land=%.2f,%.2f,%.2f",
                    gap.landX, gap.landY, gap.landZ);
            return null;
        }

        debugJump("VALID gap width=%d land=%.2f,%.2f,%.2f from pos=%.2f,%.2f,%.2f",
                gap.gapWidth, gap.landX, gap.landY, gap.landZ,
                this.entity.posX, this.entity.posY, this.entity.posZ);
        return gap;
    }

    private void commitJump(GapJumpResult gap, double speed) {
        // Deliberately NOT clearing the active path here. pathFollow()'s own isGapJumping() guard
        // already fully prevents the path-follower from steering the entity anywhere while
        // airborne, so clearing served no purpose during the flight itself - but it broke landing:
        // vanilla's PathNavigate.onUpdateNavigation() never calls pathFollow() at all while
        // noPath() is true, so clearing the path here left the entity with NOTHING driving it
        // (no re-jump attempt, no steering) for however long until the next throttled repath -
        // easily long enough, on a small landing (a narrow pillar), for leftover jump momentum
        // alone to slide it off the far edge before any AI ever ran again. Keeping the original
        // path attached lets pathFollow() resume it (and re-offer a jump) the very next tick.
        //
        // A gap jump physically skips straight from takeoff to landing - pathFollow()'s normal
        // per-tick "close enough to this path point, advance" check never runs during the flight
        // (isGapJumping() bails out of it entirely), so any path point that sat between takeoff
        // and landing would otherwise be left un-advanced-past forever: the very next tick after
        // landing, pathFollow() would still be aiming at that stale, now-BEHIND point and jump
        // straight back to fetch it. Fast-forward the index now, before liftoff, using where this
        // jump is actually going rather than waiting on a proximity check that flight will skip.
        advancePathIndexPastJump(gap);

        // Scale jump height with distance like a player naturally jumps a bit more forcefully
        // for a longer gap, instead of always using the same short single-block hop height and
        // relying purely on horizontal burst speed to cover the rest - that burst is what made a
        // wide jump look like a teleport (see applyJumpMotion below).
        double heightDiff = gap.landY - this.entity.getEntityBoundingBox().minY;
        double motionY0 = computeJumpMotionY(gap.gapWidth);

        // Fire the jump THIS tick by writing motionY directly.
        // JumpHelper.doJump() runs BEFORE the navigator each tick, so setJumping() would only
        // take effect next tick — by which point the entity has already stepped off the edge
        // (onGround=false) and doJump silently no-ops. Direct assignment avoids that race.
        this.entity.motionY = motionY0;

        // How long this specific jump will actually stay airborne, simulated from real physics
        // rather than guessed from a fixed lookup - both the horizontal speed below and the
        // steering pause above need this to be accurate, or the entity either overshoots (too
        // fast, looks like teleporting) or undershoots (falls short, into the gap it was trying
        // to clear).
        int airTicks = simulateAirTicks(motionY0, heightDiff);

        // Apply the horizontal impulse needed to reach the landing zone.
        applyJumpMotion(gap, airTicks);

        gapJumpCooldown = TravellersConfig.GAP_JUMP.postJumpCooldownBaseTicks
                + gap.gapWidth * TravellersConfig.GAP_JUMP.postJumpCooldownPerWidthTicks;
        // Small buffer past the simulated airtime so a slightly-off landing doesn't cut steering
        // back in one tick early, while still handing control back promptly once actually grounded
        // (isGapJumping() already exits the instant entity.onGround flips true regardless).
        jumpInFlightTicks = airTicks + 3;

        // Keep the move helper pointed at the landing so the entity stays oriented correctly
        // while airborne (air forces are weak but accumulate over many ticks).
        this.entity.getMoveHelper().setMoveTo(gap.landX, gap.landY, gap.landZ,
                Math.min(speed * 1.1, 1.0));

        pendingLandX = gap.landX;
        pendingLandZ = gap.landZ;
        hasPendingLand = true;

        debugJump("FIRED gapWidth=%d motionY0=%.3f airTicks=%d motionX=%.3f motionZ=%.3f cooldown=%d",
                gap.gapWidth, motionY0, airTicks, this.entity.motionX, this.entity.motionZ, gapJumpCooldown);
    }

    /**
     * Advances {@link #currentPath}'s index past any upcoming path point that this jump is about
     * to fly over, using an along/across projection onto the takeoff-to-landing line rather than
     * the ordinary proximity check (which never gets a chance to run mid-flight - see the call
     * site's comment). Points beyond the landing spot, or too far sideways of the jump's line, are
     * left alone; the scan stops at the first such point since path points are ordered along the
     * route.
     */
    private void advancePathIndexPastJump(GapJumpResult gap) {
        if (this.currentPath == null || this.currentPath.isFinished()) return;

        double startX = this.entity.posX;
        double startZ = this.entity.posZ;
        double dirX = gap.landX - startX;
        double dirZ = gap.landZ - startZ;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0e-6) return;
        dirX /= len;
        dirZ /= len;

        int idx = this.currentPath.getCurrentPathIndex();
        int pathLen = this.currentPath.getCurrentPathLength();
        int advanceTo = idx;

        for (int i = idx; i < pathLen; i++) {
            Vec3d v = vecForPathPoint(this.currentPath.getPathPointFromIndex(i));
            double along = (v.x - startX) * dirX + (v.z - startZ) * dirZ;
            double across = (v.x - startX) * -dirZ + (v.z - startZ) * dirX;
            if (along < -0.5D || along > len + 0.5D || Math.abs(across) > 1.5D) break;
            advanceTo = i + 1;
        }

        if (advanceTo > idx) {
            debugJump("path index fast-forwarded %d -> %d over jump to %.2f,%.2f (skipped points along the flight)",
                    idx, advanceTo, gap.landX, gap.landZ);
            this.currentPath.setCurrentPathIndex(Math.min(advanceTo, pathLen));
        }
    }

    /**
     * Jump height (initial motionY) for a gap of the given width. A plain 1-wide hop uses the
     * same height as a normal vanilla jump; wider jumps get a modest boost, both because it reads
     * as a more deliberate effort for a longer gap and because it buys the extra airtime a wider
     * jump needs, letting the horizontal speed in applyJumpMotion stay within a natural cap
     * instead of ballooning to cover the extra distance in the same short hop.
     */
    private static double computeJumpMotionY(int gapWidth) {
        double boosted = BASE_JUMP_MOTION_Y
                + TravellersConfig.GAP_JUMP.jumpHeightBoostPerGapBlock * (gapWidth - 1);
        return Math.min(boosted, TravellersConfig.GAP_JUMP.maxJumpVerticalVelocity);
    }

    private static final double BASE_JUMP_MOTION_Y = 0.42D;
    private static final double GRAVITY = 0.08D;
    private static final double Y_DRAG = 0.98D;
    /** Default block slipperiness (0.6) * vanilla's constant air drag (0.91), see applyJumpMotion. */
    private static final double GROUND_FRICTION = 0.6D * 0.91D;

    /**
     * Simulates vanilla's own vertical physics (gravity then drag, applied every tick after
     * moving) starting from motionY0 until the cumulative vertical displacement reaches
     * heightDiff (the landing's height relative to takeoff). This replaces a fixed lookup table
     * that only knew about the standard 0.42 jump - now that jump height itself varies with gap
     * width, the airtime has to be derived from whatever height was actually used, or the
     * horizontal speed computed from it would be wrong.
     *
     * <p>For a landing at or below takeoff height ({@code heightDiff <= 0}), {@code y} rises then
     * falls and crosses the target exactly once, on the way down - a plain {@code y <= heightDiff}
     * check finds that correctly. For a landing ABOVE takeoff height, that same check is wrong: y
     * starts at 0, which already satisfies {@code y <= heightDiff} for any positive heightDiff, so
     * it fired on tick 1 every time, before the entity had gained any real height - it always
     * returned a wildly-too-short airtime, which {@link #applyJumpMotion} then read as "must cover
     * the whole gap in ~1 tick" and blew straight past the intended landing at close to the
     * horizontal speed cap. A rising landing must wait until the arc has gone up AND come back
     * down (past its apex, tracked via {@code vy} turning non-positive) before the same downward
     * crossing check applies - mirrors how a jump onto a higher ledge actually plays out physically.
     */
    private static int simulateAirTicks(double motionY0, double heightDiff) {
        double y = 0.0;
        double vy = motionY0;
        boolean pastApex = false;
        for (int t = 1; t <= 40; t++) {
            y += vy;
            vy = (vy - GRAVITY) * Y_DRAG;
            if (heightDiff <= 0.0D) {
                if (y <= heightDiff) return t;
            } else {
                if (vy <= 0.0D) pastApex = true;
                if (pastApex && y <= heightDiff) return t;
            }
        }
        return 40;
    }

    private void applyJumpMotion(GapJumpResult gap, int airTicks) {
        double hDx = gap.landX - this.entity.posX;
        double hDz = gap.landZ - this.entity.posZ;
        double hDist = Math.sqrt(hDx * hDx + hDz * hDz);
        if (hDist < 0.1) return;

        // Horizontal displacement model for a jump that starts on the ground. Mirrors vanilla
        // EntityLivingBase.travel(): the friction factor applied at the END of a tick is chosen
        // from entity.onGround as it was at the START of that same tick (move() itself is what
        // flips onGround, and it runs AFTER the friction factor is picked) - so:
        //   tick 0 (was on ground before this tick's move): entity moves by v0, then friction
        //     GROUND_FRICTION (= default block slipperiness 0.6 * air drag 0.91) is applied.
        //   ticks 1..N (already airborne from tick 0's move): motionX *= 0.91 each tick.
        // Using bare slipperiness (0.6) instead of slipperiness*0.91 here previously overstated
        // how much horizontal speed survives the first tick, which made every solved v0 undershoot
        // the real target distance by about 12% even before the hard cap below.
        //
        // Total displacement ≈ v0 + v0*GROUND_FRICTION * sum(0.91^i, i=0..airTicks-2)
        //                     = v0 * (1 + GROUND_FRICTION * (1 - 0.91^(airTicks-1)) / 0.09)
        double airDragFactor = (airTicks > 1)
                ? 1.0 + GROUND_FRICTION * (1.0 - Math.pow(0.91, airTicks - 1)) / 0.09
                : 1.0;
        double v0 = (hDist / airDragFactor) * 0.95;

        // Hard cap on the initial horizontal burst so a wide jump can never look like it's
        // teleporting, even in the rare case the height boost above wasn't enough to buy back
        // enough airtime (e.g. maxGapWidth pushed past what jumpHeightBoostPerGapBlock accounts
        // for). Landing a little short of the exact target in that case is fine - the landing
        // cell was already validated with a full block of margin, and the move helper keeps
        // steering toward it for the rest of the flight.
        double cap = TravellersConfig.GAP_JUMP.maxJumpHorizontalSpeed;
        if (v0 > cap) v0 = cap;

        this.entity.motionX = (hDx / hDist) * v0;
        this.entity.motionZ = (hDz / hDist) * v0;
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


