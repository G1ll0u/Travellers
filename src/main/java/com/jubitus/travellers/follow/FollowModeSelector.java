package com.jubitus.travellers.follow;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;

public final class FollowModeSelector {

    private final EntityTraveller worker;

    private int caveCandidateTicks;
    private int openCandidateTicks;
    private int untrackedTicks;
    private int catchupCandidateTicks;
    private FollowMode stableLocalMode = FollowMode.SURFACE;

    private boolean cachedVisible = false;
    private int visibilityCooldown = 0;

    public FollowModeSelector(EntityTraveller worker) {
        this.worker = worker;
    }

    public void reset() {
        caveCandidateTicks = 0;
        openCandidateTicks = 0;
        untrackedTicks = 0;
        catchupCandidateTicks = 0;
        stableLocalMode = FollowMode.SURFACE;
        cachedVisible = false;
        visibilityCooldown = 0;
    }

    public FollowMode select(EntityLivingBase target, int pathFailTicks, int stagnationTicks) {
        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();

        if (worker.isTrackedByAnyPlayer()) {
            untrackedTicks = 0;
        } else {
            untrackedTicks++;
        }

        if (visibilityCooldown <= 0) {
            cachedVisible = worker.canEntityBeSeen(target) || target.canEntityBeSeen(worker);
            // Stagger refresh across workers using entity ID to avoid a ray-trace spike every N ticks.
            visibilityCooldown = 8 + Math.abs(worker.getEntityId() % 4);
        } else {
            visibilityCooldown--;
        }

        FollowContext ctx = FollowContext.sample(worker, target, cfg, cachedVisible);

        // CAVE's single-file geometry belongs to FollowFormationCoordinator, which only hired workers use;
        // a spawn-group follower (traveller, raider, merchant escort) still gets the CATCHUP escalation below.
        if (!worker.isHiredFollower()) {
            stableLocalMode = FollowMode.SURFACE;
        } else {
            boolean caveCandidate = ctx.underground || ctx.enclosed;
            if (caveCandidate) {
                caveCandidateTicks++;
                openCandidateTicks = 0;
            } else {
                openCandidateTicks++;
                caveCandidateTicks = 0;
            }

            // Small stagger so workers don't all request paths simultaneously at the cave entrance.
            int enterTicks = cfg.cave.enterModeTicks + Math.min(30, Math.max(0, (int) (ctx.dist3d * 0.5)));
            if (stableLocalMode != FollowMode.CAVE && caveCandidateTicks >= enterTicks) {
                stableLocalMode = FollowMode.CAVE;
            } else if (stableLocalMode == FollowMode.CAVE && openCandidateTicks >= cfg.cave.exitModeTicks) {
                stableLocalMode = FollowMode.SURFACE;
            }
        }

        // Debounced the same way the cave transition above already is: entering catchup abandons
        // whatever goal the current mode was walking toward in favour of EntityAITravel's own
        // waypoint stepping, which picks its destination on a completely different basis. A single
        // tick's trigger (most commonly a visibility raytrace losing line of sight behind foliage or
        // a low obstacle) used to be enough to switch, and the moment catchup's own progress closed
        // the distance back under the exit threshold elsewhere, follow mode resumed with yet another
        // fresh goal - see FollowRuntimeConfig.Catchup.enterDebounceTicks for the trace that caught it.
        if (shouldStartCatchup(ctx, pathFailTicks, stagnationTicks, cfg)) {
            catchupCandidateTicks++;
        } else {
            catchupCandidateTicks = 0;
        }
        if (catchupCandidateTicks >= cfg.catchup.enterDebounceTicks) {
            return FollowMode.CATCHUP;
        }

        return stableLocalMode;
    }

