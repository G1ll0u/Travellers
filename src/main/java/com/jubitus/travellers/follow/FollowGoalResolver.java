package com.jubitus.travellers.follow;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.Footing;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.formations.FormationGeometry;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import javax.annotation.Nullable;
import java.util.List;

/** Where a follower stands: ground goal near its slot, sticky goal, candidate scoring, settle radius, when to re-pick. */
final class FollowGoalResolver {

    private final EntityAIFollowLeader ai;
    private final EntityTraveller follower;

    int goalRefreshCooldown;
    double lastGoalEvalLeaderX, lastGoalEvalLeaderY, lastGoalEvalLeaderZ;
    private double lastGoalEvalWorkerX, lastGoalEvalWorkerZ;
    boolean hasGoalEvalAnchor;

    private static final double STICKY_GOAL_EPSILON_SQ = 1.9D * 1.9D;
    private double stickyGoalX;
    private double stickyGoalY;
    private double stickyGoalZ;
    boolean hasStickyGoal;
    double cachedSettleRadius;
    double cachedSettleGoalX = Double.NaN;
    double cachedSettleGoalY = Double.NaN;
    double cachedSettleGoalZ = Double.NaN;

    FollowGoalResolver(EntityAIFollowLeader ai, EntityTraveller follower) {
        this.ai = ai;
        this.follower = follower;
    }

    GoalCandidate resolveGoal(EntityLivingBase target,
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
            idealX = FollowMath.lerp(idealX, target.posX, pull);
            idealZ = FollowMath.lerp(idealZ, target.posZ, pull);
            idealY = target.getEntityBoundingBox().minY;
        } else {
            // Surface: use terrain height at slot XZ so the search is ground-anchored,
            // not floating at the leader's altitude (critical for mountain terrain).
            int sxi = MathHelper.floor(idealX);
            int szi = MathHelper.floor(idealZ);
            int groundY = ColumnGround.topLayer(follower.world, sxi, szi, true);
            if (groundY != ColumnGround.NONE) idealY = groundY;
        }

        int horizontal = mode == FollowMode.CAVE ? cfg.cave.goalSearchHorizontal : cfg.surface.goalSearchHorizontal;
        int vertical = airborneLeader ? 2 : (mode == FollowMode.CAVE ? cfg.cave.goalSearchVertical : cfg.surface.goalSearchVertical);

        // While the leader is actually swimming, the goal search must be allowed to land in open
        // water too - otherwise a follower's slot always resolves back to the nearest dry ground,
        // which is what stranded a whole formation on the bank while the leader swam a wide river:
        // the goal simply never moved off the shore for the search to walk toward.
        boolean waterIsSafe = ai.isLeaderSwimming(target);

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

        double stickyDistSq = FollowMath.distanceSq(candidate.x, candidate.y, candidate.z, stickyGoalX, stickyGoalY, stickyGoalZ);
        boolean leaderStill = !slot.leaderActuallyMoving;
        boolean workerNearSticky = FollowMath.distanceSq(follower.posX, follower.posY, follower.posZ, stickyGoalX, stickyGoalY, stickyGoalZ) <= 3.8D * 3.8D;

        // When the coordinator's ideal slot moved away from where we last settled (settings changed),
        // skip all snap-back so workers actually navigate to the new formation positions.
        double idealStickyDriftSq = FollowMath.distanceSq(slot.idealX, slot.idealY, slot.idealZ, stickyGoalX, stickyGoalY, stickyGoalZ);
        boolean formationMoved = idealStickyDriftSq > 1.5D * 1.5D;

