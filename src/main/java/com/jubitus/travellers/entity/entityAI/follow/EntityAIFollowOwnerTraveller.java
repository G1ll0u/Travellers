package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityAI.travel.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.GapJumpEvaluator;
import com.jubitus.travellers.utils.world.pathing.GapJumpResult;
import com.jubitus.travellers.utils.world.pathing.TravellerNavigateGround;
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

public class EntityAIFollowOwnerTraveller extends EntityAIBase {
    private boolean idleAtSlot = false;
    private double idleSlotX;
    private double idleSlotY;
    private double idleSlotZ;
    private int wakeupCooldown = 0;

    private static final double DIRECT_STEER_STOP_NEAR_SLOT_SQ = 1.35D * 1.35D;
    private static final double DIRECT_STEER_STOP_WORKER_LEADER_SQ = 1.75D * 1.75D;

    private static final int HAZARD_LOOKAHEAD_STEPS = 3;
    private static final double HAZARD_LOOKAHEAD_STEP = 0.85D;
    private static final int EDGE_DANGER_DEPTH = 2;

    /**
     * If the new ideal slot is within this distance of the current goal, keep the current goal
     * instead of re-evaluating. Prevents workers from chasing slowly-rotating formation slots.
     */
    private static final double SLOT_DRIFT_TOLERANCE_SQ = 1.8D * 1.8D;

    /**
     * A worker settled at its slot for this many consecutive ticks gets a much longer
     * goal-refresh cooldown — the "general walking among troops" state.
     */
    private static final int SETTLED_FAST_PATH_TICKS = 4;

    private double activePathGoalX;
    private double activePathGoalY;
    private double activePathGoalZ;
    private boolean hasActivePathGoal;

    /** Consecutive ticks the straight line to the goal has been hazard-blocked with no usable detour. */
    private int steeringBlockedTicks;

    /** Read by the trace tool: how close this follower is to giving up on direct steering and asking
     * for a real path. Without it a trace cannot tell "steering thinks it is fine" from "steering is
     * about to escalate", which are very different failures. */
    public int getSteeringBlockedTicks() {
        return steeringBlockedTicks;
    }
    private int deflectionRecheckCooldown;
    private double cachedDeflectDirX;
    private double cachedDeflectDirZ;
    private boolean hasCachedDeflectDir;

    private static final double GOAL_REEVAL_LEADER_MOVE_SQ = 1.5D * 1.5D;
    private static final double GOAL_REEVAL_WORKER_MOVE_SQ = 1.0D * 1.0D;

    private int goalRefreshCooldown;
    private double lastGoalEvalLeaderX, lastGoalEvalLeaderY, lastGoalEvalLeaderZ;
    private double lastGoalEvalWorkerX, lastGoalEvalWorkerY, lastGoalEvalWorkerZ;
    private boolean hasGoalEvalAnchor;

    private static final double GOAL_EPSILON_SQ = 1.25D * 1.25D;
    private static final double STICKY_GOAL_EPSILON_SQ = 1.9D * 1.9D;
    private static final double TOO_CLOSE_TO_LEADER_SQ = 1.25D * 1.25D;

    private static final int SCHEDULER_DENIAL_BLOCK_TELEPORT_TICKS = 60;
    private static final int RECENT_PATH_SUCCESS_BLOCK_TELEPORT_TICKS = 80;

    private final EntityTravellerWorker worker;
    private final double baseSpeed;
    private final float startDist;
    private final float stopDist;

    private final FollowBoatController boatController;
    private final FollowFlightController flightController;
    private final FollowModeSelector modeSelector;

    private EntityLivingBase followTarget;
    private FollowMode localMode = FollowMode.SURFACE;

    private int repathCooldown;
    private int pathFailTicks;

    private double lastGoalX;
    private double lastGoalY;
    private double lastGoalZ;
    private boolean hasGoal;

    private double stickyGoalX;
    private double stickyGoalY;
    private double stickyGoalZ;
    private boolean hasStickyGoal;

    private double smoothedMoveSpeed;

    private int stagnationTicks;
    private int failedAllowedPathTicks;
    private int schedulerDeniedTicks;
    private int recentPathSuccessTicks;
    private int activePathTicks;
    private int recoveryCooldown;

    private double lastProgressX;
    private double lastProgressY;
    private double lastProgressZ;
    private boolean hasProgressAnchor;

    /**
     * Best (smallest) squared distance-to-goal seen since the anchor was last reset. Stagnation is
     * judged against this rather than raw per-tick movement - see {@link #tickStuckSignals}.
     */
    private double bestGoalDistSq = Double.MAX_VALUE;
    private static final double PROGRESS_EPSILON_SQ = 0.3D * 0.3D;

    /**
     * Whether the worker made real progress toward its goal on the most recent
     * {@link #tickStuckSignals} pass. Used by {@link #applyNonPathSteering} to decide whether an
     * obstacle deflection is actually working, as opposed to merely being possible - see its own
     * comment for why "a hazard-free direction exists" alone is not a safe signal to reset on.
     */
    private boolean progressedTowardGoalLastTick = true;

    private int orbitTicks;
    private double lastOrbitAngle;
    private boolean hasOrbitAngle;

    private double cachedSettleRadius;
    private double cachedSettleGoalX = Double.NaN;
    private double cachedSettleGoalY = Double.NaN;
    private double cachedSettleGoalZ = Double.NaN;

    /** Consecutive ticks this worker has been within arriveRadius of its slot goal. */
    private int settledTicks = 0;

    private FollowMode previousEvaluatedMode = FollowMode.SURFACE;
    /** Tracks the last non-CATCHUP mode so slot refresh before catchup uses the correct mode. */
    private FollowMode stableLocalModeForSlotRefresh = FollowMode.SURFACE;
    private int modeTicks = 0;
    private GoalCandidate currentGoal;

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

    public EntityAIFollowOwnerTraveller(EntityTravellerWorker worker, double speed, float startDist, float stopDist) {
        this.worker = worker;
        this.baseSpeed = speed;
        this.startDist = startDist;
        this.stopDist = stopDist;
        this.modeSelector = new FollowModeSelector(worker);
        this.boatController = new FollowBoatController(worker, speed);
        this.flightController = new FollowFlightController(worker);
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        if (!canUseFollow()) return false;

        EntityLivingBase target = worker.getEffectiveFollowTarget();
        if (!isValidTarget(target)) return false;

        this.followTarget = target;

        if (flightController.shouldHandle(target)) return true;
        if (boatController.shouldHandle(target)) return true;
        return horizontalDistanceTo(target) > startDist;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (!canUseFollow()) return false;

        EntityLivingBase current = worker.getEffectiveFollowTarget();
        if (!isValidTarget(current)) return false;

        this.followTarget = current;

        if (flightController.shouldHandle(current)) return true;
        if (boatController.shouldHandle(current)) return true;
        if (worker.isCatchupTravel()) return false;

        if (idleAtSlot) return true;

        return horizontalDistanceTo(current) > stopDist || !worker.getNavigator().noPath();
    }