    private boolean shouldStartCatchup(FollowContext ctx,
                                       int pathFailTicks,
                                       int stagnationTicks,
                                       FollowRuntimeConfig cfg) {
        if (ctx == null) return false;

        // Use the distance to the worker's formation SLOT, not to the leader.
        // In large formations (100-500 workers), the slot can be very far from the leader.
        // A worker walking toward its distant slot is NOT lost — it shouldn't enter catchup.
        double distForCatchup = ctx.distToSlot;
        double verticalGapForCatchup = ctx.verticalGapToSlot;

        if (distForCatchup <= 32.0D) {
            // Mountain scenario: close horizontally but blocked by terrain + stagnating.
            // Threshold lowered to 8 blocks so moderate hills also trigger catchup.
            if (!ctx.underground
                    && verticalGapForCatchup >= 8.0D
                    && pathFailTicks >= cfg.catchup.verticalEmergencyPathFailTicks
                    && stagnationTicks >= cfg.catchup.verticalEmergencyStagnationTicks) {
                return true;
            }

            // Last resort: completely blocked for 15+ seconds despite being close to the slot.
            // Covers small mountains where the vertical gap is < 8 and no teleport rescue found
            // a valid landing spot — the travel AI will navigate around the obstacle.
            if (!ctx.underground
                    && distForCatchup > 5.0D
                    && pathFailTicks >= 30
                    && stagnationTicks >= 300) {
                return true;
            }

            return false;
        }

        if (ctx.underground || ctx.enclosed) {
            if (distForCatchup <= cfg.catchup.caveBlockCatchupDistance) {
                return false;
            }

            if (verticalGapForCatchup < cfg.catchup.verticalEmergencyGap) {
                return false;
            }

            return distForCatchup >= cfg.catchup.forceStartDistance
                    && pathFailTicks >= cfg.catchup.verticalEmergencyPathFailTicks
                    && stagnationTicks >= cfg.catchup.verticalEmergencyStagnationTicks;
        }

        if (distForCatchup >= cfg.catchup.forceStartDistance) {
            return true;
        }

        if (untrackedTicks >= cfg.catchup.minUntrackedTicks && distForCatchup >= cfg.catchup.startDistanceUntracked) {
            return true;
        }

        if (!ctx.visible && !ctx.underground && distForCatchup >= cfg.catchup.startDistanceHidden) {
            return true;
        }

        if (!ctx.visible && ctx.tracked && !ctx.underground && distForCatchup >= cfg.catchup.startDistanceTracked) {
            return true;
        }

        if (!ctx.underground
                && verticalGapForCatchup >= cfg.catchup.verticalEmergencyGap
                && distForCatchup >= cfg.catchup.verticalEmergencyStartDistance
                && (pathFailTicks >= cfg.catchup.verticalEmergencyPathFailTicks
                || stagnationTicks >= cfg.catchup.verticalEmergencyStagnationTicks)) {
            return true;
        }

        return false;
    }

    private static final class FollowContext {
        private final boolean visible;
        private final boolean tracked;
        private final boolean underground;
        private final boolean enclosed;
        private final double dist3d;
        private final double verticalGap;
        /** Distance to the worker's formation slot (or to leader if no slot is known). */
        private final double distToSlot;
        private final double verticalGapToSlot;

        private FollowContext(boolean visible,
                              boolean tracked,
                              boolean underground,
                              boolean enclosed,
                              double dist3d,
                              double verticalGap,
                              double distToSlot,
                              double verticalGapToSlot) {
            this.visible = visible;
            this.tracked = tracked;
            this.underground = underground;
            this.enclosed = enclosed;
            this.dist3d = dist3d;
            this.verticalGap = verticalGap;
            this.distToSlot = distToSlot;
            this.verticalGapToSlot = verticalGapToSlot;
        }

        private static FollowContext sample(EntityTraveller worker,
                                            EntityLivingBase target,
                                            FollowRuntimeConfig cfg,
                                            boolean visible) {
            boolean tracked = worker.isTrackedByAnyPlayer();
            boolean underground = FollowRecoveryHelper.isLeaderMeaningfullyUnderground(worker.world, target, cfg);
            boolean enclosed = FollowRecoveryHelper.isEnclosedFollowSpace(worker.world, target, cfg);
            double dist3d = worker.getDistance(target);
            double verticalGap = Math.abs(worker.posY - target.posY);

            // Use distance to the formation slot if available, otherwise fall back to leader.
            double distToSlot;
            double verticalGapToSlot;
            if (worker.hasFollowerSlotGoal()) {
                double dx = worker.posX - worker.getFollowerSlotGoalX();
                double dy = worker.posY - worker.getFollowerSlotGoalY();
                double dz = worker.posZ - worker.getFollowerSlotGoalZ();
                distToSlot = Math.sqrt(dx * dx + dy * dy + dz * dz);
                verticalGapToSlot = Math.abs(dy);
            } else {
                distToSlot = dist3d;
                verticalGapToSlot = verticalGap;
            }

            return new FollowContext(visible, tracked, underground, enclosed,
                    dist3d, verticalGap, distToSlot, verticalGapToSlot);
        }
    }
}