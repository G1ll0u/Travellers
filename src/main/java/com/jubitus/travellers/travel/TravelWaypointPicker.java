package com.jubitus.travellers.travel;

import com.jubitus.travellers.unit.LandingSpots;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.follow.FollowRuntimeConfig;
import com.jubitus.travellers.pathing.TravellerNavigateGround;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import javax.annotation.Nullable;
import java.util.Random;

/** Picks the next travel waypoint: catchup height-aware, itinerary carrot, then straight and detour tiers. */
final class TravelWaypointPicker {

    private final EntityAITravel ai;
    private final EntityTraveller mob;

    TravelWaypointPicker(EntityAITravel ai, EntityTraveller mob) {
        this.ai = ai;
        this.mob = mob;
    }

    // Consecutive normal-progression waypoint computations (not boat mode, not the "arrived" case -
    // arrival is already handled before any of these call sites run) that found no candidate at all in
    // any direction. This is a much faster signal than the stall-based backstop above: an unloaded
    // chunk immediately ahead is detected the moment the route tries to advance into it, not several
    // seconds later once movement has visibly stalled.
    int consecutiveNoWaypoint = 0;
    private static final int NO_WAYPOINT_VIRTUALIZE_THRESHOLD = 2;

    /**
     * Wraps {@link #computeNextWaypointToward} for the normal route-advance call sites (waypoint
     * reached, retarget, no waypoint yet). If it genuinely can't find any loaded/navigable candidate in
     * any direction, twice in a row, first tries handing off to the offscreen simulation instead of
     * leaving the mob to bump around near the edge until the slower stall backstop kicks in -
     * {@code mob.handOff.tryAtLoadedBorder} itself still refuses to fire near a player, so this can never
     * make a unit vanish while someone is watching. Callers must check {@code mob.isDead} immediately
     * after calling this and bail out if true.
     *
     * <p>When virtualization also refuses (loaded terrain, not an unloaded edge - the pocket/enclosure
     * case this exists for), the blind local terrain probing that produced the repeated null has
     * already demonstrably failed: hand the real, farther destination straight to
     * {@code navigateTo()} instead of guessing another local hop. Vanilla's own search range bound
     * ({@code PathNavigate.getPathSearchRange()}) already caps the cost of that honest ask to the same
     * class of search every waypoint hop already pays for.
     */
    @Nullable
    BlockPos computeNextWaypointOrHandleBlocked() {
        BlockPos wp = computeNextWaypointToward(ai.targetVillage, ai.stepBlocks);
        if (wp != null) {
            consecutiveNoWaypoint = 0;
            return wp;
        }
        if (mob.world.isRemote) return null;
        if (++consecutiveNoWaypoint < NO_WAYPOINT_VIRTUALIZE_THRESHOLD) return null;
        if (mob.handOff.tryAtLoadedBorder(ai.targetVillage)) return null;
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_NO_WAYPOINT);
        return picked("destinationFallback", ai.targetVillage, ai.targetVillage);
    }

    /**
     * Compute a "next" waypoint toward 'dest' that is inside currently loaded chunks.
     * Does not force-load anything. Returns null if no safe waypoint is found.
     * Now tries straight toward the target first, then small symmetric detours.
     */
    BlockPos computeNextWaypointToward(BlockPos dest, int baseStepIgnored) {
        if (dest == null) return null;

        if (shouldUseHeightAwareCatchup(dest)) {
            BlockPos wp = computeHeightAwareWaypointToward(dest);
            if (wp != null) {
                return picked("heightAware", wp, dest);
            }
        }

        BlockPos from = mob.getPosition();
        Vec3d destVec = new Vec3d(dest.getX() - from.getX(), 0, dest.getZ() - from.getZ());
        if (destVec.lengthSquared() < 1.0) return null;

        Vec3d dir = destVec.normalize();

        int baseStep = dynamicStep(from, dest);
        int step = Math.min(
                baseStep + mob.getTravelCommon().getPathStepJitter(),
                (int) Math.sqrt(mob.getDistanceSqToCenter(dest))
        );
        step = Math.max(8, step);

        boolean boatMode = ai.isInBoat();

// ✅ Only clamp to navigator range when NOT in a boat
        if (!boatMode) {
            int navRange = (int) Math.max(16.0, mob.getNavigator().getPathSearchRange());
            step = Math.min(step, navRange - 8);
            step = Math.max(8, step);
        } else {
            // ✅ Boat steering is manual; ensure we can actually pick a waypoint
            step = Math.max(16, step);
        }

        if (boatMode) return ai.boats.afloatWaypoint(from, dest, dir, step);

        // --- Itinerary: the leg's route polyline, shared with the offscreen simulation ---
        BlockPos carrot = ai.itinerary.itineraryWaypoint(dest, step);
        if (carrot != null) return picked("itinerary", carrot, dest);

        // --- 1) straight candidates, no lane offset: the farthest one that resolves ---
        for (int s = step; s >= 16; s -= 16) {
            BlockPos candidate = ai.terrain.surfaceAtOffset(from, dir, s, Vec3d.ZERO);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate);
                if (candidate != null) {
                    return picked("straight", candidate, dest);
                }
            }
        }

        // --- 1b) short-leg tail: `step` can legitimately be below 16 (dynamicStep's own minStep is
        // 12, and any real distance-to-target under 16 forces it lower still), but the loop above
        // never tries anything under 16 - so a short leg fell straight through to the angle-based
        // detour tier below every single time. That tier takes the first candidate that resolves,
        // not the one scored best toward the target, and a fresh call a few ticks later - after the
        // entity has drifted a fraction of a block - can resolve a *different* angle first. Measured:
        // a debug-test traveller sent on a ~10-block hop (step computed as 10) repathed every ~3
        // ticks, each new waypoint shifted by about a block from the last, and it never reached
        // normal walking speed. This same tail runs, briefly and usually unnoticed, in the last
        // stretch of every real journey too - it just never dominated a whole trip before.
        if (step < 16) {
            for (int s = Math.min(step, 12); s >= 4; s -= 4) {
                BlockPos candidate = ai.terrain.surfaceAtOffset(from, dir, s, Vec3d.ZERO);
                if (candidate != null) {
                    candidate = preferShoreWhenInWater(from, candidate);
                    if (candidate != null) {
                        return picked("straightShort", candidate, dest);
                    }
                }
            }
        }

        // --- 2) small symmetric detours, still no lane offset ---
        // Reached only once every straight-tier distance has already failed to produce any
        // standable/loaded candidate - structurally rare, not the per-waypoint hot path, so the
        // extra A* reachability check below is affordable here.
        int detStep = Math.max(12, step / 2);
        int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};
        TravellerNavigateGround navGround = (TravellerNavigateGround) mob.getNavigator();

        for (int angleDeg : detAngles) {
            Vec3d r = ai.terrain.rotateY(dir, Math.toRadians(angleDeg));
            BlockPos candidate = ai.terrain.surfaceAtOffset(from, r, detStep, Vec3d.ZERO);
            if (candidate != null) {
                candidate = preferShoreWhenInWater(from, candidate);
                if (candidate != null && navGround.isReachable(candidate)) {
                    return picked("smallDetour" + angleDeg, candidate, dest);
                }
            }
        }

        // --- 3) tiny random reseed (unchanged) ---
        Random rand = mob.getRNG();
        for (int i = 0; i < 6; i++) {
            int nx = from.getX() + rand.nextInt(9) - 4;
            int nz = from.getZ() + rand.nextInt(9) - 4;
            BlockPos guess = new BlockPos(nx, from.getY(), nz);
            BlockPos surface = ai.terrain.findSurfaceOrWaterY(guess);
            if (surface != null && LoadedChunks.isAreaLoaded(mob.world, surface, 1) && ai.terrain.isNavigable(surface)) {
                return picked("randomReseed", surface, dest);
            }
        }

        return null;
    }

    /** Trace-only: records which tier produced a waypoint and how far it points toward the goal. */
    BlockPos picked(String source, BlockPos wp, BlockPos dest) {
        if (!mob.world.isRemote && com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(mob.getUniqueID())) {
            double fwd = ai.terrain.forwardComponent(wp, mob.getPosition(), TravelTerrain.goalDirXZ(mob, dest));
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    String.format(java.util.Locale.ROOT, "waypoint %d,%d,%d src=%s fwd=%.1f",
                            wp.getX(), wp.getY(), wp.getZ(), source, fwd));
        }
        return wp;
    }

    /**
     * Scans all 8 compass directions at 6 blocks to find any navigable position.
     * Used as a last resort when the entity is stuck in an enclosed space
     * and normal directional waypoints all fail.
     */
    @Nullable
    BlockPos findLocalEscapeWaypoint() {
        BlockPos from = mob.getPosition();
        TravellerNavigateGround navGround = (TravellerNavigateGround) mob.getNavigator();
        for (int a = 0; a < 8; a++) {
            double rad = Math.PI * 2.0 * a / 8.0;
            int nx = from.getX() + (int) Math.round(Math.sin(rad) * 6);
            int nz = from.getZ() + (int) Math.round(Math.cos(rad) * 6);
            BlockPos candidate = ai.terrain.findSurfaceOrWaterY(new BlockPos(nx, from.getY(), nz));
            if (candidate != null && LoadedChunks.isAreaLoaded(mob.world, candidate, 1) && ai.terrain.isNavigable(candidate)
                    && navGround.isReachable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    boolean tryDetourWide() {
        if (ai.targetVillage == null) return false;
        BlockPos from = mob.getPosition();
        Vec3d to = new Vec3d(ai.targetVillage.getX() - from.getX(), 0, ai.targetVillage.getZ() - from.getZ());
        if (to.lengthSquared() < 1.0) return false;

        Vec3d base = to.normalize();
        int follow = (int) Math.floor(mob.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE).getAttributeValue());
        int maxStep = Math.max(6, follow - 2);               // never ask pathfinder beyond follow range
        int detStep = Math.min(Math.max(24, ai.stepBlocks), maxStep);

        // Full circle, smallest deviation first - a candidate pointing away from the target is
        // exactly the shape of the real escape out of a U-shaped obstacle or a pocket, so it must
        // stay tryable, not filtered out (see acceptDetourCandidate). Each candidate is now also
        // reachability-validated, which is what actually rules out a dead end instead of geometry.
        int[] ANG = new int[]{30, -30, 60, -60, 90, -90, 120, -120, 150, -150, 180};
        TravellerNavigateGround navGround = (TravellerNavigateGround) mob.getNavigator();

        for (int angleDeg : ANG) {
            Vec3d dir = ai.terrain.rotateY(base, Math.toRadians(angleDeg));
            BlockPos candidate = ai.terrain.surfaceAtOffset(from, dir, detStep,
                    new Vec3d(-dir.z, 0, dir.x).normalize().scale(mob.getTravelCommon().getPathLaneOffset() * 0.5));

            if (candidate == null) continue; // no standable/loaded terrain at all at this angle

            if (!acceptDetourCandidate(candidate, from)) {
                com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                        "detour ang=" + angleDeg + " rejected:backslide");
                continue;
            }
            if (!navGround.isReachable(candidate)) {
                com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                        "detour ang=" + angleDeg + " rejected:unreachable");
                continue;
            }

            ai.currentWaypoint = candidate;
            mob.getTravelCommon().setCurrentWaypoint(candidate);
            ai.debugMark(ai.currentWaypoint);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path",
                    "detour ang=" + angleDeg + " accepted");
            com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_DETOUR_WIDE);
            return true;
        }
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_DETOUR_WIDE_FAILED);
        return false;
    }

    // XZ only: a travel waypoint is a surface column, its Y is incidental. A 3D check against a Y
    // resolved onto a canopy or a ledge can never be satisfied.
    boolean isRoadWaypointReached(BlockPos wp) {
        double reachSq = ai.reachDistSq;
        if (mob.isMountedOnBondedMount()) {
            double reach = TravellersConfig.MOUNTS.mountedWaypointReachRadius;
            reachSq = reach * reach;
        }
        double dx = mob.posX - (wp.getX() + 0.5);
        double dz = mob.posZ - (wp.getZ() + 0.5);
        return dx * dx + dz * dz <= reachSq;
    }

    /**
     * Compute a dynamic step size:
     * - Far from the village: use the full stepBlocks (e.g. 64).
     * - Closer in: shrink down so we can thread gates/streets precisely.
     */
    int dynamicStep(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));

        // How close is “arrival-ish”?
        // We already have targetNearSq from retarget(), so use that to define a “near” radius.
        double nearRadius = Math.max(24.0, Math.sqrt(ai.targetNearSq) * 2.0); // start shrinking inside ~2× arrival radius
        double farRadius = 160.0;  // fully “far” beyond this (tweak as needed)

        int minStep = 12;              // smallest waypoint step near village
        int maxStep = ai.stepBlocks; // e.g. 64

        // When a follower in catchup is within view distance, the MC 1.12 pathfinder must handle
        // the navigation (no more offscreen hops). Cap step to what the A* search can realistically
        // solve.
        boolean inViewCatchup = false;
        if (mob.isCatchupTravel() && mob.isInPlayerViewDistance()) {
            inViewCatchup = true;
            maxStep = Math.min(maxStep, 24);
        }

        // Compute interpolation factor t in [0,1]
        double t;
        if (dist <= nearRadius) {
            t = 0.0;         // at/inside near radius → minStep
        } else if (dist >= farRadius) {
            t = 1.0;         // far away → maxStep
        } else {
            t = (dist - nearRadius) / (farRadius - nearRadius);
        }

        int base = (int) Math.round(minStep + (maxStep - minStep) * t);

        // Never step farther than our actual distance, and never below minStep
        base = Math.min(base, (int) dist);
        base = Math.max(minStep, base);

        return base;
    }

    /** If we're in water, nudge the candidate to a nearby rim. */
    @Nullable
    private BlockPos preferShoreWhenInWater(BlockPos from, BlockPos candidateTowardTarget) {
        if (!mob.isInWater()) return candidateTowardTarget;
        if (candidateTowardTarget == null) return null;

        World w = mob.world;
        BlockPos c = candidateTowardTarget;

        Vec3d dir = new Vec3d(
                ai.targetVillage.getX() + 0.5 - (from.getX() + 0.5),
                0,
                ai.targetVillage.getZ() + 0.5 - (from.getZ() + 0.5)
        );
        if (dir.lengthSquared() < 1e-6) return candidateTowardTarget;
        dir = dir.normalize();

        int nx = c.getX() + (int) Math.signum(dir.x);
        int nz = c.getZ() + (int) Math.signum(dir.z);

        // NEW: only try same-Y exit, don’t force +1 steps from deep water
        BlockPos forwardSameY = new BlockPos(nx, c.getY(), nz);
        if (ai.terrain.isStandable(forwardSameY)) return forwardSameY;

        return candidateTowardTarget;
    }

    // A detour candidate is deliberately not required to point forward toward the target - the
    // whole point of a detour is that the direct/forward direction is blocked, so the real opening
    // out of a pocket or U-shaped obstacle is routinely sideways or behind. Reachability (checked
    // by the caller, via TravellerNavigateGround.isReachable) is what actually validates a
    // candidate now; this is just a tie-breaker among reachable ones.
    private boolean acceptDetourCandidate(BlockPos cand, BlockPos from) {
        if (cand == null || ai.targetVillage == null) return false;

        // Don't accept a waypoint that is clearly farther from target (allow tiny backslide)
        double cur = TravelTerrain.horizDistSq(from, ai.targetVillage);
        double cd = TravelTerrain.horizDistSq(cand, ai.targetVillage);
        double allowBackslideBlocks = 6.0;
        return !(cd > cur + allowBackslideBlocks * allowBackslideBlocks);
    }

    private boolean shouldUseHeightAwareCatchup(BlockPos dest) {
        if (!mob.isCatchupTravel()) return false;

        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();
        double vert = Math.abs(mob.getEntityBoundingBox().minY - dest.getY());
        boolean covered = ai.terrain.isCoveredGoal(dest, cfg.cave.minCoverBlocks);

        if (covered) return true;
        return vert >= cfg.catchup.heightAwareVerticalGap;
    }

    @Nullable
    private BlockPos computeHeightAwareWaypointToward(BlockPos dest) {
        BlockPos from = mob.getPosition();
        FollowRuntimeConfig cfg = FollowRuntimeConfig.get();

        Vec3d dir = new Vec3d(
                (dest.getX() + 0.5D) - (from.getX() + 0.5D),
                0.0D,
                (dest.getZ() + 0.5D) - (from.getZ() + 0.5D)
        );

        if (dir.lengthSquared() < 1.0D) {
            return pickBestHeightAwareCandidate(dest.getX(), dest.getZ(), dest.getY(), dest, from, ai.terrain.isCoveredGoal(dest, cfg.cave.minCoverBlocks));
        }

        dir = dir.normalize();

        double dist = Math.sqrt(from.distanceSq(dest));
        int step = MathHelper.clamp(dynamicStep(from, dest), 8, 20);
        boolean preferCovered = ai.terrain.isCoveredGoal(dest, cfg.cave.minCoverBlocks);

        for (int s = step; s >= 6; s -= 2) {
            double t = Math.min(1.0D, s / Math.max(1.0D, dist));
            int xBase = from.getX() + MathHelper.floor(dir.x * s);
            int zBase = from.getZ() + MathHelper.floor(dir.z * s);
            int yCenter = (int) Math.round(ai.terrain.lerp(from.getY(), dest.getY(), Math.min(0.95D, t + 0.10D)));

            BlockPos candidate = pickBestHeightAwareCandidate(xBase, zBase, yCenter, dest, from, preferCovered);
            if (candidate != null) {
                return candidate;
            }
        }

        return pickBestHeightAwareCandidate(dest.getX(), dest.getZ(), dest.getY(), dest, from, preferCovered);
    }

    @Nullable
    private BlockPos pickBestHeightAwareCandidate(int xBase,
                                                  int zBase,
                                                  int yCenter,
                                                  BlockPos dest,
                                                  BlockPos from,
                                                  boolean preferCovered) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int dy = -6; dy <= 6; dy++) {
                    BlockPos feet = new BlockPos(xBase + ox, yCenter + dy, zBase + oz);
                    if (!LoadedChunks.isAreaLoaded(mob.world, feet, 1)) continue;

                    if (ai.terrain.isSwimmable(feet)) {
                        BlockPos snapped = ai.terrain.snapToWaterSurface(feet);
                        if (snapped == null) continue;
                        feet = snapped;
                    } else {
                        if (!ai.terrain.isStandable(feet)) continue;
                        feet = ai.terrain.adjustTreeTopToGround(feet);
                        if (!ai.terrain.isStandable(feet)) continue;

                        IBlockState below = mob.world.getBlockState(feet.down());
                        if (!ai.terrain.isNaturalGroundSupport(feet.down()) && !com.jubitus.travellers.ground.Footing.isFloor(mob.world, feet.down(), below)) {
                            continue;
                        }
                    }

                    if (!ai.terrain.isNavigable(feet)) continue;

                    // A walking target, not a placement: buildings allowed.
                    if (!LandingSpots.isSafe(mob.world, feet, mob, true, true)) continue;

                    double score = 0.0D;
                    score += TravelTerrain.horizDistSq(feet, dest) * 0.06D;
                    score += Math.abs(feet.getY() - dest.getY()) * 2.25D;
                    score += Math.abs(feet.getY() - yCenter) * 0.65D;
                    score -= TravelTerrain.horizDistSq(from, feet) * 0.008D;

                    if (preferCovered && mob.world.canSeeSky(feet)) {
                        score += 30.0D;
                    }

                    if (score < bestScore) {
                        bestScore = score;
                        best = feet;
                    }
                }
            }
        }

        return best;
    }
}