        if (slot.workerLeader && leaderStill) {
            if (!formationMoved && workerNearSticky && stickyDistSq <= 3.2D * 3.2D) {
                return new GoalCandidate(stickyGoalX, stickyGoalY, stickyGoalZ);
            }

            if (!formationMoved) {
                double selfDx = follower.posX - target.posX;
                double selfDz = follower.posZ - target.posZ;
                double selfDistSq = selfDx * selfDx + selfDz * selfDz;
                if (selfDistSq >= 3.0D * 3.0D && selfDistSq <= 8.0D * 8.0D) {
                    BlockPos selfFeet = new BlockPos(follower.posX, follower.getEntityBoundingBox().minY, follower.posZ);
                    if (isStandableFast(selfFeet)) {
                        return new GoalCandidate(follower.posX, selfFeet.getY(), follower.posZ);
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

        if (mode == FollowMode.CAVE && ai.stuck.orbitTicks >= cfg.cave.orbitTriggerTicks && workerNearSticky) {
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
        World world = follower.world;
        int xi = MathHelper.floor(idealX);
        int zi = MathHelper.floor(idealZ);

        // For surface follow, use the actual terrain height at the slot XZ instead of the
        // leader's altitude. This prevents goals from being computed in mid-air when the
        // leader is on a mountain and the slot XZ is at the base.
        int y0;
        if (mode != FollowMode.CAVE && !airborneLeader) {
            int groundY = ColumnGround.topLayer(world, xi, zi, true);
            y0 = MathHelper.clamp(groundY != ColumnGround.NONE ? groundY : MathHelper.floor(yHint), 2, world.getActualHeight() - 3);
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

        BlockPos selfFeet = new BlockPos(follower.posX, follower.getEntityBoundingBox().minY, follower.posZ);
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
        score += FollowMath.distanceSq2D(px, pz, idealX, idealZ);

        double distLeaderSq = FollowMath.distanceSq2D(px, pz, leader.posX, leader.posZ);
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
            double selfDistSq = FollowMath.distanceSq2D(px, pz, follower.posX, follower.posZ);
            score += selfDistSq * 0.55D;
        }

        if (isCurrentFeet) score -= 1.9D;
        if (mode == FollowMode.CAVE && ai.stuck.orbitTicks >= FollowRuntimeConfig.get().cave.orbitTriggerTicks && isCurrentFeet) score -= 4.5D;

        return score;
    }

    private double localCrowdingPenalty(EntityLivingBase leader, double x, double y, double z) {
        // Worker-only: getCrowdNeighbors scans for other hired workers specifically. A plain group
        // follower's RING offset is already a fixed personal spread (see FormationGeometry.
        // computeRingOffset) - unlike a worker's rank-based slot, it does not cluster followers onto
        // the same spot to begin with, so skipping this penalty is a safe no-op for groups.
        if (!follower.isHiredFollower()) return 0.0D;
        List<EntityTravellerWorker> neighbors =
                FollowFormationCoordinator.getCrowdNeighbors(leader, ai.requireWorker(), x, z);

        double penalty = 0.0D;
        boolean caveModeNow = ai.localMode == FollowMode.CAVE;

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

    double computeSettleRadius(FollowFormationCoordinator.SlotAssignment slot, GoalCandidate goal, FollowMode mode) {
        double radius = slot.arriveRadius;

        BlockPos feet = new BlockPos(goal.x, goal.y, goal.z);
        double slope = sampleSlopePenalty(feet);
        if (slope >= 1.0D) radius += 0.55D;
        if (slot.tunnelStrength >= 0.45D) radius += 0.25D;
        if (mode == FollowMode.CAVE && ai.stuck.orbitTicks >= FollowRuntimeConfig.get().cave.orbitTriggerTicks) radius += 0.8D;

        // A horse turns in a wide circle at speed (see the mount steering system's turn-rate/speed
        // notes) - asking it to settle inside a radius tighter than that circle makes it orbit the
        // slot forever instead of ever actually stopping on it, which is what the reported "circling"
        // behaviour was. Never let a formation's own (much tighter, pedestrian-tuned) arriveRadius
        // override this floor for a mounted follower.
        if (ai.isMounted()) {
            radius = Math.max(radius, TravellersConfig.WORKER_FORMATION.mountedMinSettleRadius);
        }

        return radius;
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

    private boolean isStandableFast(BlockPos feet) {
        return TerrainStandableCache.isStandable(follower.world, feet);
    }

    // Open water is also a valid goal while the leader itself swims, or the formation strands at the shore.
    private boolean isStandableOrSwimmable(BlockPos feet, boolean waterIsSafe) {
        if (isStandableFast(feet)) return true;
        return waterIsSafe && Footing.canSwimAt(follower.world, feet);
    }

    boolean shouldReevaluateGoal(EntityLivingBase target,
                                         FollowFormationCoordinator.SlotAssignment slot) {
        if (!ai.hasGoal || ai.currentGoal == null || !hasGoalEvalAnchor) {
            return true;
        }

        // XZ-only for movement thresholds: vertical movement while climbing hills
        // should not trigger mass re-evals — the goal XZ direction is still valid.
        // Large Y jumps (cliffs, jumps) are caught by a separate vertical threshold.
        double leaderMovedSq = FollowMath.distanceSq2D(target.posX, target.posZ, lastGoalEvalLeaderX, lastGoalEvalLeaderZ);
        double leaderMovedY   = Math.abs(target.posY - lastGoalEvalLeaderY);

        double workerMovedSq  = FollowMath.distanceSq2D(follower.posX, follower.posZ, lastGoalEvalWorkerX, lastGoalEvalWorkerZ);

        double idealShiftSq = FollowMath.distanceSq(
                ai.currentGoal.x, ai.currentGoal.y, ai.currentGoal.z,
                slot.idealX, slot.idealY, slot.idealZ
        );

        boolean workerLeader = slot.workerLeader;

        // playerCohesion is THE lever a player actually feels while walking: it tightens how far the
        // player has to move before a settled escort bothers to re-evaluate/re-path toward its slot
        // at all. This runs unconditionally every tick for a player-led follower (unlike the
        // idleAtSlot machinery above, which only ever engages for a follower leader) - so it's the
        // correct place for "walk WITH me instead of trailing", not a subtle side-effect elsewhere.
        double cohesionMul = workerLeader ? 1.0D : (1.0D - 0.55D * follower.getCachedPlayerCohesion());
        // Pedestrian-tuned thresholds get crossed almost every tick by a mounted follower's own
        // higher speed, forcing a brand new goal (and therefore a brand new A* search toward a
        // slightly different point) far more often than the leader actually needs it to. A trace
        // caught exactly this near a low tree: the path's own target and total length changed on
        // nearly every repath (dest 503->502->499->498->500->501, length 26->25->30->33->28->19),
        // which is what a zig-zag actually was - not one route being walked badly, but a new route
        // being committed to and abandoned before it was ever finished. Widening these when mounted
        // lets one search's route actually get walked to completion.
        double mountScale = ai.isMounted() ? TravellersConfig.WORKER_FORMATION.mountedCatchUpDistanceScale : 1.0D;
        double leaderMoveThresholdSq = (workerLeader ? (4.0D * 4.0D) : (2.5D * cohesionMul) * (2.5D * cohesionMul)) * mountScale * mountScale;
        double leaderMoveThresholdY  = (workerLeader ? 6.0D : 4.0D * cohesionMul) * mountScale;
        double workerMoveThresholdSq = (workerLeader ? (2.5D * 2.5D) : (2.0D * cohesionMul) * (2.0D * cohesionMul)) * mountScale * mountScale;
        double idealShiftThresholdSq = (workerLeader ? (5.5D * 5.5D) : (3.5D * cohesionMul) * (3.5D * cohesionMul)) * mountScale * mountScale;

        boolean noPath = follower.getNavigator().noPath();

        boolean forced =
                leaderMovedSq >= leaderMoveThresholdSq
                        || leaderMovedY  >= leaderMoveThresholdY
                        || workerMovedSq >= workerMoveThresholdSq
                        || idealShiftSq >= idealShiftThresholdSq
                        || (noPath && !ai.idleAtSlot && ai.settledTicks <= 0);

        if (forced) {
            goalRefreshCooldown = 0;
            return true;
        }

        if (ai.idleAtSlot) {
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

    void markGoalEvaluated(EntityLivingBase target, FollowMode mode,
                                    FollowFormationCoordinator.SlotAssignment slot) {
        lastGoalEvalLeaderX = target.posX;
        lastGoalEvalLeaderY = target.posY;
        lastGoalEvalLeaderZ = target.posZ;
        lastGoalEvalWorkerX = follower.posX;
        lastGoalEvalWorkerZ = follower.posZ;
        hasGoalEvalAnchor = true;

        if (slot.isEscort) {
            goalRefreshCooldown = 1 + Math.abs(follower.getEntityId() % 2);
            return;
        }

        int groupSize = slot.groupSize;
        boolean workerLeader = FollowMath.isHiredWorker(target);

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
            stagger = Math.abs(follower.getEntityId() * 13) % Math.max(2, base / 2);
        } else {
            stagger = Math.abs(follower.getEntityId() * 7) % 2;
        }

        goalRefreshCooldown = base + stagger;
    }
}
