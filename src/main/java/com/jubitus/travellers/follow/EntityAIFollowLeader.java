package com.jubitus.travellers.follow;

import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.ground.Footing;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.mount.MountedLeadController;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.pathing.GapJumpEvaluator;
import com.jubitus.travellers.pathing.GapJumpResult;
import com.jubitus.travellers.pathing.PathBody;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class EntityAIFollowLeader extends EntityAIBase {
    boolean idleAtSlot = false;
    private double idleSlotX;
    private double idleSlotY;
    private double idleSlotZ;
    private int wakeupCooldown = 0;

    /**
     * If the new ideal slot is within this distance of the current goal, keep the current goal
     * instead of re-evaluating. Prevents workers from chasing slowly-rotating formation slots.
     */
    private static final double SLOT_DRIFT_TOLERANCE_SQ = 1.8D * 1.8D;

    /**
     * A follower settled at its slot for this many consecutive ticks gets a much longer
     * goal-refresh cooldown — the "general walking among troops" state.
     */
    static final int SETTLED_FAST_PATH_TICKS = 4;

    /** Read by the trace tool: how close this follower is to giving up on direct steering and asking
     * for a real path. Without it a trace cannot tell "steering thinks it is fine" from "steering is
     * about to escalate", which are very different failures. */
    public int getSteeringBlockedTicks() {
        return steering.direct.steeringBlockedTicks;
    }

    /** Trace probe: "pacer" while the mounted pacer steers, else the speed policy's last asked pace. */
    public String describePace() {
        return mountedPacer.isEngaged() ? "pacer" : String.format(java.util.Locale.ROOT, "%.0f%%", speed.lastPercent);
    }

    /** Horizontal distance from the follower to its current formation goal, or -1 without one. */
    public double getProfileSlotDistance() {
        if (currentGoal == null) return -1.0D;
        double dx = currentGoal.x - follower.posX;
        double dz = currentGoal.z - follower.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static final double GOAL_EPSILON_SQ = 1.25D * 1.25D;
    private static final double TOO_CLOSE_TO_LEADER_SQ = 1.25D * 1.25D;

    private final EntityTraveller follower;
    double baseSpeed;
    private final float startDist;
    private final float stopDist;

    private final FollowBoatController boatController;
    private final FollowFlightController flightController;
    private final MountedFollowPacer mountedPacer;
    final FollowGoalResolver goals;
    final FollowPathRequester paths;
    final FollowSteering steering;
    final FollowStuckSignals stuck;
    final FollowSpeedPolicy speed;
    /**
     * Cave/tunnel mode detection AND the CATCHUP hand-off to {@code EntityAITravel}. Cave mode never
     * resolves for a non-worker follower (plain traveller/raider group) - that style of group never
     * used cave single-file geometry, and {@link FollowFormationCoordinator}'s formation-slot system,
     * the only consumer of it, is itself worker-only for now (see {@link #requireWorker()}) - but
     * CATCHUP is real for a group follower too, so this is always constructed, never {@code null}.
     */
    private final FollowModeSelector modeSelector;

    EntityLivingBase followTarget;
    FollowMode localMode = FollowMode.SURFACE;

    private double lastGoalX;
    private double lastGoalY;
    private double lastGoalZ;
    boolean hasGoal;

    /** Consecutive ticks this follower has been within arriveRadius of its slot goal. */
    int settledTicks = 0;

    private FollowMode previousEvaluatedMode = FollowMode.SURFACE;
    /** Tracks the last non-CATCHUP mode so slot refresh before catchup uses the correct mode. */
    private FollowMode stableLocalModeForSlotRefresh = FollowMode.SURFACE;
    int modeTicks = 0;
    GoalCandidate currentGoal;

    /**
     * Identity of the entity {@link #followTarget} resolved to on the previous tick. The task never
     * calls startExecuting()/resetTask() when the effective follow target switches mid-execution
     * (shouldContinueExecuting() stays true throughout — see getEffectiveFollowTarget()), so without
     * this, stagnation/recovery counters accumulated while following the OLD target (e.g. real
     * jostling in a crowd of 40 workers around the player) survive into the new target and can fire
     * a spurious FollowRecoveryHelper teleport within a tick or two of a leader reassignment, using
     * stale state that was never actually earned against the new target.
     */
    private UUID lastFollowTargetUUID;

    public EntityAIFollowLeader(EntityTraveller follower, float startDist, float stopDist) {
        this.follower = follower;
        this.baseSpeed = UnitSpeed.travelNav();
        this.startDist = startDist;
        this.stopDist = stopDist;
        this.modeSelector = new FollowModeSelector(follower);
        this.boatController = new FollowBoatController(follower);
        this.flightController = new FollowFlightController(follower);
        this.mountedPacer = new MountedFollowPacer(follower);
        this.goals = new FollowGoalResolver(this, follower);
        this.paths = new FollowPathRequester(this, follower);
        this.steering = new FollowSteering(this, follower);
        this.stuck = new FollowStuckSignals(this, follower);
        this.speed = new FollowSpeedPolicy(this, follower);
        this.setMutexBits(3);
    }

    /**
     * {@link FollowFormationCoordinator} and {@link FollowRecoveryHelper}'s worker-teleport/rescue
     * helpers are still typed to {@code EntityTravellerWorker} - every call site is itself guarded by
     * {@code instanceof EntityTravellerWorker} first (a plain traveller/raider group follower uses
     * {@link GroupFormationTracker} instead, see {@link #updateLocalFollow}), so the cast always holds
     * where it's used. Generalizing those specific helpers to {@code EntityTraveller} - so a group
     * follower gets the real formation-slot system too, instead of the ring-offset fallback - is real
     * future work, not done by this class alone.
     */
    private FollowFormationCoordinator.SlotAssignment resolveWorkerSlot(EntityLivingBase target, FollowMode mode,
                                                                        FollowRuntimeConfig cfg, double desiredDist) {
        EntityTravellerWorker w = requireWorker();
        FollowFormationCoordinator.SlotAssignment slot = pickWorkerSlot(w, target, mode, cfg, desiredDist);
        w.setEscortSlot(slot.isEscort);
        return slot;
    }

    private FollowFormationCoordinator.SlotAssignment pickWorkerSlot(EntityTravellerWorker w, EntityLivingBase target,
                                                                     FollowMode mode, FollowRuntimeConfig cfg, double desiredDist) {
        if (w.isPickupEscortActive() && target instanceof EntityPlayer) {
            return FollowFormationCoordinator.getPickupEscortSlot(w, target,
                    TravellersConfig.WORKER.pickupEscortDistance);
        }
        FollowFormationCoordinator.SlotAssignment slot = FollowFormationCoordinator.getSlot(w, target, mode, cfg, desiredDist);
        if (target instanceof EntityTravellerWorker && mode == FollowMode.SURFACE) {
            // A leader on a Go order: road file / waiting at the route anchor, else the formation as is.
            FollowFormationCoordinator.SlotAssignment route = GroupFormationTracker.routeSlotForEscort(w,
                    (EntityTravellerWorker) target, slot, desiredDist, follower.world.getTotalWorldTime());
            if (route != null) return route;
        }
        return slot;
    }

    /**
     * This follower's formation slot. A plain traveller/raider group follower uses the lighter
     * loaded-side {@link GroupFormationTracker} (real formation geometry or a fixed RING offset)
     * instead of FollowFormationCoordinator's worker-only registration/settings system; mode is
     * always SURFACE for it (modeSelector never selects CAVE for one) and the tracker ignores mode
     * anyway. The follower's own copy of the group's style (group-atomic, copied once at creation)
     * is authoritative.
     */
    private FollowFormationCoordinator.SlotAssignment resolveSlot(EntityLivingBase target, FollowMode mode,
                                                                  FollowRuntimeConfig cfg, double desiredDist) {
        if (follower.isHiredFollower()) {
            return resolveWorkerSlot(target, mode, cfg, desiredDist);
        }
        boolean mounted = PathBody.isMounted(follower) || PathBody.isMounted(target);
        return GroupFormationTracker.getGroupSlot(follower, target, follower.getGroupFormationStyle(),
                desiredDist, mounted, follower.world.getTotalWorldTime());
    }

    EntityTravellerWorker requireWorker() {
        return (EntityTravellerWorker) follower;
    }

    @Override
    public boolean shouldExecute() {
        if (!canUseFollow()) return false;

        EntityLivingBase target = follower.getEffectiveFollowTarget();
        if (!isValidTarget(target)) return false;

        this.followTarget = target;

        if (flightController.shouldHandle(target)) return true;
        if (boatController.shouldHandle(target)) return true;
        return horizontalDistanceTo(target) > startDist;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (!canUseFollow()) return false;

        EntityLivingBase current = follower.getEffectiveFollowTarget();
        if (!isValidTarget(current)) return false;

        this.followTarget = current;

        if (flightController.shouldHandle(current)) return true;
        if (boatController.shouldHandle(current)) return true;
        if (follower.isCatchupTravel()) return false;

        if (idleAtSlot) return true;
        if (mountedPacer.isEngaged()) return true;

        return horizontalDistanceTo(current) > stopDist || !follower.getNavigator().noPath();
    }

    @Override
    public void startExecuting() {
        PathNavigate nav = follower.getNavigator();
        if (nav instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) nav).setFollowerMode(true);
        }
        resetAll();
    }

    @Override
    public void resetTask() {
        PathNavigate nav = follower.getNavigator();
        if (nav instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) nav).setFollowerMode(false);
        }
        follower.getNavigator().clearPath();
        follower.setSprinting(false);
        followTarget = null;
        resetAll();
    }

    /** Everything this task tracks back to a fresh start (task start and stop). */
    private void resetAll() {
        paths.repathCooldown = 0;
        dropGoals();
        forgetGoal();
        speed.smoothedMoveSpeed = baseSpeed;
        previousEvaluatedMode = FollowMode.SURFACE;
        modeTicks = 0;
        leaveIdle();
        goals.goalRefreshCooldown = 0;
        stuck.resetEvidence();
        stuck.recoveryCooldown = 0;
        stuck.resetOrbit();
        localMode = FollowMode.SURFACE;
        modeSelector.reset();
        settledTicks = 0;
        steering.reset();
        lastFollowTargetUUID = null;
        boatController.reset();
        flightController.reset();
        mountedPacer.reset();
    }

    /** Drop the goal anchors so the next tick re-picks and re-paths (every reset does this). */
    void dropGoals() {
        hasGoal = false;
        goals.hasStickyGoal = false;
        paths.hasActivePathGoal = false;
    }

    /** Forget the current goal itself, not only its anchors (start, stop, new follow target). */
    private void forgetGoal() {
        currentGoal = null;
        goals.hasGoalEvalAnchor = false;
        goals.cachedSettleGoalX = Double.NaN;
    }

    void leaveIdle() {
        idleAtSlot = false;
        wakeupCooldown = 0;
    }

    @Override
    public void updateTask() {
        baseSpeed = UnitSpeed.travelNav();
        // Don't interfere with an active gap jump - let physics carry the entity, same guard
        // EntityAITravel uses. Without this, applyNonPathSteering's per-tick moveHelper.setMoveTo
        // toward the (different) formation goal fights the ballistic motionX/Z the jump set up,
        // which is what made jumps consistently land short once steering became the default path.
        if (TravellerNavigateGround.isMidGapJump(follower)) return;

        EntityLivingBase target = follower.getEffectiveFollowTarget();
        if (!isValidTarget(target)) {
            resetTask();
            return;
        }

        this.followTarget = target;
        // A player leader has no EntityTraveller field of its own to carry a trail on (unlike a
        // worker/traveller leader, see tryFollowLeaderTrail) - PlayerLeaderTrail is the equivalent
        // store, recorded here so it exists regardless of which follow branch runs below.
        if (target instanceof net.minecraft.entity.player.EntityPlayer) {
            PlayerLeaderTrail.record((net.minecraft.entity.player.EntityPlayer) target);
        }

        UUID targetUUID = target.getUniqueID();
        if (lastFollowTargetUUID != null && !lastFollowTargetUUID.equals(targetUUID)) {
            onFollowTargetChanged();
        }
        lastFollowTargetUUID = targetUUID;

        // Flight first: while airborne the flight controller owns movement outright, and every
        // formation/slot/stagnation measurement below assumes a walking follower on the ground.
        if (flightController.tick(target)) {
            return;
        }

        if (boatController.tick(target)) {
            return;
        }

        if (stuck.recoveryCooldown > 0) stuck.recoveryCooldown--;
        if (stuck.schedulerDeniedTicks > 0) stuck.schedulerDeniedTicks--;
        if (stuck.recentPathSuccessTicks > 0) stuck.recentPathSuccessTicks--;

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        FollowMode selected = modeSelector.select(target, stuck.pathFailTicks, stuck.stagnationTicks());
        // Stuck outside a cave with no working way to move (failed routes or blocked steering, not a
        // crowd jostle): the travel AI's ladder and its hop to a resume point take over.
        if (selected == FollowMode.SURFACE
                && stuck.stagnationTicks() >= TravellersConfig.WORKER_FORMATION.followStuckCatchupSeconds * 20
                && (stuck.pathFailTicks >= 3
                || steering.direct.steeringBlockedTicks >= TravellersConfig.WORKER_FORMATION.steeringBlockedEscalationGraceTicks)) {
            selected = FollowMode.CATCHUP;
        }

        if (selected != previousEvaluatedMode) {

            onFollowModeChanged(previousEvaluatedMode, selected, cfg);
            previousEvaluatedMode = selected;
            modeTicks = 0;
        } else {
            modeTicks++;
        }

        if (selected == FollowMode.CATCHUP) {
            // Refresh the slot goal before entering catchup so the travel AI
            // navigates to the correct formation position, not to the leader.
            FollowMode preMode = (stableLocalModeForSlotRefresh == FollowMode.CAVE)
                    ? FollowMode.CAVE : FollowMode.SURFACE;
            double desiredDist = getDesiredFollowDistance(target, preMode, cfg);
            FollowFormationCoordinator.SlotAssignment slot = resolveSlot(target, preMode, cfg, desiredDist);
            follower.notifyFollowerSlotGoal(slot.idealX, slot.idealY, slot.idealZ);

            follower.getStuckRecord().handOverToCatchup();
            follower.startCatchupTravelTo(target);
            return;
        }

        stableLocalModeForSlotRefresh = selected;
        localMode = (selected == FollowMode.CAVE) ? FollowMode.CAVE : FollowMode.SURFACE;
        updateLocalFollow(target, localMode, cfg);
    }

    private void updateLocalFollow(EntityLivingBase target, FollowMode mode, FollowRuntimeConfig cfg) {
        double desiredFollowDistance = getDesiredFollowDistance(target, mode, cfg);

        FollowFormationCoordinator.SlotAssignment slot = resolveSlot(target, mode, cfg, desiredFollowDistance);

        follower.setCachedGroupSize(slot.groupSize);

        if (!slot.workerLeader && idleAtSlot) {
            idleAtSlot = false;
            wakeupCooldown = 0;
        }

        boolean airborneLeader = mode != FollowMode.CAVE && isLeaderClearlyAirborne(target, cfg);
        boolean undergroundLeader = mode == FollowMode.CAVE || slot.underground;

        // Mounted follower behind a moving leader: continuous pace-matching replaces the whole
        // settle/re-path servo below for the tick - see MountedFollowPacer. SURFACE only: cave
        // follow is single-file geometry, and an airborne leader belongs to the flight controller.
        if (mode == FollowMode.SURFACE && !airborneLeader
                && !GroupFormationTracker.isWaitingForLeader(target.getUniqueID())
                && mountedPacer.tick(target.posX, target.posZ,
                        com.jubitus.travellers.unit.EntityMotion.velX(target), com.jubitus.travellers.unit.EntityMotion.velZ(target),
                        slot.idealX, slot.idealZ, horizontalDistanceTo(target),
                        baseSpeed,
                        isMounted() ? TravellersConfig.WORKER_FORMATION.mountedCatchUpDistanceScale : 1.0D,
                        target)) {
            follower.notifyFollowerSlotGoal(slot.idealX, slot.idealY, slot.idealZ);
            follower.setSprinting(false);
            // Real movement while pacing is progress: new cells clear the stall evidence, a wedged horse keeps it.
            follower.getStuckRecord().visit(follower.posX, follower.posY, follower.posZ);
            // Leave no half-finished servo state behind for the moment the pacer disengages - a
            // stale goal/settle carried across the handover is exactly the kind of state
            // that fires a rescue the instant the servo resumes.
            idleAtSlot = false;
            settledTicks = 0;
            paths.hasActivePathGoal = false;
            currentGoal = null;
            hasGoal = false;
            stuck.hasProgressAnchor = false;
            steering.direct.steeringBlockedTicks = 0;
            return;
        }

        GoalCandidate goal;

        // If on cooldown but the slot has moved far from currentGoal (e.g. formation heading
        // just updated), bypass the cooldown so workers immediately chase the new slot
        // instead of spinning in place while navigating toward a stale position.
        if (goals.goalRefreshCooldown > 0 && currentGoal != null) {
            double sdx = currentGoal.x - slot.idealX;
            double sdy = currentGoal.y - slot.idealY;
            double sdz = currentGoal.z - slot.idealZ;
            if (sdx * sdx + sdy * sdy + sdz * sdz > 2.0D * 2.0D) {
                goals.goalRefreshCooldown = 0;
            }
        }

        if (goals.shouldReevaluateGoal(target, slot)) {
            goal = goals.resolveGoal(target, slot, mode, cfg, airborneLeader);
            currentGoal = goal;
            follower.notifyFollowerSlotGoal(goal.x, goal.y, goal.z);
            goals.markGoalEvaluated(target, mode, slot);
        } else if (currentGoal != null) {
            goal = currentGoal;
        } else {
            goal = goals.resolveGoal(target, slot, mode, cfg, airborneLeader);
            currentGoal = goal;
            follower.notifyFollowerSlotGoal(goal.x, goal.y, goal.z);
            goals.markGoalEvaluated(target, mode, slot);
        }

        if (slot.workerLeader && currentGoal != null) {
            double curDx = follower.posX - currentGoal.x;
            double curDz = follower.posZ - currentGoal.z;
            double curDistSq = curDx * curDx + curDz * curDz;

            if (curDistSq <= 1.15D * 1.15D && !slot.leaderActuallyMoving) {
                goal = currentGoal;
            }
        }

        if (idleAtSlot) {
            if (wakeupCooldown > 0) wakeupCooldown--;

            double idleDriftSq = FollowMath.distanceSq(goal.x, goal.y, goal.z, idleSlotX, idleSlotY, idleSlotZ);
            double leaderMoveSq = FollowMath.distanceSq(
                    target.posX, target.posY, target.posZ,
                    goals.lastGoalEvalLeaderX, goals.lastGoalEvalLeaderY, goals.lastGoalEvalLeaderZ
            );

            boolean workerLeader = slot.workerLeader;

            boolean shouldWake;
            if (workerLeader) {
                // Compare the coordinator's fresh ideal position against where this follower settled.
                // This catches settings changes (style, spacing, distance) even when the leader is stopped,
                // since slot.idealX/Y/Z is always recomputed by the coordinator on each rebuild.
                double idealDriftSq = FollowMath.distanceSq(slot.idealX, slot.idealY, slot.idealZ, idleSlotX, idleSlotY, idleSlotZ);
                shouldWake = idealDriftSq > 1.5D * 1.5D
                        || (slot.leaderActuallyMoving && (
                                leaderMoveSq > 2.25D * 2.25D ||
                                        idleDriftSq > 2.4D * 2.4D ||
                                        horizontalDistanceTo(target) > stopDist + 3.0D
                        ));
            } else {
                // Player: much more sensitive wake-up.
                // NOTE: this branch is a defensive fallback only - idleAtSlot is never SET true for a
                // player leader below (see "Player leader: no long idle state"), so in practice a
                // player-followed follower never reaches this block except transiently right after its
                // leader switches from a follower to the player. The real playerCohesion lever for a
                // settled player-led escort is the fast-path tolerance further down (search
                // "Player leader: lower tolerance").
                shouldWake =
                        slot.leaderActuallyMoving
                                || leaderMoveSq > 0.60D * 0.60D
                                || idleDriftSq > 0.85D * 0.85D
                                || horizontalDistanceTo(target) > stopDist + 1.2D;
            }

            if (!shouldWake) {
                follower.getNavigator().clearPath();
                follower.setSprinting(false);
                return;
            }

            // Skip wake-up stagger when the leader has teleported far (e.g., travel AI rescue).
            // A large slot displacement means the leader jumped suddenly — followers must react
            // immediately rather than waiting for the staggered cooldown to expire.
            double idealDriftForWake = workerLeader
                    ? FollowMath.distanceSq(slot.idealX, slot.idealY, slot.idealZ, idleSlotX, idleSlotY, idleSlotZ)
                    : 0.0D;
            boolean largeLeaderJump = idealDriftForWake > 8.0D * 8.0D;

            if (workerLeader && wakeupCooldown > 0 && !largeLeaderJump) {
                follower.getNavigator().clearPath();
                follower.setSprinting(false);
                return;
            }

            idleAtSlot = false;
            goals.goalRefreshCooldown = 0;
            paths.repathCooldown = 0;
            settledTicks = 0;
        }

        double dx = follower.posX - goal.x;
        double dz = follower.posZ - goal.z;
        double dy = Math.abs(follower.posY - goal.y);
        double distGoalSq = dx * dx + dz * dz;

        applyEmergencySeparation(target);
        stuck.tickStuckSignals(goal);
        stuck.tickOrbitDetection(goal, mode, cfg);

        if (!airborneLeader && stuck.tryProgressiveRecovery(target, mode, cfg)) {
            return;
        }

        double settleRadius;
        if (Double.isNaN(goals.cachedSettleGoalX)
                || goal.x != goals.cachedSettleGoalX || goal.y != goals.cachedSettleGoalY || goal.z != goals.cachedSettleGoalZ) {
            settleRadius = goals.computeSettleRadius(slot, goal, mode);
            goals.cachedSettleRadius = settleRadius;
            goals.cachedSettleGoalX = goal.x;
            goals.cachedSettleGoalY = goal.y;
            goals.cachedSettleGoalZ = goal.z;
        } else {
            settleRadius = goals.cachedSettleRadius;
        }
        double verticalTolerance = mode == FollowMode.CAVE ? cfg.cave.verticalTolerance : cfg.surface.verticalTolerance;
        boolean closeEnoughToSlot = distGoalSq <= (settleRadius * settleRadius)
                && (airborneLeader || dy <= verticalTolerance);

        // ── Settled counter ───────────────────────────────────────────────────
        // Track consecutive ticks the follower is within its slot. Once settled for
        // SETTLED_FAST_PATH_TICKS ticks, the follower enters a low-cost idle state:
        // it stops navigating and only wakes up when the leader genuinely moves away
        // or the slot drifts far enough. This is the "general among troops" state.
        if (closeEnoughToSlot) {
            settledTicks = Math.min(settledTicks + 1, SETTLED_FAST_PATH_TICKS * 3);
        } else {
            settledTicks = Math.max(0, settledTicks - 2); // decay faster than increment
        }

        boolean workerLeader = slot.workerLeader;

        if (closeEnoughToSlot && !slot.leaderActuallyMoving) {
            follower.getNavigator().clearPath();
            follower.setSprinting(false);
            paths.hasActivePathGoal = false;

            // Hard idle only when the leader is a follower.
            // For player leaders, stay reactive to small movements.
            if (workerLeader) {
                idleAtSlot = true;
                idleSlotX = goal.x;
                idleSlotY = goal.y;
                idleSlotZ = goal.z;

                // Keep the sticky goal, don't throw it away
                currentGoal = goal;
                hasGoal = true;

                wakeupCooldown = 10 + Math.abs(follower.getEntityId() * 17) % 10;
                return;
            }

            // Player leader: no long idle state
            idleAtSlot = false;
            wakeupCooldown = 0;
            settledTicks = Math.min(settledTicks, SETTLED_FAST_PATH_TICKS);
        }

        // Fast-path for settled workers: even when the leader IS moving, if this follower
        // is well within its slot, only react when the slot has drifted significantly.
        // This prevents the whole formation from re-pathing in unison on every heading
        // update — the "orbiting around the leader" symptom.
        if (settledTicks >= SETTLED_FAST_PATH_TICKS && closeEnoughToSlot) {
            if (slot.workerLeader) {
                // Worker leader: strong damping to avoid synchronized wake-up across the group
                double idealDx = follower.posX - slot.idealX;
                double idealDz = follower.posZ - slot.idealZ;
                double idealDriftSq = idealDx * idealDx + idealDz * idealDz;

                if (idealDriftSq < SLOT_DRIFT_TOLERANCE_SQ * 2.0D) {
                    if (follower.getNavigator().noPath()) return;
                    paths.repathCooldown = Math.max(paths.repathCooldown, 6);
                    return;
                }

                settledTicks = 0;
                goals.goalRefreshCooldown = 0;
            } else {
                // Player leader: lower tolerance, react quickly - and the higher playerCohesion is,
                // the tighter that tolerance gets, so a settled escort re-paths toward its slot on a
                // smaller drift instead of only when a visible gap has already opened up. This is the
                // lever that actually runs continuously for a player-led escort (unlike the idleAtSlot
                // wake-up thresholds above, which player leaders never idle into).
                double tolerance = 0.75D * (1.0D - 0.6D * follower.getCachedPlayerCohesion());
                double idealDx = follower.posX - slot.idealX;
                double idealDz = follower.posZ - slot.idealZ;
                double idealDriftSq = idealDx * idealDx + idealDz * idealDz;

                if (idealDriftSq < tolerance * tolerance && !slot.leaderActuallyMoving) {
                    if (follower.getNavigator().noPath()) return;
                    paths.repathCooldown = Math.max(paths.repathCooldown, 2);
                    return;
                }

                settledTicks = 0;
                goals.goalRefreshCooldown = 0;
            }
        }
        if (paths.repathCooldown > 0) {
            paths.repathCooldown--;
        }

        // Capture previous anchor BEFORE updating, then update unconditionally so
        // goalChanged only fires for single-tick jumps (teleports, large formation
        // changes) — not for ordinary movement-induced slot drift.
        // BUG that was here: lastGoalX was only updated when we didn't return early,
        // causing 1.25-block drift to accumulate and bypass repathCooldown every ~10
        // ticks instead of the designed 24, multiplying pathfinder calls by ~2x.
        boolean prevHasGoal = hasGoal;
        double prevGoalX = lastGoalX, prevGoalY = lastGoalY, prevGoalZ = lastGoalZ;
        lastGoalX = goal.x;
        lastGoalY = goal.y;
        lastGoalZ = goal.z;
        hasGoal = true;

        // Same widening as shouldReevaluateGoal, and for the same reason: this epsilon gates whether
        // an ordinary repathCooldown is honoured at all (see the "!goalChanged && repathCooldown > 0"
        // check below) - at the pedestrian-tuned 1.25 blocks, a mounted follower's own drift crosses
        // it almost every tick and bypasses the cooldown into a fresh search each time.
        double goalEpsilonSq = GOAL_EPSILON_SQ * (isMounted()
                ? TravellersConfig.WORKER_FORMATION.mountedCatchUpDistanceScale * TravellersConfig.WORKER_FORMATION.mountedCatchUpDistanceScale
                : 1.0D);
        boolean goalChanged = !prevHasGoal || FollowMath.distanceSq(goal.x, goal.y, goal.z, prevGoalX, prevGoalY, prevGoalZ) > goalEpsilonSq;
        boolean noPath = follower.getNavigator().noPath();
        boolean canReusePath = paths.shouldReuseCurrentPath(goal);

        double distToLeader = horizontalDistanceTo(target);
        double distToGoal = Math.sqrt(distGoalSq);
        double moveSpeed = speed.computeMoveSpeed(target, slot, distToLeader, distToGoal, mode, airborneLeader);
        // Leading a bonded horse on foot over dry ground: hang back so the leash doesn't drag it
        // (no-op unless leading the horse on foot - see MountedLeadController).
        moveSpeed *= MountedLeadController.ledRiderPaceMultiplier(follower);

        // Steering first: A* only once steering has been blocked steeringStuckTicksBeforePathfind ticks in a row.
        // A path whose goal has drifted is dropped for steering when it would be redone, rather than re-searched.
        boolean steeringFresh = steering.direct.steeringBlockedTicks < TravellersConfig.WORKER_FORMATION.steeringStuckTicksBeforePathfind;
        if (steeringFresh && !noPath && !canReusePath && paths.repathCooldown <= 0) {
            follower.getNavigator().clearPath();
            noPath = true;
        }
        if (noPath && steeringFresh) {
            steering.applyNonPathSteering(goal, target, moveSpeed, airborneLeader, mode);
            follower.setSprinting(false);
            return;
        }

        if (canReusePath && paths.repathCooldown > 0) {
            return;
        }

        if (!goalChanged && paths.repathCooldown > 0 && !noPath) {
            return;
        }

        paths.requestPath(target, goal, slot, mode, noPath, distToGoal, distToLeader, moveSpeed, airborneLeader);
    }

    /**
     * The follower's effective follow target switched entity (e.g. WorkerGroupService assigned it to a
     * new leader, or its old leader's follow order was cleared) while this AI task kept running
     * uninterrupted. Treats it like a fresh start against the new target: every stagnation/recovery
     * counter earned against the OLD target is wiped so the next few ticks judge progress purely
     * against the new one, instead of a lucky/unlucky streak from before the switch immediately
     * triggering (or suppressing) a teleport rescue that has nothing to do with the new target.
     */
    private void onFollowTargetChanged() {
        stuck.resetEvidence();
        stuck.resetOrbit();
        paths.repathCooldown = 0;
        dropGoals();
        forgetGoal();
        settledTicks = 0;
        leaveIdle();
        steering.reset();

        // Small grace window before the teleport-rescue heuristics can fire again, so ordinary
        // pathfinding gets a fair first attempt at reaching the new target before anything escalates.
        stuck.recoveryCooldown = Math.max(stuck.recoveryCooldown, FollowRuntimeConfig.get().rescue.cooldownTicks);

        follower.getNavigator().clearPath();
    }

    /** Single mount-awareness gate for this task - see {@code PathBody}. */
    boolean isMounted() {
        return com.jubitus.travellers.pathing.PathBody.isMounted(follower);
    }

    private double getDesiredFollowDistance(EntityLivingBase target, FollowMode mode, FollowRuntimeConfig cfg) {
        if (mode == FollowMode.CAVE) {
            return cfg.cave.baseLeaderDistance;
        }
        if (FollowMath.isHiredWorker(target)) {
            return Math.max(cfg.surface.baseLeaderDistanceWorker, stopDist + 1.8D);
        }
        return Math.max(cfg.surface.baseLeaderDistancePlayer, stopDist + 1.3D);
    }

    private boolean canUseFollow() {
        return followBlocker() == null;
    }

    /** The first gate that keeps this unit from following, or null when none does. */
    @Nullable
    private String followBlocker() {
        EntityLivingBase atk = follower.getAttackTarget();
        if (atk != null) return "attackTarget=" + describe(atk);
        if (follower.isInCombat()) return "inCombat";
        EntityLivingBase rev = follower.getRevengeTarget();
        if (rev != null && !rev.isDead) return "revengeTarget=" + describe(rev);
        if (follower.isCatchupTravel()) return "catchup";
        // Dispersed in the village its leader is visiting (EntityAIVillageVisit), until it leaves.
        if (follower.isDispersedInVillage()) return "dispersedInVillage";
        // An unhired worker follows only as a merchant's escort (its spawn group, like a traveller group
        // follower); its own follow order is a hired-worker notion and stale on it.
        if (follower instanceof EntityTravellerWorker && !follower.isHiredFollower()) {
            if (follower.getGroupLeaderUUID() == null) return "notHired";
        } else if (!follower.isFollowOrderActive()) {
            return "noFollowOrder";
        }
        if (follower.isJobActive() && !follower.isReturnUsingFollow()) return "jobActive";
        return null;
    }

    /** Why this task is not running, for the trace: a gate, no target, or already close enough. */
    public String whyNotRunning() {
        String gate = followBlocker();
        if (gate != null) return gate;
        EntityLivingBase target = follower.getEffectiveFollowTarget();
        if (!isValidTarget(target)) return "noTarget";
        return String.format(java.util.Locale.ROOT, "close(%.1f<=%.1f)", horizontalDistanceTo(target), startDist);
    }

    private String describe(EntityLivingBase e) {
        return String.format(java.util.Locale.ROOT, "%s%s@%.1f", e.getClass().getSimpleName(),
                e.isDead ? "(dead)" : "", Math.sqrt(follower.getDistanceSq(e)));
    }

    private boolean isValidTarget(@Nullable EntityLivingBase target) {
        if (target == null || target.isDead) return false;
        return !(target instanceof EntityPlayer) || !((EntityPlayer) target).isSpectator();
    }

    private boolean hasValidCombatTarget() {
        EntityLivingBase atk = follower.getAttackTarget();
        if (atk != null && !atk.isDead) return true;

        EntityLivingBase rev = follower.getRevengeTarget();
        return rev != null && !rev.isDead;
    }

    private boolean isLeaderClearlyAirborne(EntityLivingBase leader, FollowRuntimeConfig cfg) {
        if (leader == null) return false;
        if (leader.isRiding() && leader.getRidingEntity() instanceof EntityBoat) return false;
        if (leader.isInWater()) return false;
        if (FollowRecoveryHelper.isLeaderMeaningfullyUnderground(follower.world, leader, cfg)) return false;

        BlockPos feet = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);
        if (!follower.world.isBlockLoaded(feet)) return false;

        int groundY = ColumnGround.topLayer(follower.world, feet.getX(), feet.getZ(), true);
        if (groundY == ColumnGround.NONE) return false;
        BlockPos top = new BlockPos(feet.getX(), groundY, feet.getZ());
        int gap = feet.getY() - top.getY();

        return !leader.onGround && gap >= 5 && follower.world.canSeeSky(top);
    }

    double horizontalDistanceTo(EntityLivingBase target) {
        double dx = follower.posX - target.posX;
        double dz = follower.posZ - target.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void applyEmergencySeparation(EntityLivingBase leader) {
        boolean workerLeader = FollowMath.isHiredWorker(leader);

        double protectRadius = workerLeader ? 3.15D : Math.sqrt(TOO_CLOSE_TO_LEADER_SQ);
        double d2 = follower.getDistanceSq(leader);
        if (d2 > protectRadius * protectRadius) return;

        double dx = follower.posX - leader.posX;
        double dz = follower.posZ - leader.posZ;
        double lenSq = dx * dx + dz * dz;

        if (lenSq < 1.0E-6D) {
            int side = ((follower.getUniqueID().hashCode() >>> 2) & 1) == 0 ? -1 : 1;
            dx = side;
            dz = 0.0D;
            lenSq = 1.0D;
        }

        double len = Math.sqrt(lenSq);
        dx /= len;
        dz /= len;

        double strength = workerLeader ? 0.11D : 0.05D;
        follower.motionX += dx * strength;
        follower.motionZ += dz * strength;
    }

    /**
     * True while the given follow target is actually swimming (in water, not riding a boat) -
     * the shared condition for treating open water as a legitimate follow medium instead of an
     * obstacle, both for goal placement and for steering (see the local {@code waterIsSafe} in
     * {@link FollowSteering#applyNonPathSteering}).
     */
    boolean isLeaderSwimming(EntityLivingBase target) {
        return target.isInWater() && !(target.isRiding() && target.getRidingEntity() instanceof EntityBoat);
    }

    private void onFollowModeChanged(FollowMode oldMode, FollowMode newMode, FollowRuntimeConfig cfg) {
        if (oldMode == newMode) return;

        dropGoals();
        paths.repathCooldown = 0;
        stuck.resetOrbit();
        leaveIdle();
        settledTicks = 0;

        // Not resetEvidence(): recentPathSuccessTicks is raised or kept here, never zeroed.
        if (newMode == FollowMode.CAVE) {
            stuck.pathFailTicks = 0;
            stuck.failedAllowedPathTicks = 0;
            stuck.activePathTicks = 0;
            // Workers on the surface while the leader is underground can't get active paths —
            // skip the recent-path delay so cave rescue can fire quickly for them.
            boolean outsideUnderground = followTarget != null
                    && FollowRecoveryHelper.isWorkerOutsideWhileLeaderUnderground(follower, followTarget);
            if (!outsideUnderground) {
                stuck.recentPathSuccessTicks = Math.max(stuck.recentPathSuccessTicks, 20);
            }
            stuck.schedulerDeniedTicks = 0;
            stuck.hasProgressAnchor = false;
            stuck.progressedTowardGoalLastTick = true;
        }
    }

}