package com.jubitus.travellers.pathing;

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
    // While > 0, caps the A* search range (and so the chunk cache vanilla builds around the entity).
    private float searchRangeCap = -1.0F;

    @Override
    public float getPathSearchRange() {
        float range = super.getPathSearchRange();
        return searchRangeCap > 0.0F ? Math.min(range, searchRangeCap) : range;
    }
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

    @Nullable
    @Override
    public net.minecraft.pathfinding.Path getPathToPos(BlockPos pos) {
        return timedPathToPos(pos, false);
    }

    @Nullable
    @Override
    public net.minecraft.pathfinding.Path getPathToEntityLiving(net.minecraft.entity.Entity target) {
        if (!com.jubitus.travellers.debug.profile.MovementProfiler.isActive() || !canNavigate()) {
            return super.getPathToEntityLiving(target);
        }
        net.minecraft.pathfinding.Path before = this.currentPath;
        long t0 = System.nanoTime();
        net.minecraft.pathfinding.Path path = super.getPathToEntityLiving(target);
        long nanos = System.nanoTime() - t0;
        // Vanilla hands back the current path untouched when the target cell hasn't changed - no search ran.
        if (path == null || path != before) {
            com.jubitus.travellers.debug.profile.MovementProfiler.pathSearch(this.entity, nanos, path,
                    target.posX, target.posY, target.posZ, false);
        }
        return path;
    }

    @Nullable
    private net.minecraft.pathfinding.Path timedPathToPos(BlockPos pos, boolean probe) {
        if (!com.jubitus.travellers.debug.profile.MovementProfiler.isActive() || !canNavigate()) {
            return super.getPathToPos(pos);
        }
        net.minecraft.pathfinding.Path before = this.currentPath;
        long t0 = System.nanoTime();
        net.minecraft.pathfinding.Path path = super.getPathToPos(pos);
        long nanos = System.nanoTime() - t0;
        if (path == null || path != before) {
            com.jubitus.travellers.debug.profile.MovementProfiler.pathSearch(this.entity, nanos, path,
                    pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, probe);
        }
        return path;
    }

    @Override
    protected Vec3d getEntityPosition() {
        // Important: use real feet Y, not rounded - and the moving body's feet, since a mounted
        // rider's own bounding box sits about 0.78 above the ground its horse is standing on.
        net.minecraft.entity.Entity body = PathBody.of(this.entity);
        return new Vec3d(body.posX, body.getEntityBoundingBox().minY, body.posZ);
    }

    // --- NEW: compute the true surface Y for a path node (handles snow layers) ---
    private double surfaceYForNode(int x, int y, int z) {
        // In vanilla, node y is typically the "feet cell". The floor is usually in y-1,
        // but snow layers can be in y itself. One column only - taking the highest across a
        // two-cell footprint reads an adjacent wall as floor, see
        // TravellerNodeProcessor.surfaceYForNode.
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

    /**
     * The world point a path node means for THIS body. A node is the min corner of the body's
     * footprint, so the centre is half the footprint further along - {@code +0.5} for a one-cell
     * body, {@code +1.0} for a horse's two. Hardcoding 0.5 while the planner reserved two cells is
     * what would steer a horse half a block off its own route, permanently overhanging one side.
     */
    private Vec3d vecForPathPoint(PathPoint p) {
        BlockPos pos = new BlockPos(p.x, p.y, p.z);
        double off = PathBody.nodeCentreOffset(this.entity);

        if (this.world.getBlockState(pos).getMaterial() == Material.WATER) {
            // Aim mid-block so we don't keep trying to climb to the air block above
            return new Vec3d(p.x + off, p.y + 0.5D, p.z + off);
        }

        double y = surfaceYForNode(p.x, p.y, p.z);
        return new Vec3d(p.x + off, y, p.z + off);
    }

    /**
     * True while a gap jump was recently fired and the entity is still airborne. Also detects the
     * exact tick a jump lands (the moment this flips from true to false) and, right then, damps
     * most of the leftover horizontal momentum from the jump - a real landing "sticks" instead of
     * skating through at near-full jump speed, which is what carried entities clean off a small
     * landing (a narrow pillar, a tight obstacle course) before anything got a chance to react.
     * This is a stateful check, not a pure getter, but it's called at most meaningfully once per
     * tick (whichever AI task is currently executing - {@link com.jubitus.travellers.entity.entityai.travel.EntityAITravel}
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
                // Deliberately NOT Path.getPosition(entity): that offsets the node by the asking
                // entity's own width, which mounted is the rider's - half a block off the footprint
                // the planner actually reserved, re-applied every tick right after pathFollow() got
                // it right. vecForPathPoint is the one place that offset is decided.
                Vec3d vec3d2 = vecForPathPoint(
                        this.currentPath.getPathPointFromIndex(this.currentPath.getCurrentPathIndex()));
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

        float bodyWidth = PathBody.width(this.entity);
        double reach = bodyWidth > 0.75F
                ? bodyWidth / 2.0F
                : 0.75D - bodyWidth / 2.0D;

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

        // The followerMode fast path below aims straight at the current (single, ~1-block-away) path
        // point with a small fixed step - cheap and fine for a one-cell pedestrian body, but a mounted
        // body has a real minimum turn radius (see TravellerMoveHelper.slowForTurn) that a target this
        // close, this often, sits well inside of at any real speed. The result is not a stall - it is
        // a horse endlessly circling a point it can never turn tight enough to reach, which is exactly
        // what let a follower complete two or three full rotations before the path finally advanced
        // past that node. The full corridor-shortcut logic below already understands this for
        // EntityAITravel; it must not be skipped here just because this is a follower's path.
        if (followerMode && !PathBody.isMounted(this.entity) && !shouldDoHeavyPathUpdatesThisTick()) {
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

                if (PathBody.isInWater(this.entity)) {
                    setSafeMoveTo(mx, this.entity.posY, mz, this.speed);
                    if (this.entity.collidedHorizontally && !PathBody.isMounted(this.entity)) {
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
            boolean mounted = PathBody.isMounted(this.entity);

            int lookahead;
            if (remaining >= 10) lookahead = 5;
            else if (remaining >= 6) lookahead = 4;
            else lookahead = 2;

            int maxIdx = Math.min(curIdx + lookahead, this.currentPath.getCurrentPathLength() - 1);

            // The line-of-sight shortcut must be probed with the body that would actually travel it,
            // or it approves a straight dash between two trees a horse cannot fit through.
            int w = MathHelper.ceil(PathBody.width(this.entity));
            int h = MathHelper.ceil(PathBody.height(this.entity));

            if (mounted && TravellersConfig.MOUNTS.mountedCorridorShortcutEnabled) {
                // Mounted uses the terrain-following corridor test rather than vanilla's, which holds
                // the probe at the starting height and therefore refuses every slope - see
                // isBodyCorridorClear. That refusal is what made a horse climb a hill in steps.
                int limit = farthestNodeWithin(entityPos, curIdx,
                        TravellersConfig.MOUNTS.mountedLookaheadBlocks);
                int span = limit - (curIdx + 1);
                // Farthest candidate first, then progressively nearer, at a fixed small number of
                // probes. Deliberately NOT a binary search: that assumes a nearer node is reachable
                // whenever a farther one is, and in woodland it simply is not - a straight line to a
                // distant node can miss the trunks that the line to a nearer one runs into, so the
                // search could accept a far node it had no business accepting. A fixed handful of
                // probes costs the same and cannot draw that conclusion.
                for (int p = 0; span > 0 && p < MOUNTED_SHORTCUT_PROBES; p++) {
                    int j = limit - (span * p) / MOUNTED_SHORTCUT_PROBES;
                    if (j <= curIdx + 1) break;
                    Vec3d candidate = vecForPathPoint(this.currentPath.getPathPointFromIndex(j));
                    if (isBodyCorridorClear(entityPos, candidate) && headingAllowsCorridorShortcut(entityPos, candidate)) {
                        this.currentPath.setCurrentPathIndex(j);
                        break;
                    }
                }
            } else if (!mounted) {
                for (int j = maxIdx; j > curIdx + 1; j--) {
                    PathPoint pp = this.currentPath.getPathPointFromIndex(j);
                    Vec3d v = vecForPathPoint(pp);

                    if (this.isDirectPathBetweenPoints(entityPos, v, w, h, w)) {
                        this.currentPath.setCurrentPathIndex(j);
                        break;
                    }
                }
            }

            losSkipCooldown = followerMode ? 12 : 4;
        }

        if (this.currentPath.isFinished()) return;

        int curIndex = this.currentPath.getCurrentPathIndex();
        PathPoint cur = this.currentPath.getPathPointFromIndex(curIndex);
        Vec3d target = vecForPathPoint(cur);
        if (PathBody.isInWater(this.entity)) {
            this.entity.getMoveHelper().setMoveTo(target.x, this.entity.posY, target.z, this.speed);

            if (this.entity.collidedHorizontally && !PathBody.isMounted(this.entity)) {
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


    /**
     * True when the body can walk the straight line between two points, <b>following the terrain</b>.
     *
     * <p>This exists because vanilla's own {@code isDirectPathBetweenPoints} tests every cell along
     * the line at one fixed height - the height it started from - so it refuses every slope in either
     * direction, which is why a mounted traveller used to climb a hill like a staircase instead of
     * cutting across it the way a player rides.
     *
     * <p>The actual test lives in {@link MountTerrainProfile#corridorClear} so the corridor and the
     * planner's per-node scan resolve terrain under one set of rules (tolerant footprint, ford-depth
     * water, lava refusal, full-body sweep at the resolved surface) and can never disagree. The body
     * height used is the horse's own, not the rider's full stack - the planner only requires that
     * much either (low headroom is a cost there, not a refusal), and a stricter shortcut would simply
     * never fire in woodland, exactly where cutting the corner matters most.
     */
    public boolean isBodyCorridorClear(Vec3d from, Vec3d to) {
        double bodyHeight = PathBody.height(this.entity);
        double stepRise = PathBody.stepHeight(this.entity);
        double stack = bodyHeight;

        net.minecraft.entity.Entity body = PathBody.of(this.entity);
        if (body != this.entity) {
            // A ridden horse takes a jumpable ledge by riding straight at it (the reactive jump
            // fires on contact), so the per-sample rise budget is this horse's own jump reach, not
            // its step height - capped by the corridor's total climb budget, which still bounds any
            // one straight line. And the clearance owed is the full horse-plus-rider silhouette:
            // solid rock in the rider's band is a hard refusal (see corridorClear).
            stack = PathBody.clearanceHeight(this.entity);
            if (TravellersConfig.MOUNTS.mountedReactiveJumpEnabled
                    && body instanceof net.minecraft.entity.passive.EntityHorse) {
                double jump = com.jubitus.travellers.mount.TravellerMountSupport
                        .jumpHeightOf((net.minecraft.entity.passive.EntityHorse) body) - 0.2D;
                stepRise = Math.max(stepRise,
                        Math.min(jump, TravellersConfig.MOUNTS.mountedMaxStraightRise));
            }
        }

        return MountTerrainProfile.corridorClear(this.world, from, to,
                PathBody.width(this.entity) / 2.0D,
                bodyHeight,
                stepRise,
                TravellersConfig.MOUNTS.mountedMaxStraightDrop,
                TravellersConfig.MOUNTS.mountedMaxStraightRise,
                stack);
    }

    /**
     * True when the horse's own capped turn rate ({@code TravellerMoveHelper}) can actually deliver
     * it along something close to the straight chord {@link #isBodyCorridorClear} just validated,
     * rather than an arc that bows away from it. The move helper never lets the horse's heading snap
     * to face a shortcut target instantly - it turns at {@code mountedMaxTurnPerTick} degrees/tick,
     * slowing down further when the turn is sharper than that (see {@code
     * TravellerMoveHelper.slowForTurn}). A shortcut accepted while the current heading disagrees
     * sharply with the bearing to the candidate is therefore not walked as the sampled line at all -
     * it's walked as a circular arc of radius {@code speed / turnCapRad}, and the straight-line
     * corridor test's flank samples (only the horse's own half-width either side of the chord) can
     * miss whatever that bow clips. This is the gap {@code trySidestepIfWedged} patches over after
     * the fact; catching it here means the shortcut is refused before the horse ever runs into it.
     *
     * <p>Reuses the same circular-turn model {@code TravellerMoveHelper.slowForTurn} already reasons
     * from - turning radius {@code r = speed / turnCapRad} - to bound the worst-case bow
     * ({@code r * (1 - cos(deltaHeading))}) against a margin scaled from the horse's own half-width
     * ({@code mountedCorridorTurnGateMargin}), refusing the shortcut once that bow would exceed it.
     *
     * <p>Deliberately conservative in both directions it can be wrong: at near-zero speed the arc
     * geometry doesn't meaningfully apply yet (a horse essentially pivots without covering ground),
     * so it's allowed through and re-checked again a few ticks later once actually moving. Refusing
     * here only falls back to a nearer candidate or ordinary node-by-node walking - never a
     * correctness problem, only a missed optimization.
     */
    private boolean headingAllowsCorridorShortcut(Vec3d from, Vec3d to) {
        if (!TravellersConfig.MOUNTS.mountedCorridorTurnGateEnabled) return true;

        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (dx * dx + dz * dz < 1.0E-8D) return true;

        net.minecraft.entity.Entity body = PathBody.of(this.entity);
        double vx = body.posX - body.prevPosX;
        double vz = body.posZ - body.prevPosZ;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 1.0E-4D) return true;

        double half = PathBody.width(this.entity) / 2.0D * TravellersConfig.MOUNTS.mountedCorridorTurnGateMargin;
        double turnCapRad = Math.toRadians(TravellersConfig.MOUNTS.mountedMaxTurnPerTick);
        double radius = speed / turnCapRad;

        // Even a full about-turn's worst-case bow (2r) stays inside the margin at this radius.
        if (radius * 2.0D <= half) return true;

        double cosMax = 1.0D - half / radius;
        if (cosMax <= -1.0D) return true;
        double deltaMaxDeg = Math.toDegrees(Math.acos(cosMax));

        double bearingDeg = Math.toDegrees(Math.atan2(-dx, dz));
        double deltaDeg = Math.abs(MathHelper.wrapDegrees(bearingDeg - body.rotationYaw));
        return deltaDeg <= deltaMaxDeg;
    }

    /**
     * Steers straight at a point when there is no path to follow, provided the body can actually get
     * there in a straight line. Returns false when the corridor is blocked, leaving the caller free
     * to escalate.
     *
     * <p>Without this, a mounted rider whose planner returns nothing does not merely lose its route -
     * it stops dead. With no path, {@code onUpdateNavigation} never calls {@code setMoveTo}, so the
     * move helper falls back to {@code WAIT} and writes {@code moveForward = 0} every tick. Measured
     * in a real trace: 308 consecutive ticks standing perfectly still, not touching anything, on
     * ground, until a rescue teleport fired for want of anything else.
     *
     * <p><b>Trap: vanilla's {@code EntityMoveHelper.action} does not revert to {@code WAIT} on its
     * own</b> - it stays {@code MOVE_TO} at whatever point was last set until something explicitly
     * changes it. So once this method has succeeded even once, a corridor that turns blocked on a
     * later tick leaves the move helper chasing that same stale point forever rather than reverting to
     * the "stood still" case described above. A trace caught the visible result: a horse orbiting one
     * spot at the turn-rate cap for 244 ticks, continuously producing just enough per-tick displacement
     * from the turning itself that the generic "hasn't moved" stuck check never triggered - only the
     * separate net-progress check eventually did, four times slower. Both refusal paths below now
     * actively command a stop instead of leaving the stale order in place, so a refused corridor reads
     * as "stood still" (fast stuck detection) rather than "orbiting" (slow one).
     */
    /**
     * {@link #steerDirect(double, double, double)}, but the target is first clamped to
     * {@code legBlocks} of distance along the line from the body's current position - the "prove
     * one short leg, re-derive fresh next tick" shape {@code EntityAITravel}'s direct-ride and the
     * mounted follow steerers all need for the same reason: {@code corridorClear}'s rise/drop
     * budget applies to the whole tested line, so aiming far ahead in one shot fails that budget on
     * rolling terrain far more often than a short leg would. Was duplicated at each call site;
     * shared here instead.
     */
    public boolean steerDirectLegCapped(double x, double z, double legBlocks, double speed) {
        Vec3d from = getEntityPosition();
        double dx = x - from.x;
        double dz = z - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double aimX = dist > legBlocks ? from.x + dx / dist * legBlocks : x;
        double aimZ = dist > legBlocks ? from.z + dz / dist * legBlocks : z;
        return steerDirect(aimX, aimZ, speed);
    }

    public boolean steerDirect(double x, double z, double speed) {
        Vec3d from = getEntityPosition();
        // The destination column may sit a whole leg's climb above the start - that is exactly what
        // riding up a slope means - so its ground is resolved within the corridor's own total-rise
        // budget, not within a single step. Whether the climb between here and there is actually
        // walkable is the corridor test's job below, not this lookup's.
        double ground = MountTerrainProfile.groundYNear(this.world, x, z, from.y,
                TravellersConfig.MOUNTS.mountedMaxStraightRise,
                TravellersConfig.MOUNTS.mountedMaxStraightDrop,
                MathHelper.ceil(PathBody.height(this.entity)));
        if (Double.isNaN(ground)) {
            haltStaleDirectSteer();
            return false;
        }

        // The move order has to be re-issued every tick (the move helper drops back to WAIT after
        // acting on one), but the corridor behind it does not have to be re-proven every tick - it is
        // a few hundred block reads over a long probe. Re-check on a short cooldown, or immediately
        // if the destination changed, and coast on the last verdict in between.
        boolean sameTarget = Math.abs(x - lastDirectSteerX) < 1.0D && Math.abs(z - lastDirectSteerZ) < 1.0D;
        if (!sameTarget || directSteerRecheck <= 0) {
            directSteerApproved = isBodyCorridorClear(from, new Vec3d(x, ground, z));
            directSteerRecheck = DIRECT_STEER_RECHECK_TICKS;
            lastDirectSteerX = x;
            lastDirectSteerZ = z;
        } else {
            directSteerRecheck--;
        }
        if (!directSteerApproved) {
            haltStaleDirectSteer();
            return false;
        }

        this.entity.getMoveHelper().setMoveTo(x, ground, z, speed);
        return true;
    }

    /** Actively commands a stop rather than leaving whatever {@code MOVE_TO} order the move helper
     * was last given - see the trap note on {@link #steerDirect}. */
    private void haltStaleDirectSteer() {
        this.entity.getMoveHelper().setMoveTo(this.entity.posX, this.entity.posY, this.entity.posZ, 0.0);
    }

    /** How long a corridor verdict stands before {@link #steerDirect} re-proves it. Short enough that
     * a mob wandering into the way is noticed within a fifth of a second. */
    private static final int DIRECT_STEER_RECHECK_TICKS = 4;

    private int directSteerRecheck = 0;
    private boolean directSteerApproved = false;
    private double lastDirectSteerX = Double.NaN;
    private double lastDirectSteerZ = Double.NaN;

    /**
     * Shared probe behind {@link #canReachOnFootSized} and {@link #isReachable}: does a real A*
     * search actually arrive at the target (not just come back non-null - vanilla's PathFinder
     * answers an unreachable target with a best-effort route to whatever node got closest, so a
     * bare null-check reads as reachable for almost anything).
     *
     * <p>Costs a whole extra A* search, so callers must only run it when something already went
     * wrong, and throttled.
     */
    private boolean reachesTarget(BlockPos target, boolean ignoreMount) {
        getPathFinder();
        if (travellerProc == null) return false;
        travellerProc.setIgnoreMount(ignoreMount);
        // A probe only needs room around the straight line to its target, not the full follow range.
        net.minecraft.entity.Entity body = PathBody.of(this.entity);
        double tdx = target.getX() + 0.5D - body.posX, tdz = target.getZ() + 0.5D - body.posZ;
        searchRangeCap = (float) (Math.sqrt(tdx * tdx + tdz * tdz) * 1.5D + PROBE_RANGE_MARGIN);
        try {
            net.minecraft.pathfinding.Path path = timedPathToPos(target, true);
            if (path == null) return false;
            PathPoint end = path.getFinalPathPoint();
            if (end == null) return false;
            double dx = (end.x + 0.5D) - (target.getX() + 0.5D);
            double dz = (end.z + 0.5D) - (target.getZ() + 0.5D);
            return dx * dx + dz * dz <= REACH_TOLERANCE_SQ;
        } finally {
            travellerProc.setIgnoreMount(false);
            searchRangeCap = -1.0F;
        }
    }

    private static final double PROBE_RANGE_MARGIN = 16.0D;

    /**
     * Diagnostic: would this destination be reachable if the traveller were on foot rather than
     * mounted? This is the question that separates the two explanations for "no path", and nothing
     * else in a trace can tell them apart. Same answer for both sizes means the destination is
     * genuinely unreachable and the mount work is innocent. Reachable on foot but not mounted means
     * the horse's two-cell footprint is what refuses it - which is either correct (a horse really
     * does not fit through a one-block doorway) or a bug in the sizing, and either way it is the
     * mount code that has to answer for it.
     */
    public boolean canReachOnFootSized(BlockPos target) {
        return reachesTarget(target, true);
    }

    /**
     * Is this target reachable at all, at the entity's real current sizing (mounted or not)? Used
     * to validate a blind terrain-probed detour/escape candidate before committing to it - terrain
     * standability says nothing about whether an actual route connects the two points.
     */
    public boolean isReachable(BlockPos target) {
        return reachesTarget(target, false);
    }

    /**
     * {@link #isReachable} as if the body stood at (x, feetY, z): the moving body (and its rider) is
     * shifted there for the search only, within this call, so nothing is ever sent to clients.
     */
    public boolean isReachableFrom(double x, double feetY, double z, BlockPos target) {
        net.minecraft.entity.Entity body = PathBody.of(this.entity);
        double dx = x - body.posX, dy = feetY - body.getEntityBoundingBox().minY, dz = z - body.posZ;
        net.minecraft.util.math.AxisAlignedBB bodyBox = body.getEntityBoundingBox();
        net.minecraft.util.math.AxisAlignedBB riderBox = this.entity.getEntityBoundingBox();
        double bx = body.posX, by = body.posY, bz = body.posZ;
        double rx = this.entity.posX, ry = this.entity.posY, rz = this.entity.posZ;
        try {
            shiftBy(body, dx, dy, dz);
            if (body != this.entity) shiftBy(this.entity, dx, dy, dz);
            return reachesTarget(target, false);
        } finally {
            body.posX = bx; body.posY = by; body.posZ = bz;
            body.setEntityBoundingBox(bodyBox);
            if (body != this.entity) {
                this.entity.posX = rx; this.entity.posY = ry; this.entity.posZ = rz;
                this.entity.setEntityBoundingBox(riderBox);
            }
        }
    }

    private static void shiftBy(net.minecraft.entity.Entity e, double dx, double dy, double dz) {
        e.posX += dx;
        e.posY += dy;
        e.posZ += dz;
        e.setEntityBoundingBox(e.getEntityBoundingBox().offset(dx, dy, dz));
    }

    /** How close, squared, a diagnostic route must end to its target to count as reaching it -
     * matches EntityAITravel's own partial-path tolerance (3 blocks). */
    private static final double REACH_TOLERANCE_SQ = 3.0D * 3.0D;

    /** How many candidate nodes the mounted shortcut tests per pass, farthest first. */
    private static final int MOUNTED_SHORTCUT_PROBES = 4;

    /**
     * Index of the farthest path node still within {@code maxBlocks} of the entity.
     *
     * <p>The shortcut has to be bounded by <b>distance</b>, not by a node count, and getting that
     * wrong produced the worst regression of this whole effort. Advancing "up to twelve nodes" per
     * pass is unbounded in practice: the pass repeats every few ticks, and while the horse is stuck
     * its position never changes, so each pass measures from the same spot toward an ever more
     * distant node. Caught in a trace with the path index at 46 of 47 while the entity had not left
     * the start of its route, steering at a point 28 blocks away straight through a wood. Bounding
     * by distance also bounds how far the corridor test has to be trusted, which matters because
     * three sample lines say much less about 28 blocks of forest than about 12.
     */
    private int farthestNodeWithin(Vec3d from, int curIdx, double maxBlocks) {
        double maxSq = maxBlocks * maxBlocks;
        int limit = curIdx + 1;
        for (int j = curIdx + 1; j < this.currentPath.getCurrentPathLength(); j++) {
            Vec3d v = vecForPathPoint(this.currentPath.getPathPointFromIndex(j));
            double dx = v.x - from.x;
            double dz = v.z - from.z;
            if (dx * dx + dz * dz > maxSq) break;
            limit = j;
        }
        return limit;
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
            // Not while mounted: the rider's jump helper drives a body that is being carried, so it
            // does nothing, and the horse has its own stepHeight of 1.0 anyway.
            if (stuckTicks >= 40 && this.entity.onGround && !PathBody.isMounted(this.entity)) {
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
        // comes from the horse every tick regardless. A horse clears a 1-block rise on stepHeight
        // alone; anything wider is a job for the planner, not a pedestrian ballistic hop.
        if (PathBody.isMounted(this.entity)) {
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