    @Override
    public void startExecuting() {
        repathCooldown = 0;
        pathFailTicks = 0;
        hasGoal = false;
        hasStickyGoal = false;
        smoothedMoveSpeed = baseSpeed;
        previousEvaluatedMode = FollowMode.SURFACE;
        modeTicks = 0;
        currentGoal = null;
        hasActivePathGoal = false;

        idleAtSlot = false;
        wakeupCooldown = 0;

        goalRefreshCooldown = 0;
        hasGoalEvalAnchor = false;

        stagnationTicks = 0;
        failedAllowedPathTicks = 0;
        schedulerDeniedTicks = 0;
        recentPathSuccessTicks = 0;
        activePathTicks = 0;
        recoveryCooldown = 0;
        hasProgressAnchor = false;
        progressedTowardGoalLastTick = true;

        orbitTicks = 0;
        hasOrbitAngle = false;
        localMode = FollowMode.SURFACE;
        modeSelector.reset();
        cachedSettleGoalX = Double.NaN;
        settledTicks = 0;

        steeringBlockedTicks = 0;
        deflectionRecheckCooldown = 0;
        hasCachedDeflectDir = false;

        lastFollowTargetUUID = null;

        PathNavigate nav = worker.getNavigator();
        if (nav instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) nav).setFollowerMode(true);
        }

        boatController.reset();
        flightController.reset();
    }

    @Override
    public void resetTask() {
        PathNavigate nav = worker.getNavigator();
        if (nav instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) nav).setFollowerMode(false);
        }
        hasActivePathGoal = false;
        goalRefreshCooldown = 0;
        hasGoalEvalAnchor = false;

        idleAtSlot = false;
        wakeupCooldown = 0;

        previousEvaluatedMode = FollowMode.SURFACE;
        modeTicks = 0;
        currentGoal = null;

        worker.getNavigator().clearPath();
        worker.setSprinting(false);

        followTarget = null;
        repathCooldown = 0;
        pathFailTicks = 0;
        hasGoal = false;
        hasStickyGoal = false;
        smoothedMoveSpeed = baseSpeed;

        stagnationTicks = 0;
        failedAllowedPathTicks = 0;
        schedulerDeniedTicks = 0;
        recentPathSuccessTicks = 0;
        activePathTicks = 0;
        recoveryCooldown = 0;
        hasProgressAnchor = false;
        progressedTowardGoalLastTick = true;

        orbitTicks = 0;
        hasOrbitAngle = false;
        localMode = FollowMode.SURFACE;
        modeSelector.reset();
        cachedSettleGoalX = Double.NaN;
        settledTicks = 0;

        steeringBlockedTicks = 0;
        deflectionRecheckCooldown = 0;
        hasCachedDeflectDir = false;

        lastFollowTargetUUID = null;

        boatController.reset();
        flightController.reset();
    }

    @Override
    public void updateTask() {
        // Don't interfere with an active gap jump - let physics carry the entity, same guard
        // EntityAITravel uses. Without this, applyNonPathSteering's per-tick moveHelper.setMoveTo
        // toward the (different) formation goal fights the ballistic motionX/Z the jump set up,
        // which is what made jumps consistently land short once steering became the default path.
        PathNavigate navCheck = worker.getNavigator();
        if (navCheck instanceof TravellerNavigateGround && ((TravellerNavigateGround) navCheck).isGapJumping()) {
            return;
        }

        EntityLivingBase target = worker.getEffectiveFollowTarget();
        if (!isValidTarget(target)) {
            resetTask();
            return;
        }

        this.followTarget = target;

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

        if (recoveryCooldown > 0) recoveryCooldown--;
        if (schedulerDeniedTicks > 0) schedulerDeniedTicks--;
        if (recentPathSuccessTicks > 0) recentPathSuccessTicks--;

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        FollowMode selected = modeSelector.select(target, pathFailTicks, stagnationTicks);

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
            FollowFormationCoordinator.SlotAssignment slot =
                    FollowFormationCoordinator.getSlot(worker, target, preMode, cfg, desiredDist);
            worker.notifyFollowerSlotGoal(slot.idealX, slot.idealY, slot.idealZ);

            worker.startCatchupTravelTo(target);
            return;
        }

        stableLocalModeForSlotRefresh = selected;
        localMode = (selected == FollowMode.CAVE) ? FollowMode.CAVE : FollowMode.SURFACE;
        updateLocalFollow(target, localMode, cfg);
    }

    private void updateLocalFollow(EntityLivingBase target, FollowMode mode, FollowRuntimeConfig cfg) {
        double desiredFollowDistance = getDesiredFollowDistance(target, mode, cfg);

        FollowFormationCoordinator.SlotAssignment slot =
                FollowFormationCoordinator.getSlot(worker, target, mode, cfg, desiredFollowDistance);

        worker.setCachedGroupSize(slot.groupSize);

        if (!slot.workerLeader && idleAtSlot) {
            idleAtSlot = false;
            wakeupCooldown = 0;
        }

        boolean airborneLeader = mode != FollowMode.CAVE && isLeaderClearlyAirborne(target, cfg);
        boolean undergroundLeader = mode == FollowMode.CAVE || slot.underground;

        GoalCandidate goal;
        boolean leaderStill = Math.abs(target.posX - target.prevPosX) < 0.04D
                && Math.abs(target.posZ - target.prevPosZ) < 0.04D;



        // If on cooldown but the slot has moved far from currentGoal (e.g. formation heading
        // just updated), bypass the cooldown so workers immediately chase the new slot
        // instead of spinning in place while navigating toward a stale position.
        if (goalRefreshCooldown > 0 && currentGoal != null) {
            double sdx = currentGoal.x - slot.idealX;
            double sdy = currentGoal.y - slot.idealY;
            double sdz = currentGoal.z - slot.idealZ;
            if (sdx * sdx + sdy * sdy + sdz * sdz > 2.0D * 2.0D) {
                goalRefreshCooldown = 0;
            }
        }

        if (shouldReevaluateGoal(target, mode, slot)) {
            goal = resolveGoal(target, slot, mode, cfg, airborneLeader);
            currentGoal = goal;
            worker.notifyFollowerSlotGoal(goal.x, goal.y, goal.z);
            markGoalEvaluated(target, mode, slot);
        } else if (currentGoal != null) {
            goal = currentGoal;
        } else {
            goal = resolveGoal(target, slot, mode, cfg, airborneLeader);
            currentGoal = goal;
            worker.notifyFollowerSlotGoal(goal.x, goal.y, goal.z);
            markGoalEvaluated(target, mode, slot);
        }

        if (slot.workerLeader && currentGoal != null) {
            double curDx = worker.posX - currentGoal.x;
            double curDz = worker.posZ - currentGoal.z;
            double curDistSq = curDx * curDx + curDz * curDz;

            if (curDistSq <= 1.15D * 1.15D && !slot.leaderActuallyMoving) {
                goal = currentGoal;
            }
        }

        if (idleAtSlot) {
            if (wakeupCooldown > 0) wakeupCooldown--;

            double idleDriftSq = distanceSq(goal.x, goal.y, goal.z, idleSlotX, idleSlotY, idleSlotZ);
            double leaderMoveSq = distanceSq(
                    target.posX, target.posY, target.posZ,
                    lastGoalEvalLeaderX, lastGoalEvalLeaderY, lastGoalEvalLeaderZ
            );

            boolean workerLeader = slot.workerLeader;

            boolean shouldWake;
            if (workerLeader) {
                // Compare the coordinator's fresh ideal position against where this worker settled.
                // This catches settings changes (style, spacing, distance) even when the leader is stopped,
                // since slot.idealX/Y/Z is always recomputed by the coordinator on each rebuild.
                double idealDriftSq = distanceSq(slot.idealX, slot.idealY, slot.idealZ, idleSlotX, idleSlotY, idleSlotZ);
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
                // player-followed worker never reaches this block except transiently right after its
                // leader switches from a worker to the player. The real playerCohesion lever for a
                // settled player-led escort is the fast-path tolerance further down (search
                // "Player leader: lower tolerance").
                shouldWake =
                        slot.leaderActuallyMoving
                                || leaderMoveSq > 0.60D * 0.60D
                                || idleDriftSq > 0.85D * 0.85D
                                || horizontalDistanceTo(target) > stopDist + 1.2D;
            }

            if (!shouldWake) {
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
                return;
            }

            // Skip wake-up stagger when the leader has teleported far (e.g., travel AI rescue).
            // A large slot displacement means the leader jumped suddenly — followers must react
            // immediately rather than waiting for the staggered cooldown to expire.
            double idealDriftForWake = workerLeader
                    ? distanceSq(slot.idealX, slot.idealY, slot.idealZ, idleSlotX, idleSlotY, idleSlotZ)
                    : 0.0D;
            boolean largeLeaderJump = idealDriftForWake > 8.0D * 8.0D;

            if (workerLeader && wakeupCooldown > 0 && !largeLeaderJump) {
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
                return;
            }

            idleAtSlot = false;
            goalRefreshCooldown = 0;
            repathCooldown = 0;
            settledTicks = 0;
        }

        double dx = worker.posX - goal.x;
        double dz = worker.posZ - goal.z;
        double dy = Math.abs(worker.posY - goal.y);
        double distGoalSq = dx * dx + dz * dz;

        applyEmergencySeparation(target);
        tickStuckSignals(goal);
        tickOrbitDetection(goal, mode, cfg);

        if (!airborneLeader && tryProgressiveRecovery(target, mode, cfg)) {
            return;
        }

        double settleRadius;
        if (Double.isNaN(cachedSettleGoalX)
                || goal.x != cachedSettleGoalX || goal.y != cachedSettleGoalY || goal.z != cachedSettleGoalZ) {
            settleRadius = computeSettleRadius(slot, goal, mode);
            cachedSettleRadius = settleRadius;
            cachedSettleGoalX = goal.x;
            cachedSettleGoalY = goal.y;
            cachedSettleGoalZ = goal.z;
        } else {
            settleRadius = cachedSettleRadius;
        }
        double verticalTolerance = mode == FollowMode.CAVE ? cfg.cave.verticalTolerance : cfg.surface.verticalTolerance;
        boolean closeEnoughToSlot = distGoalSq <= (settleRadius * settleRadius)
                && (airborneLeader || dy <= verticalTolerance);

        // ── Settled counter ───────────────────────────────────────────────────
        // Track consecutive ticks the worker is within its slot. Once settled for
        // SETTLED_FAST_PATH_TICKS ticks, the worker enters a low-cost idle state:
        // it stops navigating and only wakes up when the leader genuinely moves away
        // or the slot drifts far enough. This is the "general among troops" state.
        if (closeEnoughToSlot) {
            settledTicks = Math.min(settledTicks + 1, SETTLED_FAST_PATH_TICKS * 3);
        } else {
            settledTicks = Math.max(0, settledTicks - 2); // decay faster than increment
        }

        boolean workerLeader = slot.workerLeader;

        if (closeEnoughToSlot && !slot.leaderActuallyMoving) {
            worker.getNavigator().clearPath();
            worker.setSprinting(false);
            hasActivePathGoal = false;

            // Hard idle only when the leader is a worker.
            // For player leaders, stay reactive to small movements.
            if (workerLeader) {
                idleAtSlot = true;
                idleSlotX = goal.x;
                idleSlotY = goal.y;
                idleSlotZ = goal.z;

                // On garde le goal sticky, on ne le jette pas
                currentGoal = goal;
                hasGoal = true;

                wakeupCooldown = 10 + Math.abs(worker.getEntityId() * 17) % 10;
                return;
            }

            // Player leader: no long idle state
            idleAtSlot = false;
            wakeupCooldown = 0;
            settledTicks = Math.min(settledTicks, SETTLED_FAST_PATH_TICKS);
        }

        // Fast-path for settled workers: even when the leader IS moving, if this worker
        // is well within its slot, only react when the slot has drifted significantly.
        // This prevents the whole formation from re-pathing in unison on every heading
        // update — the "orbiting around the leader" symptom.
        if (settledTicks >= SETTLED_FAST_PATH_TICKS && closeEnoughToSlot) {
            if (slot.workerLeader) {
                // Worker leader: strong damping to avoid synchronized wake-up across the group
                double idealDx = worker.posX - slot.idealX;
                double idealDz = worker.posZ - slot.idealZ;
                double idealDriftSq = idealDx * idealDx + idealDz * idealDz;

                if (idealDriftSq < SLOT_DRIFT_TOLERANCE_SQ * 2.0D) {
                    if (worker.getNavigator().noPath()) return;
                    repathCooldown = Math.max(repathCooldown, 6);
                    return;
                }

                settledTicks = 0;
                goalRefreshCooldown = 0;
            } else {
                // Player leader: lower tolerance, react quickly - and the higher playerCohesion is,
                // the tighter that tolerance gets, so a settled escort re-paths toward its slot on a
                // smaller drift instead of only when a visible gap has already opened up. This is the
                // lever that actually runs continuously for a player-led escort (unlike the idleAtSlot
                // wake-up thresholds above, which player leaders never idle into).
                double tolerance = 0.75D * (1.0D - 0.6D * worker.getCachedPlayerCohesion());
                double idealDx = worker.posX - slot.idealX;
                double idealDz = worker.posZ - slot.idealZ;
                double idealDriftSq = idealDx * idealDx + idealDz * idealDz;

                if (idealDriftSq < tolerance * tolerance && !slot.leaderActuallyMoving) {
                    if (worker.getNavigator().noPath()) return;
                    repathCooldown = Math.max(repathCooldown, 2);
                    return;
                }

                settledTicks = 0;
                goalRefreshCooldown = 0;
            }
        }
        if (repathCooldown > 0) {
            repathCooldown--;
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

        boolean goalChanged = !prevHasGoal || distanceSq(goal.x, goal.y, goal.z, prevGoalX, prevGoalY, prevGoalZ) > GOAL_EPSILON_SQ;
        boolean noPath = worker.getNavigator().noPath();
        boolean canReusePath = shouldReuseCurrentPath(goal);

        double distToLeader = horizontalDistanceTo(target);
        double distToGoal = Math.sqrt(distGoalSq);
        double moveSpeed = computeMoveSpeed(target, slot, distToLeader, distToGoal, mode, airborneLeader);

        // Prefer cheap direct steering over a full A* search - most of the time a follower's slot
        // is reachable in a straight line (open ground, or a gap it can now jump), and steering
        // already handles both at a fraction of pathfinding's cost. Only escalate to a real path
        // once steering has reported being stuck for several ticks in a row (steeringBlockedTicks,
        // raised by applyNonPathSteering itself when its own obstacle-deflection and
        // shared-corridor fallback both fail) - a single bad tick doesn't trigger it, so the two
        // modes don't flip-flop. A worker already on a real path (noPath() false) skips this and
        // falls through to the existing cooldown/reuse logic below instead, so a path already in
        // progress is followed through to completion - crossing whatever obstacle needed it in
        // the first place never stops or stutters mid-way.
        if (noPath && steeringBlockedTicks < TravellersConfig.WORKER_FORMATION.steeringStuckTicksBeforePathfind) {
            applyNonPathSteering(goal, target, moveSpeed, airborneLeader, mode);
            worker.setSprinting(false);
            return;
        }

        if (canReusePath && repathCooldown > 0) {
            return;
        }

        if (!goalChanged && repathCooldown > 0 && !noPath) {
            return;
        }

        int priority = getPathPriority(noPath, distToGoal, distToLeader, mode, slot);

        PathScheduler.AcquireResult acquire =
                PathScheduler.tryAcquireDetailed(worker.world, target, worker.getEntityId(), priority, Math.max(1, slot.groupSize));

        boolean moved = false;

        if (acquire == PathScheduler.AcquireResult.GRANTED) {
            if (shouldReuseCurrentPath(goal)) {
                moved = true;
            } else {
                moved = worker.getNavigator().tryMoveToXYZ(goal.x, goal.y, goal.z, moveSpeed);
            }

            if (moved) {
                recentPathSuccessTicks = RECENT_PATH_SUCCESS_BLOCK_TELEPORT_TICKS;
                failedAllowedPathTicks = 0;
                pathFailTicks = 0;
                steeringBlockedTicks = 0;
                markActivePathGoal(goal);

                if (TravellersConfig.WORKER_FORMATION.sharedRouteCorridorEnabled) {
                    FollowRouteCache.record(worker.world, target, worker.getNavigator().getPath());
                }
            } else {
                failedAllowedPathTicks += 4;
                pathFailTicks++;
                hasActivePathGoal = false;
                // The A* search itself found no route this tick (e.g. this follower's own lateral
                // offset doesn't line up with a valid crossing, or the search budget ran out on a
                // detour) - fall back to direct steering instead of leaving the entity with no
                // active goal at all. This is what actually jumps the gap for a follower whose
                // pathfind attempt failed: applyNonPathSteering's own isJumpableGapAhead check
                // takes over from here. Without this, a follower in this exact situation just sat
                // idle with stale motion while the rest of a large group pushed it toward the edge.
                applyNonPathSteering(goal, target, moveSpeed, airborneLeader, mode);
            }
        } else {
            if (PathScheduler.wasSchedulerDenial(acquire)) {
                schedulerDeniedTicks = Math.max(schedulerDeniedTicks, SCHEDULER_DENIAL_BLOCK_TELEPORT_TICKS);
            }
            hasActivePathGoal = false;
            applyNonPathSteering(goal, target, moveSpeed, airborneLeader, mode);
        }

        if (moved) {
            repathCooldown = getRepathInterval(slot, priority, true, mode);
        } else {
            if (acquire != PathScheduler.AcquireResult.GRANTED) {
                repathCooldown = getDeniedRetryCooldown(acquire, distToLeader, distToGoal, priority);
            } else {
                repathCooldown = getRepathInterval(slot, priority, false, mode);
            }
        }

        worker.setSprinting(false);
    }

    private GoalCandidate resolveGoal(EntityLivingBase target,
                                      FollowFormationCoordinator.SlotAssignment slot,
                                      FollowMode mode,
                                      FollowRuntimeConfig cfg,
                                      boolean airborneLeader) {
        double idealX = slot.idealX;
        double idealY = slot.idealY;
        double idealZ = slot.idealZ;

        // Re-anchor the slot to the leader's CURRENT position using the stored offset,
        // eliminating the stale-anchor lag between formation rebuilds.
        // Applies to all slots; skipped only when the anchor is a projected ground point.
        if (!slot.projectedGroundTarget) {
            idealX = target.posX + slot.leaderOffsetDx;
            idealZ = target.posZ + slot.leaderOffsetDz;
        }

        if (airborneLeader) {
            idealY = slot.idealY;
        } else if (mode == FollowMode.CAVE) {
            double pull = MathHelper.clamp(cfg.cave.heightAnchorPull, 0.15D, 0.90D);
            idealX = lerp(idealX, target.posX, pull);
            idealZ = lerp(idealZ, target.posZ, pull);
            idealY = target.getEntityBoundingBox().minY;
        } else {
            // Surface: use terrain height at slot XZ so the search is ground-anchored,
            // not floating at the leader's altitude (critical for mountain terrain).
            int sxi = MathHelper.floor(idealX);
            int szi = MathHelper.floor(idealZ);
            BlockPos slotCol = new BlockPos(sxi, 0, szi);
            if (worker.world.isBlockLoaded(slotCol)) {
                idealY = FollowRecoveryHelper.topSolidOrWaterSurface(worker.world, slotCol).getY();
            }
        }

        int horizontal = mode == FollowMode.CAVE ? cfg.cave.goalSearchHorizontal : cfg.surface.goalSearchHorizontal;
        int vertical = airborneLeader ? 2 : (mode == FollowMode.CAVE ? cfg.cave.goalSearchVertical : cfg.surface.goalSearchVertical);

        // While the leader is actually swimming, the goal search must be allowed to land in open
        // water too - otherwise a follower's slot always resolves back to the nearest dry ground,
        // which is what stranded a whole formation on the bank while the leader swam a wide river:
        // the goal simply never moved off the shore for the search to walk toward.
        boolean waterIsSafe = isLeaderSwimming(target);

        BlockPos best = findBestStandableGoal(target, slot, idealX, idealY, idealZ, horizontal, vertical, mode, airborneLeader, waterIsSafe);
        GoalCandidate candidate = best != null
                ? new GoalCandidate(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D)
                : new GoalCandidate(idealX, idealY, idealZ);

        candidate = stabilizeGoal(candidate, target, mode, slot, cfg);
        stickyGoalX = candidate.x;
        stickyGoalY = candidate.y;
        stickyGoalZ = candidate.z;
        hasStickyGoal = true;

        return candidate;
    }

    private GoalCandidate stabilizeGoal(GoalCandidate candidate,
                                        EntityLivingBase target,
                                        FollowMode mode,
                                        FollowFormationCoordinator.SlotAssignment slot,
                                        FollowRuntimeConfig cfg) {
        if (!hasStickyGoal) return candidate;

        double stickyDistSq = distanceSq(candidate.x, candidate.y, candidate.z, stickyGoalX, stickyGoalY, stickyGoalZ);
        boolean leaderStill = !slot.leaderActuallyMoving;
        boolean workerNearSticky = distanceSq(worker.posX, worker.posY, worker.posZ, stickyGoalX, stickyGoalY, stickyGoalZ) <= 3.8D * 3.8D;

        // When the coordinator's ideal slot moved away from where we last settled (settings changed),
        // skip all snap-back so workers actually navigate to the new formation positions.
        double idealStickyDriftSq = distanceSq(slot.idealX, slot.idealY, slot.idealZ, stickyGoalX, stickyGoalY, stickyGoalZ);
        boolean formationMoved = idealStickyDriftSq > 1.5D * 1.5D;

        if (slot.workerLeader && leaderStill) {
            if (!formationMoved && workerNearSticky && stickyDistSq <= 3.2D * 3.2D) {
                return new GoalCandidate(stickyGoalX, stickyGoalY, stickyGoalZ);
            }

            if (!formationMoved) {
                double selfDx = worker.posX - target.posX;
                double selfDz = worker.posZ - target.posZ;
                double selfDistSq = selfDx * selfDx + selfDz * selfDz;
                if (selfDistSq >= 3.0D * 3.0D && selfDistSq <= 8.0D * 8.0D) {
                    BlockPos selfFeet = new BlockPos(worker.posX, worker.getEntityBoundingBox().minY, worker.posZ);
                    if (isStandableFast(selfFeet)) {
                        return new GoalCandidate(worker.posX, selfFeet.getY(), worker.posZ);
                    }
                }
            }
        }

        if (leaderStill && workerNearSticky && stickyDistSq <= STICKY_GOAL_EPSILON_SQ && !formationMoved) {
            return new GoalCandidate(stickyGoalX, stickyGoalY, stickyGoalZ);
        }

        if (mode == FollowMode.CAVE && workerNearSticky && stickyDistSq <= 2.8D * 2.8D && !formationMoved) {
            return new GoalCandidate(stickyGoalX, stickyGoalY, stickyGoalZ);
        }

        if (mode == FollowMode.CAVE && orbitTicks >= cfg.cave.orbitTriggerTicks && workerNearSticky) {
            return new GoalCandidate(stickyGoalX, stickyGoalY, stickyGoalZ);
        }

        return candidate;
    }

    @Nullable
    private BlockPos findBestStandableGoal(EntityLivingBase leader,
                                           FollowFormationCoordinator.SlotAssignment slot,
                                           double idealX,
                                           double yHint,
                                           double idealZ,
                                           int horizontalRange,
                                           int verticalRange,
                                           FollowMode mode,
                                           boolean airborneLeader,
                                           boolean waterIsSafe) {
        World world = worker.world;
        int xi = MathHelper.floor(idealX);
        int zi = MathHelper.floor(idealZ);

        // For surface follow, use the actual terrain height at the slot XZ instead of the
        // leader's altitude. This prevents goals from being computed in mid-air when the
        // leader is on a mountain and the slot XZ is at the base.
        int y0;
        if (mode != FollowMode.CAVE && !airborneLeader) {
            BlockPos slotColumn = new BlockPos(xi, 0, zi);
            if (world.isBlockLoaded(slotColumn)) {
                y0 = MathHelper.clamp(FollowRecoveryHelper.topSolidOrWaterSurface(world, slotColumn).getY(), 2, world.getActualHeight() - 3);
            } else {
                y0 = MathHelper.clamp(MathHelper.floor(yHint), 2, world.getActualHeight() - 3);
            }
        } else {
            y0 = MathHelper.clamp(MathHelper.floor(yHint), 2, world.getActualHeight() - 3);
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        BlockPos stickyFeet = hasStickyGoal
                ? new BlockPos(stickyGoalX, stickyGoalY, stickyGoalZ)
                : null;

        if (stickyFeet != null && isStandableOrSwimmable(stickyFeet, waterIsSafe)) {
            if (mode != FollowMode.CAVE || !world.canSeeSky(stickyFeet)) {
                best = stickyFeet;
                bestScore = scoreStandableCandidate(leader, slot, stickyFeet, idealX, idealZ, mode, airborneLeader, false);
            }
        }

        BlockPos selfFeet = new BlockPos(worker.posX, worker.getEntityBoundingBox().minY, worker.posZ);
        if (isStandableOrSwimmable(selfFeet, waterIsSafe)) {
            if (mode != FollowMode.CAVE || !world.canSeeSky(selfFeet)) {
                double score = scoreStandableCandidate(leader, slot, selfFeet, idealX, idealZ, mode, airborneLeader, true);
                if (score < bestScore) {
                    best = selfFeet;
                    bestScore = score;
                }
            }
        }

        int[][] ring = {
                {0, 0},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };

        for (int[] off : ring) {
            BlockPos pos = new BlockPos(xi + off[0], y0, zi + off[1]);
            BlockPos grounded = findLocalGroundFast(pos, waterIsSafe);
            if (grounded == null) continue;
            if (mode == FollowMode.CAVE && world.canSeeSky(grounded)) continue;

            double score = scoreStandableCandidate(leader, slot, grounded, idealX, idealZ, mode, airborneLeader, false);
            if (score < bestScore) {
                bestScore = score;
                best = grounded;
            }
        }

        if (best != null && bestScore <= 2.2D) {
            return best;
        }

        for (int r = 1; r <= horizontalRange; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;

                    BlockPos pos = new BlockPos(xi + dx, y0, zi + dz);
                    BlockPos grounded = findLocalGroundFast(pos, waterIsSafe);
                    if (grounded == null) continue;
                    if (Math.abs(grounded.getY() - y0) > verticalRange) continue;
                    if (mode == FollowMode.CAVE && world.canSeeSky(grounded)) continue;

                    double score = scoreStandableCandidate(leader, slot, grounded, idealX, idealZ, mode, airborneLeader, false);
                    if (score < bestScore) {
                        bestScore = score;
                        best = grounded;
                    }
                }
            }

            if (best != null && bestScore <= (r <= 2 ? 4.0D : 2.8D)) {
                break;
            }
        }

        return best;
    }

    private boolean isBlockLoadedNoForce(BlockPos pos) {
        return worker.world.isBlockLoaded(pos, false);
    }

    private double scoreStandableCandidate(EntityLivingBase leader,
                                           FollowFormationCoordinator.SlotAssignment slot,
                                           BlockPos pos,
                                           double idealX,
                                           double idealZ,
                                           FollowMode mode,
                                           boolean airborneLeader,
                                           boolean isCurrentFeet) {
        double px = pos.getX() + 0.5D;
        double py = pos.getY();
        double pz = pos.getZ() + 0.5D;

        double score = 0.0D;
        score += distanceSq2D(px, pz, idealX, idealZ);

        double distLeaderSq = distanceSq2D(px, pz, leader.posX, leader.posZ);
        double dyLeader = Math.abs(py - leader.posY);

        double keepOut = slot.workerLeader ? 3.1D : 2.0D;
        if (distLeaderSq < keepOut * keepOut) {
            score += 18.0D + (keepOut * keepOut - distLeaderSq) * 6.0D;
        }

        if (!airborneLeader) {
            score += mode == FollowMode.CAVE ? (dyLeader * dyLeader * 1.8D) : (dyLeader * 0.6D);
        }

        double front = frontness(px, pz, leader.posX, leader.posZ, slot.leaderDirX, slot.leaderDirZ);
        if (mode != FollowMode.CAVE && !airborneLeader && front > 0.35D && distLeaderSq < 6.0D * 6.0D) {
            score += front * 7.0D * slot.frontPenaltyStrength;
        }

        score += localCrowdingPenalty(leader, px, py, pz);

        if (slot.workerLeader && !slot.leaderActuallyMoving) {
            double selfDistSq = distanceSq2D(px, pz, worker.posX, worker.posZ);
            score += selfDistSq * 0.55D;
        }

        if (isCurrentFeet) score -= 1.9D;
        if (mode == FollowMode.CAVE && orbitTicks >= FollowRuntimeConfig.get().cave.orbitTriggerTicks && isCurrentFeet) score -= 4.5D;

        return score;
    }

    private double localCrowdingPenalty(EntityLivingBase leader, double x, double y, double z) {
        List<EntityTravellerWorker> neighbors =
                FollowFormationCoordinator.getCrowdNeighbors(leader, worker, x, z);

        double penalty = 0.0D;
        boolean caveModeNow = localMode == FollowMode.CAVE;

        for (EntityTravellerWorker other : neighbors) {
            double dx = other.posX - x;
            double dz = other.posZ - z;
            double d2 = dx * dx + dz * dz;

            if (d2 > 4.0D * 4.0D) continue;

            if (d2 < 0.0001D) {
                penalty += caveModeNow ? 2.0D : 8.0D;
            } else {
                penalty += (caveModeNow ? 0.18D : 1.2D) / d2;
            }
        }

        return penalty;
    }

    private double sampleSlopePenalty(BlockPos pos) {
        BlockPos c = findLocalGroundFast(pos);
        if (c == null) return 2.0D;

        int cy = c.getY();
        int min = cy;
        int max = cy;

        BlockPos n = findLocalGroundFast(pos.north());
        BlockPos s = findLocalGroundFast(pos.south());
        BlockPos e = findLocalGroundFast(pos.east());
        BlockPos w = findLocalGroundFast(pos.west());

        if (n != null) { min = Math.min(min, n.getY()); max = Math.max(max, n.getY()); }
        if (s != null) { min = Math.min(min, s.getY()); max = Math.max(max, s.getY()); }
        if (e != null) { min = Math.min(min, e.getY()); max = Math.max(max, e.getY()); }
        if (w != null) { min = Math.min(min, w.getY()); max = Math.max(max, w.getY()); }

        return max - min;
    }

    @Nullable
    private BlockPos findLocalGroundFast(BlockPos feet) {
        return findLocalGroundFast(feet, false);
    }

    @Nullable
    private BlockPos findLocalGroundFast(BlockPos feet, boolean waterIsSafe) {
        if (isStandableOrSwimmable(feet, waterIsSafe)) return feet;
        BlockPos up1 = feet.up();
        if (isStandableOrSwimmable(up1, waterIsSafe)) return up1;
        BlockPos down1 = feet.down();
        if (isStandableOrSwimmable(down1, waterIsSafe)) return down1;
        BlockPos up2 = up1.up();
        if (isStandableOrSwimmable(up2, waterIsSafe)) return up2;
        BlockPos down2 = down1.down();
        if (isStandableOrSwimmable(down2, waterIsSafe)) return down2;
        return null;
    }

    @Nullable
    private BlockPos findLocalGround(BlockPos feet) {
        for (int dy = 2; dy >= -2; dy--) {
            BlockPos p = feet.add(0, dy, 0);
            if (isStandableFast(p)) return p;
        }
        return null;
    }

    private double computeSettleRadius(FollowFormationCoordinator.SlotAssignment slot, GoalCandidate goal, FollowMode mode) {
        double radius = slot.arriveRadius;

        BlockPos feet = new BlockPos(goal.x, goal.y, goal.z);
        double slope = sampleSlopePenalty(feet);
        if (slope >= 1.0D) radius += 0.55D;
        if (slot.tunnelStrength >= 0.45D) radius += 0.25D;
        if (mode == FollowMode.CAVE && orbitTicks >= FollowRuntimeConfig.get().cave.orbitTriggerTicks) radius += 0.8D;

        return radius;
    }

    private void tickStuckSignals(GoalCandidate goal) {
        boolean hasPath = !worker.getNavigator().noPath();
        if (hasPath) activePathTicks++;
        else if (activePathTicks > 0) activePathTicks--;

        double goalDistSq = distanceSq(worker.posX, worker.posY, worker.posZ, goal.x, goal.y, goal.z);

        if (!hasProgressAnchor) {
            lastProgressX = worker.posX;
            lastProgressY = worker.posY;
            lastProgressZ = worker.posZ;
            bestGoalDistSq = goalDistSq;
            hasProgressAnchor = true;
            stagnationTicks = 0;
            progressedTowardGoalLastTick = true;
            return;
        }

        boolean tryingToMove = !idleAtSlot && (hasPath || goalDistSq > 4.0D * 4.0D);

        // Stagnation is judged by PROGRESS toward the goal, not raw per-tick movement. A worker
        // jammed against several others in a crowded pit gets physically jostled by ordinary
        // Minecraft entity collision every tick - that alone used to look like "moving" and reset
        // this counter forever, so a pile of stuck workers never tripped the pit-escape rescue
        // below even though none of them were actually getting anywhere.
        boolean progressed = goalDistSq < bestGoalDistSq - PROGRESS_EPSILON_SQ;
        if (progressed) {
            bestGoalDistSq = goalDistSq;
        }
        progressedTowardGoalLastTick = progressed;

        if (tryingToMove && !progressed) {
            stagnationTicks++;
        } else if (stagnationTicks > 0) {
            stagnationTicks = Math.max(0, stagnationTicks - 2);
        }

        lastProgressX = worker.posX;
        lastProgressY = worker.posY;
        lastProgressZ = worker.posZ;

        if (!tryingToMove && failedAllowedPathTicks > 0) {
            failedAllowedPathTicks--;
        }
    }

    private void tickOrbitDetection(GoalCandidate goal, FollowMode mode, FollowRuntimeConfig cfg) {
        double dx = worker.posX - goal.x;
        double dz = worker.posZ - goal.z;
        double distSq = dx * dx + dz * dz;

        if (distSq < 1.8D || distSq > 16.0D || idleAtSlot || settledTicks >= SETTLED_FAST_PATH_TICKS) {
            orbitTicks = 0;
            hasOrbitAngle = false;
            return;
        }

        double angle = Math.atan2(dz, dx);

        if (!hasOrbitAngle) {
            lastOrbitAngle = angle;
            hasOrbitAngle = true;
            orbitTicks = 0;
            return;
        }

        double delta = wrapToPi(angle - lastOrbitAngle);
        lastOrbitAngle = angle;

        int trigger = mode == FollowMode.CAVE ? cfg.cave.orbitTriggerTicks : 50;

        if (Math.abs(delta) > 0.10D && stagnationTicks > 10) {
            orbitTicks++;
        } else if (orbitTicks > 0) {
            orbitTicks--;
        }

        if (orbitTicks > trigger + 20) {
            orbitTicks = trigger + 20;
        }
    }

    private boolean tryProgressiveRecovery(EntityLivingBase target, FollowMode mode, FollowRuntimeConfig cfg) {
        if (currentGoal == null) return false;
        if (recoveryCooldown > 0) return false;

        // Pit rescue: worker has no path at all and has made no progress toward its goal.
        // Bypasses the scheduler/recentPath guards that would block a stuck worker indefinitely.
        if (schedulerDeniedTicks <= 0 || recentPathSuccessTicks <= 0) {
            boolean pitStuck =
                    worker.getNavigator().noPath()
                            && stagnationTicks >= cfg.rescue.pitStagnationTicks
                            && pathFailTicks >= cfg.rescue.pitPathFailTicks
                            && failedAllowedPathTicks >= cfg.rescue.pitFailedAllowedPathTicks;

            if (pitStuck) {
                FollowRecoveryHelper.TeleportCandidate candidate =
                        FollowRecoveryHelper.findPitEscapeTeleport(
                                worker,
                                target,
                                currentGoal.x,
                                currentGoal.y,
                                currentGoal.z,
                                cfg.rescue.pitStepDistance
                        );

                if (candidate != null) {
                    FollowRecoveryHelper.teleportWorker(worker, candidate);
                    onTeleportRecovery(cfg);
                    return true;
                }
            }
        }

        if (schedulerDeniedTicks > 0) return false;
        if (recentPathSuccessTicks > 0) return false;

        boolean visible = canSeeTarget(target);

        if (mode == FollowMode.CAVE) {
            boolean outsideVsUnderground = FollowRecoveryHelper.isWorkerOutsideWhileLeaderUnderground(worker, target);

            // Surface workers with underground leader: shorter grace, relaxed thresholds.
            // They can never get activePathTicks (underground goal unreachable from surface),
            // so the normal condition would never trigger without this bypass.
            int graceTicks = outsideVsUnderground ? 15 : cfg.rescue.caveModeGraceTicks;
            if (modeTicks < graceTicks) {
                return false;
            }

            if (!isHardCaveRescueCase(target, currentGoal, cfg)) {
                return false;
            }

            int minStagnation = cfg.rescue.caveMinStagnationTicks;
            int minFailed = cfg.rescue.caveMinFailedPathTicks;

            if (outsideVsUnderground) {
                // Faster thresholds: stagnation builds quickly since the path always fails,
                // and activePathTicks can never reach 20 from the surface.
                minStagnation = 50;
                minFailed = 16;
            } else if (visible) {
                minStagnation += cfg.rescue.visibleExtraStagnationTicks;
                minFailed += cfg.rescue.visibleExtraFailedPathTicks;
            }

            boolean caveCase =
                    stagnationTicks >= minStagnation
                            && failedAllowedPathTicks >= minFailed
                            && (activePathTicks >= cfg.rescue.minActivePathTicks || outsideVsUnderground);

            if (caveCase) {
                boolean rescued = FollowRecoveryHelper.tryCaveFollowRescue(
                        worker,
                        target,
                        currentGoal.x,
                        currentGoal.y,
                        currentGoal.z,
                        cfg
                );

                if (rescued) {
                    onTeleportRecovery(cfg);
                    return true;
                }
            }

            return false;
        }

        boolean genericStuck =
                stagnationTicks >= cfg.rescue.surfaceMinStagnationTicks
                        && failedAllowedPathTicks >= cfg.rescue.surfaceMinFailedPathTicks
                        && activePathTicks >= cfg.rescue.minActivePathTicks
                        && pathFailTicks >= 12;

        if (genericStuck) {
            FollowRecoveryHelper.TeleportCandidate candidate =
                    FollowRecoveryHelper.findShortStepTeleport(
                            worker,
                            target,
                            cfg.rescue.surfaceMaxStep,
                            cfg.rescue.surfaceMinLeaderDistance,
                            cfg.rescue.surfaceMaxLeaderDistance,
                            false
                    );

            if (candidate != null) {
                FollowRecoveryHelper.teleportWorker(worker, candidate);
                onTeleportRecovery(cfg);
                return true;
            }

            // Short-step failed (e.g., mountain between worker and leader with no reachable
            // spot in that narrow corridor). Try a wide search centered on the leader so we
            // can land on the far side of any obstacle blocking the path.
            candidate = FollowRecoveryHelper.findWideTeleportNearLeader(
                    worker, target, 12.0D, cfg.rescue.surfaceMinLeaderDistance);

            if (candidate != null) {
                FollowRecoveryHelper.teleportWorker(worker, candidate);
                onTeleportRecovery(cfg);
                return true;
            }
        }

        return false;
    }

    private boolean isHardCaveRescueCase(EntityLivingBase target,
                                         GoalCandidate goal,
                                         FollowRuntimeConfig cfg) {
        if (FollowRecoveryHelper.shouldSuppressCaveRescue(
                worker,
                target,
                goal.x,
                goal.y,
                goal.z,
                cfg)) {
            return false;
        }

        boolean outsideVsUnderground = FollowRecoveryHelper.isWorkerOutsideWhileLeaderUnderground(worker, target);

        boolean aboveLeader =
                FollowRecoveryHelper.isFollowerRoughlyAboveLeader(
                        worker,
                        target,
                        cfg.rescue.caveMaxHorizontal,
                        cfg.rescue.caveMinVerticalAbove
                );

        boolean extremeFailure =
                failedAllowedPathTicks >= cfg.rescue.caveRequireSamePocketFailTicks
                        && stagnationTicks >= cfg.rescue.caveRequireSamePocketStagnationTicks
                        && activePathTicks >= cfg.rescue.minActivePathTicks * 2;

        return outsideVsUnderground || aboveLeader || extremeFailure;
    }

    private boolean isNaturalCaveProgressStillHappening(EntityLivingBase target,
                                                        GoalCandidate goal,
                                                        FollowRuntimeConfig cfg) {
        double distToLeader = worker.getDistance(target);
        double dyToLeader = Math.abs(worker.posY - target.posY);

        if (distToLeader <= cfg.rescue.caveNoTeleportLeaderDistance + 4.0D
                && dyToLeader <= cfg.rescue.caveNoTeleportVerticalGap + 2.0D) {
            return true;
        }

        double dx = worker.posX - goal.x;
        double dy = worker.posY - goal.y;
        double dz = worker.posZ - goal.z;
        double distToGoalSq = dx * dx + dy * dy + dz * dz;

        if (distToGoalSq <= (cfg.rescue.caveNoTeleportGoalDistance + 2.0D) * (cfg.rescue.caveNoTeleportGoalDistance + 2.0D)) {
            return true;
        }

        return activePathTicks > 0 && stagnationTicks < 40 && failedAllowedPathTicks < 16;
    }

    private boolean sameOwner(EntityTravellerWorker other) {
        return other != null && Objects.equals(worker.getFollowOwnerId(), other.getFollowOwnerId());
    }

    /**
     * The worker's effective follow target switched entity (e.g. WorkerGroupService assigned it to a
     * new leader, or its old leader's follow order was cleared) while this AI task kept running
     * uninterrupted. Treats it like a fresh start against the new target: every stagnation/recovery
     * counter earned against the OLD target is wiped so the next few ticks judge progress purely
     * against the new one, instead of a lucky/unlucky streak from before the switch immediately
     * triggering (or suppressing) a teleport rescue that has nothing to do with the new target.
     */
    private void onFollowTargetChanged() {
        pathFailTicks = 0;
        failedAllowedPathTicks = 0;
        stagnationTicks = 0;
        activePathTicks = 0;
        recentPathSuccessTicks = 0;
        schedulerDeniedTicks = 0;
        hasProgressAnchor = false;
        progressedTowardGoalLastTick = true;
        orbitTicks = 0;
        hasOrbitAngle = false;

        repathCooldown = 0;
        hasGoal = false;
        hasStickyGoal = false;
        hasActivePathGoal = false;
        hasGoalEvalAnchor = false;
        currentGoal = null;
        settledTicks = 0;
        cachedSettleGoalX = Double.NaN;

        idleAtSlot = false;
        wakeupCooldown = 0;

        steeringBlockedTicks = 0;
        deflectionRecheckCooldown = 0;
        hasCachedDeflectDir = false;

        // Small grace window before the teleport-rescue heuristics can fire again, so ordinary
        // pathfinding gets a fair first attempt at reaching the new target before anything escalates.
        recoveryCooldown = Math.max(recoveryCooldown, FollowRuntimeConfig.get().rescue.cooldownTicks);

        worker.getNavigator().clearPath();
    }

    private void onTeleportRecovery(FollowRuntimeConfig cfg) {
        pathFailTicks = 0;
        failedAllowedPathTicks = 0;
        stagnationTicks = 0;
        activePathTicks = 0;
        repathCooldown = 12;
        recoveryCooldown = cfg.rescue.cooldownTicks;
        hasGoal = false;
        hasStickyGoal = false;
        recentPathSuccessTicks = 0;
        schedulerDeniedTicks = 0;
        hasProgressAnchor = false;
        progressedTowardGoalLastTick = true;
        orbitTicks = 0;
        hasOrbitAngle = false;
        hasActivePathGoal = false;

        idleAtSlot = false;
        wakeupCooldown = 0;

        steeringBlockedTicks = 0;
        deflectionRecheckCooldown = 0;
        hasCachedDeflectDir = false;
    }

    private int getPathPriority(boolean noPath,
                                double distToGoal,
                                double distToLeader,
                                FollowMode mode,
                                FollowFormationCoordinator.SlotAssignment slot) {
        double dy = followTarget == null ? 0.0D : Math.abs(worker.posY - followTarget.posY);

        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (wf.steeringBlockedFastEscalationEnabled
                && steeringBlockedTicks >= wf.steeringBlockedEscalationGraceTicks) {
            return 2;
        }

        if (pathFailTicks >= 6) return 2;
        if (mode == FollowMode.CAVE && dy >= 6.0D) return 2;
        if (mode == FollowMode.SURFACE && dy >= 10.0D) return 1;
        if (noPath && distToGoal > 4.0D && distToLeader > 10.0D) return 2;
        if (distToLeader >= 34.0D) return 2;
        if (distToLeader >= 22.0D) return 1;
        if (distToLeader >= 14.0D && distToGoal >= 4.5D) return 1;
        if (noPath && distToLeader >= 10.0D) return 1;
        if (slot.tunnelStrength >= 0.45D && distToGoal > 3.0D) return 1;

        if (isLeaderMovingFast(wf, slot)) return Math.max(0, wf.fastLeaderMinPriority);
        return 0;
    }

    // Escorts already re-path every 2-3 ticks regardless of speed (getRepathInterval), so this
    // only matters for formation followers, whose slots are farther out and see bigger per-tick
    // goal shifts while the leader moves fast (sprinting player, or a worker leader traveling) —
    // covers both, unlike checking EntityPlayer.isSprinting() alone.
    private boolean isLeaderMovingFast(TravellersConfig.WorkerFormation wf,
                                       FollowFormationCoordinator.SlotAssignment slot) {
        return wf.fastLeaderPriorityBoostEnabled
                && !slot.isEscort
                && slot.leaderActuallyMoving
                && slot.leaderSpeed >= wf.fastLeaderSpeedThreshold;
    }

    private int getDeniedRetryCooldown(PathScheduler.AcquireResult acquire,
                                       double distToLeader,
                                       double distToGoal,
                                       int priority) {
        int base;

        if (acquire == PathScheduler.AcquireResult.DENIED_SPREAD) base = 2;
        else if (acquire == PathScheduler.AcquireResult.DENIED_OWNER_TOKENS) base = 3;
        else if (acquire == PathScheduler.AcquireResult.DENIED_GLOBAL_LIMIT) base = 4;
        else base = 4;

        if (priority >= 2 || distToLeader >= 28.0D) {
            base = Math.max(1, base - 2);
        } else if (priority == 1 || distToLeader >= 18.0D) {
            base = Math.max(2, base - 1);
        }

        if (distToGoal <= 3.0D && distToLeader <= 10.0D) {
            base += 1;
        }

        return base + Math.abs((worker.getEntityId() * 11) % 2);
    }

    private int getRepathInterval(FollowFormationCoordinator.SlotAssignment slot,
                                  int priority,
                                  boolean pathSucceeded,
                                  FollowMode mode) {
        if (slot.isEscort) {
            // Escorts re-path aggressively to stay glued to the leader.
            int base = pathSucceeded ? 3 : 2;
            return base + Math.abs((worker.getEntityId() * 13) % 2);
        }

        int base = slot.groupSize >= 100 ? 34 : (slot.groupSize >= 60 ? 28 : (slot.groupSize >= 30 ? 22 : 16));

        if (slot.compactness < 0.8D) base += 2;
        if (mode == FollowMode.CAVE) base = Math.max(5, base - 1);
        if (slot.tunnelStrength >= 0.45D) base = Math.max(4, base - 1);

        if (priority >= 2) base = Math.max(4, base - 2);
        else if (priority == 1) base = Math.max(5, base - 1);

        if (!pathSucceeded) base = Math.max(4, base - 1);

        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (isLeaderMovingFast(wf, slot)) {
            base = Math.min(base, wf.fastLeaderRepathIntervalCapTicks);
        }

        return base + Math.abs((worker.getEntityId() * 13) % 3);
    }

    private double computeMoveSpeed(EntityLivingBase target,
                                    FollowFormationCoordinator.SlotAssignment slot,
                                    double distToLeader,
                                    double distToGoal,
                                    FollowMode mode,
                                    boolean airborneLeader) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        double catchUp = wf.catchUpSpeedBoost;

        double targetMove = slot.leaderSpeed;
        double speed = baseSpeed;

        if (target.isSneaking()) speed *= 0.88D;
        if (target.isSprinting()) speed *= wf.sprintSpeedBoost;

        if (slot.isEscort && slot.leaderActuallyMoving) speed *= 1.0D + 0.10D * catchUp;

        if (targetMove > 0.08D) speed *= 1.0D + 0.08D * catchUp;
        if (targetMove > 0.14D) speed *= 1.0D + 0.10D * catchUp;

        if (distToGoal > 3.5D) speed *= 1.0D + 0.08D * catchUp;
        if (distToGoal > 6.0D) speed *= 1.0D + 0.12D * catchUp;
        if (distToGoal > 9.0D) speed *= 1.0D + 0.15D * catchUp;

        if (distToLeader > 14.0D) speed *= 1.0D + 0.10D * catchUp;
        if (distToLeader > 24.0D) speed *= 1.0D + 0.15D * catchUp;

        if (mode == FollowMode.CAVE) speed *= 1.03D;
        if (airborneLeader) speed *= 1.04D;
        if (followTarget instanceof EntityTravellerWorker) speed *= 1.02D;

        speed = MathHelper.clamp(speed, wf.minSpeedMultiplier, wf.maxSpeedMultiplier);
        smoothedMoveSpeed = smoothedMoveSpeed * 0.76D + speed * 0.24D;
        return MathHelper.clamp(smoothedMoveSpeed, wf.minSpeedMultiplier, wf.maxSpeedMultiplier);
    }

    private double getDesiredFollowDistance(EntityLivingBase target, FollowMode mode, FollowRuntimeConfig cfg) {
        if (mode == FollowMode.CAVE) {
            return cfg.cave.baseLeaderDistance;
        }
        if (target instanceof EntityTravellerWorker) {
            return Math.max(cfg.surface.baseLeaderDistanceWorker, stopDist + 1.8D);
        }
        return Math.max(cfg.surface.baseLeaderDistancePlayer, stopDist + 1.3D);
    }

    private void applyNonPathSteering(GoalCandidate goal,
                                      EntityLivingBase target,
                                      double moveSpeed,
                                      boolean airborneLeader,
                                      FollowMode mode) {
        double verticalGap = goal.y - worker.posY;
        double horizToGoalSq = distanceSq2D(worker.posX, worker.posZ, goal.x, goal.z);
        double horizToGoal = Math.sqrt(horizToGoalSq);

        boolean leaderStill = Math.abs(target.posX - target.prevPosX) < 0.05D
                && Math.abs(target.posZ - target.prevPosZ) < 0.05D;

        // The leader itself is swimming (not just standing at a shore) — water ahead is the
        // expected medium to cross, not an obstacle to steer away from.
        boolean waterIsSafe = isLeaderSwimming(target);

        if (isCloseEnoughToStopSteering(goal, target)) {
            worker.getNavigator().clearPath();
            steeringBlockedTicks = 0;
            return;
        }

        double toGoalDx = goal.x - worker.posX;
        double toGoalDz = goal.z - worker.posZ;
        double toGoalDistSq = toGoalDx * toGoalDx + toGoalDz * toGoalDz;
        double toGoalDist = Math.sqrt(toGoalDistSq);

        if (toGoalDist < 1.0E-4D) {
            worker.getNavigator().clearPath();
            steeringBlockedTicks = 0;
            return;
        }

        // Straight line to the goal is blocked (cliff, ravine, water bank, etc). Rather than
        // stalling at the edge, try a local detour first, then a nearby groupmate's found path,
        // before giving up and letting the stuck-recovery/escalation timers take over.
        //
        // A hazard that is actually a jumpable gap must NOT be deflected around or stalled at:
        // keep steering straight toward it instead, so the follower closes the remaining
        // distance to the edge and the reactive gap-jump below (and every tick via
        // TravellerNavigateGround) fires once it's actually close enough. Without this check,
        // isHazardAhead's early-warning lookahead flags the gap well before the entity is close
        // enough for GapJumpEvaluator's own edge-adjacent check to confirm it — so the deflection
        // logic used to run first every time, routing followers around/away from a gap they could
        // have simply jumped, and in a crowded group that's what kept shoving followers off the
        // edge into it instead. Only a genuinely unjumpable hazard (too wide, lava, no safe
        // landing) still falls through to deflection/corridor-follow/stop.
        if (isHazardAhead(worker.posX, worker.posY, worker.posZ, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist, waterIsSafe)
                && !isJumpableGapAhead(worker.posX, worker.posY, worker.posZ, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist)) {
            boolean handled = tryObstacleDeflection(goal, toGoalDx / toGoalDist, toGoalDz / toGoalDist, toGoalDist, moveSpeed, waterIsSafe)
                    || tryFollowSharedCorridor(target, moveSpeed);

            if (handled) {
                // A hazard-free direction existing is not proof it's actually useful - along a
                // riverbank/cliff edge, deflection can keep finding a direction that merely runs
                // parallel to the obstacle forever, never making real headway. Only treat that as
                // "handled" (reset the escalation counter) when it's also produced real progress
                // toward the goal; otherwise keep counting so a genuinely stuck follower still
                // escalates to real pathfinding (the only thing that can actually route across
                // water/around the obstacle) instead of oscillating at the edge indefinitely.
                steeringBlockedTicks = progressedTowardGoalLastTick ? 0 : steeringBlockedTicks + 1;
                return;
            }

            steeringBlockedTicks++;
            worker.getNavigator().clearPath();
            return;
        }
        // Every probe above (isHazardAhead, the deflection, the corridor search) is sized from the
        // worker's own sub-1-block hitbox, so for a mounted worker they are blind to the only thing
        // that actually stops it: its horse's much wider, taller body. The way ahead reads as clear,
        // this counter resets every tick, the escalation to real pathfinding - which IS mount-aware -
        // never happens, and the horse is steered into the same tree indefinitely. Measured: 1381
        // consecutive ticks at zero displacement with the rider still asking to move forward. Real
        // physics is the only reliable witness here, so it feeds the counter directly.
        if (TravellerMountSupport.isMountWedged(worker)) {
            steeringBlockedTicks++;
            return;
        }
        steeringBlockedTicks = 0;

        if (idleAtSlot) {
            worker.getNavigator().clearPath();
            return;
        }

        if (horizToGoal <= 0.85D && !target.isSprinting() && !slotLikeLeaderMovement(target)) {
            worker.getNavigator().clearPath();
            return;
        }

        BlockPos steerFeet = new BlockPos(goal.x, goal.y, goal.z);
        if (isHazardousStandPos(steerFeet, waterIsSafe)) {
            worker.getNavigator().clearPath();
            return;
        }

        double clampedSpeed = Math.max(0.20D, moveSpeed * 0.90D);

        if (airborneLeader) {
            if (horizToGoal > 1.8D) {
                if (!tryGapJump(goal.x, goal.z, clampedSpeed)) {
                    steerTo(goal.x, worker.posY, goal.z, clampedSpeed);
                }
            } else {
                worker.getNavigator().clearPath();
            }
            return;
        }

        if (mode == FollowMode.CAVE && Math.abs(worker.posY - target.posY) <= 3.0D) {
            double s = Math.max(0.20D, moveSpeed * 0.92D);
            if (!tryGapJump(goal.x, goal.z, s)) {
                steerTo(goal.x, goal.y, goal.z, s);
            }
            return;
        }

        if (verticalGap > 1.25D) {
            if (horizToGoal > 2.0D) {
                double s = Math.max(0.20D, moveSpeed * 0.84D);
                if (!tryGapJump(goal.x, goal.z, s)) {
                    steerTo(goal.x, worker.posY, goal.z, s);
                }
            } else if (!leaderStill) {
                double s = Math.max(0.20D, moveSpeed * 0.78D);
                if (!tryGapJump(goal.x, goal.z, s)) {
                    steerTo(goal.x, worker.posY, goal.z, s);
                }
            } else {
                worker.getNavigator().clearPath();
            }
            return;
        }

        if (!tryGapJump(goal.x, goal.z, clampedSpeed)) {
            steerTo(goal.x, goal.y, goal.z, clampedSpeed);
        }
    }

    /** The single way this task hands a direct steering target to the move helper. While mounted,
     * {@code getMoveHelper()} is the horse's own, so this steers the horse directly. */
    private void steerTo(double x, double y, double z, double speed) {
        worker.getMoveHelper().setMoveTo(x, y, z, speed);
    }

    private boolean tryGapJump(double goalX, double goalZ, double speed) {
        PathNavigate nav = worker.getNavigator();
        if (!(nav instanceof TravellerNavigateGround)) return false;

        TravellerNavigateGround groundNav = (TravellerNavigateGround) nav;
        if (groundNav.offerGapJump(goalX, goalZ, speed)) return true;

        // A jump is fully validated and ready to fire, only withheld by its own cooldown - hold
        // in place (report "handled" so every caller's own setMoveTo fallback is skipped) instead
        // of walking toward a goal across the gap, which on a small landing (a narrow pillar, a
        // tight obstacle course) would step the worker clean off the edge and into the gap it
        // hasn't been allowed to jump yet.
        if (groundNav.isJumpPendingCooldown(goalX, goalZ)) {
            worker.getMoveHelper().setMoveTo(worker.posX, worker.posY, worker.posZ, 0.0D);
            return true;
        }
        return false;
    }

    private boolean slotLikeLeaderMovement(EntityLivingBase target) {
        return Math.abs(target.posX - target.prevPosX) > 0.05D
                || Math.abs(target.posZ - target.prevPosZ) > 0.05D;
    }

    private boolean canUseFollow() {
        if (worker.getAttackTarget() != null || worker.isInCombat()) return false;
        if (hasValidCombatTarget()) return false;
        if (worker.isCatchupTravel()) return false;
        if (!worker.isHiredFollower()) return false;
        if (!worker.isFollowOrderActive()) return false;
        if (worker.isJobActive() && !worker.isReturnUsingFollow()) return false;
        return true;
    }

    private boolean isValidTarget(@Nullable EntityLivingBase target) {
        if (target == null || target.isDead) return false;
        return !(target instanceof EntityPlayer) || !((EntityPlayer) target).isSpectator();
    }

    private boolean hasValidCombatTarget() {
        EntityLivingBase atk = worker.getAttackTarget();
        if (atk != null && !atk.isDead) return true;

        EntityLivingBase rev = worker.getRevengeTarget();
        return rev != null && !rev.isDead;
    }

    private boolean isLeaderClearlyAirborne(EntityLivingBase leader, FollowRuntimeConfig cfg) {
        if (leader == null) return false;
        if (leader.isRiding() && leader.getRidingEntity() instanceof EntityBoat) return false;
        if (leader.isInWater()) return false;
        if (FollowRecoveryHelper.isLeaderMeaningfullyUnderground(worker.world, leader, cfg)) return false;

        BlockPos feet = new BlockPos(leader.posX, leader.getEntityBoundingBox().minY, leader.posZ);
        if (!worker.world.isBlockLoaded(feet)) return false;

        BlockPos top = worker.world.getTopSolidOrLiquidBlock(new BlockPos(feet.getX(), 0, feet.getZ()));
        int gap = feet.getY() - top.getY();

        return !leader.onGround && gap >= 5 && worker.world.canSeeSky(top);
    }

    private double horizontalDistanceTo(EntityLivingBase target) {
        double dx = worker.posX - target.posX;
        double dz = worker.posZ - target.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void applyEmergencySeparation(EntityLivingBase leader) {
        boolean workerLeader = leader instanceof EntityTravellerWorker;

        double protectRadius = workerLeader ? 3.15D : Math.sqrt(TOO_CLOSE_TO_LEADER_SQ);
        double d2 = worker.getDistanceSq(leader);
        if (d2 > protectRadius * protectRadius) return;

        double dx = worker.posX - leader.posX;
        double dz = worker.posZ - leader.posZ;
        double lenSq = dx * dx + dz * dz;

        if (lenSq < 1.0E-6D) {
            int side = ((worker.getUniqueID().hashCode() >>> 2) & 1) == 0 ? -1 : 1;
            dx = side;
            dz = 0.0D;
            lenSq = 1.0D;
        }

        double len = Math.sqrt(lenSq);
        dx /= len;
        dz /= len;

        double strength = workerLeader ? 0.11D : 0.05D;
        worker.motionX += dx * strength;
        worker.motionZ += dz * strength;
    }

    private double frontness(double x, double z, double leaderX, double leaderZ, double dirX, double dirZ) {
        double dx = x - leaderX;
        double dz = z - leaderZ;
        double lenSq = dx * dx + dz * dz;
        if (lenSq < 1.0E-6D) return 0.0D;

        double len = Math.sqrt(lenSq);
        dx /= len;
        dz /= len;
        return dx * dirX + dz * dirZ;
    }

    private boolean canSeeTarget(EntityLivingBase target) {
        return worker.canEntityBeSeen(target) || target.canEntityBeSeen(worker);
    }

    private boolean isStandableFast(BlockPos feet) {
        return TerrainStandableCache.isStandable(worker.world, feet, this::computeStandable);
    }

    private boolean computeStandable(BlockPos feet) {
        World world = worker.world;
        BlockPos down = feet.down();
        BlockPos up = feet.up();

        if (!isBlockLoadedNoForce(down) || !isBlockLoadedNoForce(feet) || !isBlockLoadedNoForce(up)) {
            return false;
        }

        IBlockState below = world.getBlockState(down);
        if (below.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = below.getBlockFaceShape(world, down, EnumFacing.UP);
        if (shape != BlockFaceShape.SOLID) return false;

        IBlockState atFeet = world.getBlockState(feet);
        IBlockState atHead = world.getBlockState(up);

        // Water is Material.isReplaceable() == true (same as air) — without this explicit check
        // a solid floor sitting under a water column reads as "standable", sending goals/steering
        // toward positions the worker would suffocate at. Same fix as FollowRecoveryHelper.isStandable.
        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) return false;

        if (!(atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(world, feet) == null)) {
            return false;
        }

        return atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(world, up) == null;
    }

    /**
     * Same "can a worker occupy this column" question as {@link #isStandableFast}, but also
     * accepts an open-water column when {@code waterIsSafe} is true (the leader is itself
     * swimming). Without this, the goal search behind {@link #findBestStandableGoal} can never
     * place a slot in open water - every candidate in a lake/river fails the dry-land-only check,
     * so a follower's goal always resolves back to the nearest shore even while its leader swims
     * on, stranding the whole formation at the water's edge instead of following across.
     */
    private boolean isStandableOrSwimmable(BlockPos feet, boolean waterIsSafe) {
        if (isStandableFast(feet)) return true;
        return waterIsSafe && isSwimmableFast(feet);
    }

    /**
     * Not routed through {@link TerrainStandableCache} - that cache is keyed on block position
     * alone and shared world-wide for the tick, but "is this water column OK" depends on whether
     * THIS follower's leader is swimming, which can differ between two formations in the same
     * tick. Only reached while a leader is actually swimming, so it isn't a hot path.
     */
    private boolean isSwimmableFast(BlockPos feet) {
        World world = worker.world;
        BlockPos up = feet.up();
        if (!isBlockLoadedNoForce(feet) || !isBlockLoadedNoForce(up)) return false;

        IBlockState atFeet = world.getBlockState(feet);
        if (!isVanillaWater(atFeet)) return false;

        IBlockState atHead = world.getBlockState(up);
        return atHead.getMaterial().isReplaceable() || atHead.getMaterial().isLiquid()
                || atHead.getCollisionBoundingBox(world, up) == null;
    }

    /**
     * True while the given follow target is actually swimming (in water, not riding a boat) -
     * the shared condition for treating open water as a legitimate follow medium instead of an
     * obstacle, both for goal placement and for steering (see the local {@code waterIsSafe} in
     * {@link #applyNonPathSteering}).
     */
    private boolean isLeaderSwimming(EntityLivingBase target) {
        return target.isInWater() && !(target.isRiding() && target.getRidingEntity() instanceof EntityBoat);
    }

    private double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private double distanceSq2D(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return dx * dx + dz * dz;
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private double wrapToPi(double angle) {
        while (angle > Math.PI) angle -= Math.PI * 2.0D;
        while (angle < -Math.PI) angle += Math.PI * 2.0D;
        return angle;
    }

    private static boolean isVanillaWater(IBlockState state) {
        Block block = state.getBlock();
        return block == Blocks.WATER || block == Blocks.FLOWING_WATER;
    }

    private static final class GoalCandidate {
        private final double x;
        private final double y;
        private final double z;

        private GoalCandidate(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private void onFollowModeChanged(FollowMode oldMode, FollowMode newMode, FollowRuntimeConfig cfg) {
        if (oldMode == newMode) return;

        hasGoal = false;
        hasStickyGoal = false;
        repathCooldown = 0;
        orbitTicks = 0;
        hasOrbitAngle = false;
        hasActivePathGoal = false;

        idleAtSlot = false;
        wakeupCooldown = 0;
        settledTicks = 0;

        if (newMode == FollowMode.CAVE) {
            pathFailTicks = 0;
            failedAllowedPathTicks = 0;
            stagnationTicks = 0;
            activePathTicks = 0;
            // Workers on the surface while the leader is underground can't get active paths —
            // skip the recent-path delay so cave rescue can fire quickly for them.
            boolean outsideUnderground = followTarget != null
                    && FollowRecoveryHelper.isWorkerOutsideWhileLeaderUnderground(worker, followTarget);
            if (!outsideUnderground) {
                recentPathSuccessTicks = Math.max(recentPathSuccessTicks, 20);
            }
            schedulerDeniedTicks = 0;
            hasProgressAnchor = false;
        progressedTowardGoalLastTick = true;
        }
    }
    private boolean shouldReevaluateGoal(EntityLivingBase target,
                                         FollowMode mode,
                                         FollowFormationCoordinator.SlotAssignment slot) {
        if (!hasGoal || currentGoal == null || !hasGoalEvalAnchor) {
            return true;
        }

        // XZ-only for movement thresholds: vertical movement while climbing hills
        // should not trigger mass re-evals — the goal XZ direction is still valid.
        // Large Y jumps (cliffs, jumps) are caught by a separate vertical threshold.
        double leaderMovedSq = distanceSq2D(target.posX, target.posZ, lastGoalEvalLeaderX, lastGoalEvalLeaderZ);
        double leaderMovedY   = Math.abs(target.posY - lastGoalEvalLeaderY);

        double workerMovedSq  = distanceSq2D(worker.posX, worker.posZ, lastGoalEvalWorkerX, lastGoalEvalWorkerZ);

        double idealShiftSq = distanceSq(
                currentGoal.x, currentGoal.y, currentGoal.z,
                slot.idealX, slot.idealY, slot.idealZ
        );

        boolean workerLeader = slot.workerLeader;

        // playerCohesion is THE lever a player actually feels while walking: it tightens how far the
        // player has to move before a settled escort bothers to re-evaluate/re-path toward its slot
        // at all. This runs unconditionally every tick for a player-led follower (unlike the
        // idleAtSlot machinery above, which only ever engages for a worker leader) - so it's the
        // correct place for "walk WITH me instead of trailing", not a subtle side-effect elsewhere.
        double cohesionMul = workerLeader ? 1.0D : (1.0D - 0.55D * worker.getCachedPlayerCohesion());
        double leaderMoveThresholdSq = workerLeader ? (4.0D * 4.0D) : (2.5D * cohesionMul) * (2.5D * cohesionMul);
        double leaderMoveThresholdY  = workerLeader ? 6.0D : 4.0D * cohesionMul;
        double workerMoveThresholdSq = workerLeader ? (2.5D * 2.5D) : (2.0D * cohesionMul) * (2.0D * cohesionMul);
        double idealShiftThresholdSq = workerLeader ? (5.5D * 5.5D) : (3.5D * cohesionMul) * (3.5D * cohesionMul);

        boolean noPath = worker.getNavigator().noPath();

        boolean forced =
                leaderMovedSq >= leaderMoveThresholdSq
                        || leaderMovedY  >= leaderMoveThresholdY
                        || workerMovedSq >= workerMoveThresholdSq
                        || idealShiftSq >= idealShiftThresholdSq
                        || (noPath && !idleAtSlot && settledTicks <= 0);

        if (forced) {
            goalRefreshCooldown = 0;
            return true;
        }

        if (idleAtSlot) {
            return false;
        }

        if (goalRefreshCooldown > 0) {
            goalRefreshCooldown--;
            return false;
        }

        if (workerLeader && slot.leaderActuallyMoving) {
            return false;
        }

        return true;
    }

    private void markGoalEvaluated(EntityLivingBase target, FollowMode mode,
                                    FollowFormationCoordinator.SlotAssignment slot) {
        lastGoalEvalLeaderX = target.posX;
        lastGoalEvalLeaderY = target.posY;
        lastGoalEvalLeaderZ = target.posZ;
        lastGoalEvalWorkerX = worker.posX;
        lastGoalEvalWorkerY = worker.posY;
        lastGoalEvalWorkerZ = worker.posZ;
        hasGoalEvalAnchor = true;

        if (slot.isEscort) {
            goalRefreshCooldown = 1 + Math.abs(worker.getEntityId() % 2);
            return;
        }

        int groupSize = slot.groupSize;
        boolean workerLeader = target instanceof EntityTravellerWorker;

        int base;
        if (workerLeader) {
            if (groupSize >= 200)          base = 48;
            else if (groupSize >= 100)     base = 40;
            else if (groupSize >= 60)      base = 32;
            else if (groupSize >= 20)      base = 24;
            else                           base = 18;
        } else {
            // Player leader: reactive but not too aggressive for large groups
            if (groupSize >= 200)          base = 16;
            else if (groupSize >= 100)     base = 14;
            else if (groupSize >= 60)      base = 10;
            else if (mode == FollowMode.CAVE) base = 6;
            else                           base = 5;
        }

        int stagger;
        if (workerLeader) {
            stagger = Math.abs(worker.getEntityId() * 13) % Math.max(2, base / 2);
        } else {
            stagger = Math.abs(worker.getEntityId() * 7) % 2;
        }

        goalRefreshCooldown = base + stagger;
    }

    private boolean shouldReuseCurrentPath(GoalCandidate goal) {
        PathNavigate nav = worker.getNavigator();
        if (nav == null || nav.noPath()) return false;
        if (!hasActivePathGoal) return false;

        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;

        double goalShiftSq = distanceSq(
                goal.x, goal.y, goal.z,
                activePathGoalX, activePathGoalY, activePathGoalZ
        );

        if (goalShiftSq > wf.pathReuseGoalShiftMaxBlocks * wf.pathReuseGoalShiftMaxBlocks) {
            return false;
        }

        double workerToGoalSq = distanceSq(
                worker.posX, worker.posY, worker.posZ,
                goal.x, goal.y, goal.z
        );

        return workerToGoalSq <= wf.pathReuseWorkerToGoalMaxBlocks * wf.pathReuseWorkerToGoalMaxBlocks;
    }

    private void markActivePathGoal(GoalCandidate goal) {
        activePathGoalX = goal.x;
        activePathGoalY = goal.y;
        activePathGoalZ = goal.z;
        hasActivePathGoal = true;
    }

    private boolean isHazardousStandPos(BlockPos feet, boolean waterIsSafe) {
        World world = worker.world;
        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.down()) || !world.isBlockLoaded(feet.up())) {
            return true;
        }

        IBlockState at = world.getBlockState(feet);
        IBlockState below = world.getBlockState(feet.down());
        IBlockState up = world.getBlockState(feet.up());

        Block atBlock = at.getBlock();
        Block belowBlock = below.getBlock();

        if (at.getMaterial() == Material.LAVA || below.getMaterial() == Material.LAVA) return true;
        if (!waterIsSafe && (at.getMaterial() == Material.WATER || below.getMaterial() == Material.WATER)) return true;
        if (atBlock == Blocks.FIRE || belowBlock == Blocks.FIRE) return true;

        if (!(at.getMaterial().isReplaceable() || at.getCollisionBoundingBox(world, feet) == null)) return true;
        if (!(up.getMaterial().isReplaceable() || up.getCollisionBoundingBox(world, feet.up()) == null)) return true;

        if (!waterIsSafe && below.getMaterial().isLiquid()) return true;
        if (below.getCollisionBoundingBox(world, feet.down()) == null && !below.getMaterial().isLiquid()) return true;

        if (isDropOrUnsafeEdge(feet, EDGE_DANGER_DEPTH, waterIsSafe)) return true;

        return false;
    }

    private boolean isHazardAhead(double startX, double startY, double startZ, double dirX, double dirZ,
                                  double maxDist, boolean waterIsSafe) {
        World world = worker.world;

        int steps = Math.min(HAZARD_LOOKAHEAD_STEPS, Math.max(1, (int) Math.ceil(maxDist / HAZARD_LOOKAHEAD_STEP)));
        double sampleY = worker.getEntityBoundingBox().minY;

        for (int i = 1; i <= steps; i++) {
            double t = i * HAZARD_LOOKAHEAD_STEP;
            double sx = startX + dirX * t;
            double sz = startZ + dirZ * t;

            BlockPos feet = new BlockPos(sx, sampleY, sz);
            if (isHazardousApproachPos(feet, waterIsSafe)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Same lookahead cadence as {@link #isHazardAhead}, but asks GapJumpEvaluator whether a real
     * jump would clear the ground ahead once the entity actually reaches it, instead of just
     * flagging "hazard, avoid". GapJumpEvaluator itself only evaluates from directly at the edge
     * (its cx is "the current column"), so this walks the same sample points forward and asks
     * "if I were standing here, would this be a jumpable gap" for each — using the entity's
     * current Y throughout, the same flat-approach assumption isHazardAhead already makes with
     * its own fixed sampleY.
     */
    private boolean isJumpableGapAhead(double startX, double startY, double startZ,
                                       double dirX, double dirZ, double maxDist) {
        World world = worker.world;
        long worldTime = world.getTotalWorldTime();
        int cy = MathHelper.floor(startY + 0.5D);

        int steps = Math.min(HAZARD_LOOKAHEAD_STEPS, Math.max(1, (int) Math.ceil(maxDist / HAZARD_LOOKAHEAD_STEP)));
        for (int i = 1; i <= steps; i++) {
            double t = i * HAZARD_LOOKAHEAD_STEP;
            int cx = MathHelper.floor(startX + dirX * t);
            int cz = MathHelper.floor(startZ + dirZ * t);

            GapJumpResult gap = GapJumpEvaluator.evaluate(world, worldTime, cx, cy, cz, dirX, dirZ);
            if (gap.type == GapJumpResult.Type.JUMPABLE) return true;
            if (gap.type == GapJumpResult.Type.UNJUMPABLE) return false; // real gap, can't clear it
            // NONE: no gap at this sample point yet (still solid ground) - keep scanning forward
        }
        return false;
    }

    private boolean isHazardousApproachPos(BlockPos feet, boolean waterIsSafe) {
        World world = worker.world;

        if (!world.isBlockLoaded(feet) || !world.isBlockLoaded(feet.down()) || !world.isBlockLoaded(feet.up())) {
            return true;
        }

        IBlockState at = world.getBlockState(feet);
        IBlockState up = world.getBlockState(feet.up());
        IBlockState below = world.getBlockState(feet.down());

        Block atBlock = at.getBlock();
        Block belowBlock = below.getBlock();

        if (at.getMaterial() == Material.LAVA || below.getMaterial() == Material.LAVA) return true;
        if (atBlock == Blocks.FIRE || belowBlock == Blocks.FIRE) return true;

        // Water is hazardous to a dry-land follower (avoid wandering into a lake for no reason),
        // but not to one already trailing a swimming leader — it needs to enter water to keep up.
        if (!waterIsSafe && (at.getMaterial() == Material.WATER || below.getMaterial() == Material.WATER)) return true;

        if (!(at.getMaterial().isReplaceable() || at.getCollisionBoundingBox(world, feet) == null)) {
            // Allow 1-block step-ups (climbable slope). If the position one block up is walkable
            // and not a drop, this is just terrain rising — not a real navigation hazard.
            if (!isClimbableStepUp(world, feet, waterIsSafe)) return true;
        }

        if (!(up.getMaterial().isReplaceable() || up.getCollisionBoundingBox(world, feet.up()) == null)) return true;

        if (isDropOrUnsafeEdge(feet, EDGE_DANGER_DEPTH, waterIsSafe)) return true;

        return false;
    }

    private boolean isClimbableStepUp(World world, BlockPos feet, boolean waterIsSafe) {
        BlockPos feetUp = feet.up();
        if (!world.isBlockLoaded(feetUp) || !world.isBlockLoaded(feetUp.up())) return false;
        IBlockState atUp = world.getBlockState(feetUp);
        IBlockState headUp = world.getBlockState(feetUp.up());
        boolean feetUpClear = atUp.getMaterial().isReplaceable() || atUp.getCollisionBoundingBox(world, feetUp) == null;
        boolean headUpClear = headUp.getMaterial().isReplaceable() || headUp.getCollisionBoundingBox(world, feetUp.up()) == null;
        return feetUpClear && headUpClear && !isDropOrUnsafeEdge(feetUp, EDGE_DANGER_DEPTH, waterIsSafe);
    }

    private boolean isDropOrUnsafeEdge(BlockPos feet, int maxDepth, boolean waterIsSafe) {
        World world = worker.world;

        for (int d = 1; d <= maxDepth; d++) {
            BlockPos check = feet.down(d);
            if (!world.isBlockLoaded(check)) {
                return true;
            }

            IBlockState state = world.getBlockState(check);
            Block block = state.getBlock();

            if (state.getMaterial() == Material.LAVA) {
                return true;
            }

            if (block == Blocks.FIRE) {
                return true;
            }

            if (!waterIsSafe && state.getMaterial() == Material.WATER) {
                return true;
            }

            BlockFaceShape shape = state.getBlockFaceShape(world, check, EnumFacing.UP);
            if (shape == BlockFaceShape.SOLID && !state.getMaterial().isLiquid()) {
                return false;
            }

            // Open water below a swimming follower's step is the normal medium, not a drop —
            // there is no "floor" concept to require while already swimming.
            if (waterIsSafe && state.getMaterial().isLiquid()) {
                return false;
            }
        }

        return true;
    }

    private boolean isCloseEnoughToStopSteering(GoalCandidate goal, EntityLivingBase target) {
        double dx = goal.x - worker.posX;
        double dz = goal.z - worker.posZ;
        double distSq = dx * dx + dz * dz;

        double stopSq = (target instanceof EntityTravellerWorker)
                ? DIRECT_STEER_STOP_WORKER_LEADER_SQ
                : DIRECT_STEER_STOP_NEAR_SLOT_SQ;

        return distSq <= stopSq;
    }

    /**
     * Tries a small fan of directions around the direct line to the goal, looking for one that
     * isn't hazard-blocked, and steers toward a local point along it instead of the literal goal.
     * Cheap: no pathfinding, just the same block-column hazard scan already used for the direct line.
     */
    private boolean tryObstacleDeflection(GoalCandidate goal, double dirX, double dirZ, double dist,
                                          double moveSpeed, boolean waterIsSafe) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (!wf.steeringObstacleAvoidanceEnabled) return false;

        double probeDist = Math.min(dist, HAZARD_LOOKAHEAD_STEP * HAZARD_LOOKAHEAD_STEPS);

        if (deflectionRecheckCooldown > 0) {
            deflectionRecheckCooldown--;
            if (hasCachedDeflectDir
                    && !isHazardAhead(worker.posX, worker.posY, worker.posZ, cachedDeflectDirX, cachedDeflectDirZ, probeDist, waterIsSafe)) {
                steerTowardLocalPoint(goal, cachedDeflectDirX, cachedDeflectDirZ, probeDist, moveSpeed);
                return true;
            }
        }

        double baseAngle = Math.atan2(dirZ, dirX);
        double stepRad = Math.toRadians(wf.steeringDeflectionStepDegrees);
        double maxRad = Math.toRadians(wf.steeringMaxDeflectionDegrees);

        for (double offset = stepRad; offset <= maxRad + 1.0E-6D; offset += stepRad) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double angle = baseAngle + sign * offset;
                double ndx = Math.cos(angle);
                double ndz = Math.sin(angle);

                if (!isHazardAhead(worker.posX, worker.posY, worker.posZ, ndx, ndz, probeDist, waterIsSafe)) {
                    cachedDeflectDirX = ndx;
                    cachedDeflectDirZ = ndz;
                    hasCachedDeflectDir = true;
                    deflectionRecheckCooldown = wf.steeringDeflectionRecheckIntervalTicks;
                    steerTowardLocalPoint(goal, ndx, ndz, probeDist, moveSpeed);
                    return true;
                }
            }
        }

        hasCachedDeflectDir = false;
        return false;
    }

    private void steerTowardLocalPoint(GoalCandidate goal, double dirX, double dirZ, double probeDist, double moveSpeed) {
        double px = worker.posX + dirX * probeDist;
        double pz = worker.posZ + dirZ * probeDist;
        worker.getMoveHelper().setMoveTo(px, goal.y, pz, moveSpeed);
    }

    /**
     * Last resort before stalling: reuse a nearby groupmate's recently found real path around
     * the same obstacle, if one exists and is still fresh.
     */
    private boolean tryFollowSharedCorridor(EntityLivingBase target, double moveSpeed) {
        TravellersConfig.WorkerFormation wf = TravellersConfig.WORKER_FORMATION;
        if (!wf.sharedRouteCorridorEnabled) return false;

        Vec3d waypoint = FollowRouteCache.findNearestUsableWaypoint(
                worker.world,
                target,
                worker.posX,
                worker.posZ,
                wf.sharedRouteCorridorMaxSnapDistance,
                wf.sharedRouteCorridorTtlTicks
        );

        if (waypoint == null) return false;

        worker.getMoveHelper().setMoveTo(waypoint.x, waypoint.y, waypoint.z, moveSpeed);
        return true;
    }

}