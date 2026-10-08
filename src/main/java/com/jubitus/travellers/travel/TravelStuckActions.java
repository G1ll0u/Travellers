package com.jubitus.travellers.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.ArrayList;
import java.util.List;

/** The travel task's side of the stuck ladder: the evidence it feeds and the rescue each rung runs. */
final class TravelStuckActions {

    private final EntityAITravel ai;
    private final EntityTraveller mob;

    private static final double NET_MOVE_1S_SQ = 0.35 * 0.35; // < 0.35 blocks over 1s == effectively stuck
    private static final int FAILED_HOP_RETRY_TICKS = 2 * 20;
    private static final int EDGE_RETRY_TICKS = 20;
    private static final int UNDERGROUND_CACHE_TICKS = 20;
    // Movement-based stuck signal over 1 s windows (prevents jitter resetting progress)
    Vec3d moveWindowStart = Vec3d.ZERO;
    int moveWindowTicks = 0;
    boolean moveWindowInit = false;
    int noMoveTicks = 0;
    // Underground state cache (avoid world queries every tick)
    private boolean cachedUnderground = false;
    int undergroundCacheRefreshTick = -999;
    // Ticks spent underground in a row (the ladder hops sooner)
    int undergroundTicks = 0;

    TravelStuckActions(EntityAITravel ai, EntityTraveller mob) {
        this.ai = ai;
        this.mob = mob;
    }

    /** Pinned-in-place and underground counters the ladder reads, updated once per travel tick. */
    void tickMotion(Vec3d now) {
        if (!moveWindowInit) {
            moveWindowStart = now;
            moveWindowTicks = 0;
            moveWindowInit = true;
        }

        moveWindowTicks++;
        if (moveWindowTicks >= 20) { // 1 second window
            double dx = now.x - moveWindowStart.x;
            double dz = now.z - moveWindowStart.z;
            double netSq = dx * dx + dz * dz;

            if (netSq < NET_MOVE_1S_SQ) {
                noMoveTicks += 20; // accumulate in ticks so your existing thresholds still work
            } else {
                noMoveTicks = 0;
            }

            moveWindowStart = now;
            moveWindowTicks = 0;
        }

        // Underground detector (caves, overhangs), cached every 20 ticks
        if ((mob.ticksExisted - undergroundCacheRefreshTick) >= UNDERGROUND_CACHE_TICKS) {
            cachedUnderground = ai.terrain.isUndergroundNow();
            undergroundCacheRefreshTick = mob.ticksExisted;
        }
        if (cachedUnderground) undergroundTicks++;
        else undergroundTicks = 0;
    }

    StuckRecord stuck() {
        return mob.getStuckRecord();
    }

    /** What a stuck episode is about: the stop for normal travel, catchup as a whole for a moving goal. */
    Object episodeKey() {
        return mob.isCatchupTravel() ? StuckRecord.CATCHUP : ai.targetVillage;
    }

    static TravelStuckLadder.Timings ladderTimings() {
        TravellersConfig.Travel t = TravellersConfig.TRAVEL;
        return new TravelStuckLadder.Timings(t.stuckRepathSeconds * 20, t.stuckDetourSeconds * 20,
                t.stuckReplanSeconds * 20, t.stuckHopSeconds * 20, t.stuckPinnedHopSeconds * 20,
                t.stuckUndergroundHopSeconds * 20, t.stuckHopCooldownSeconds * 20, FAILED_HOP_RETRY_TICKS,
                t.edgeStuckVirtualizeGraceTicks, EDGE_RETRY_TICKS);
    }

    void runStuckAction(TravelStuckLadder.Action action) {
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "ladder " + action
                + " stall=" + stuck().ladder.stallTicks() + " pin=" + noMoveTicks + " underground=" + undergroundTicks);
        switch (action) {
            case REPATH:
                // Is the horse the reason? Stepping down beats detouring around a wall a rider can't pass.
                if (mob.isMountedOnBondedMount()) ai.mounts.considerTerrainDismount(ai.currentWaypoint, true);
                ai.paths.clearNav();
                ai.currentWaypoint = ai.waypoints.computeNextWaypointOrHandleBlocked();
                if (mob.isDead) return;
                mob.getTravelCommon().setCurrentWaypoint(ai.currentWaypoint);
                ai.debugMark(ai.currentWaypoint);
                ai.paths.pushPathToWaypoint(true);
                break;
            case DETOUR:
                ai.paths.clearNav();
                if (!ai.waypoints.tryDetourWide()) {
                    BlockPos escape = ai.waypoints.findLocalEscapeWaypoint();
                    if (escape != null) {
                        ai.currentWaypoint = escape;
                        mob.getTravelCommon().setCurrentWaypoint(escape);
                    } else {
                        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "dead end: no reachable detour or escape");
                        stuck().ladder.deadEnd(ladderTimings());
                    }
                }
                ai.paths.pushPathToWaypoint(true);
                break;
            case REPLAN:
                if (ai.itinerary.abandonUnreachableGate()) break;
                com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_STUCK_REPLAN);
                ai.itinerary.nextItineraryReplanTick = 0;
                ai.itinerary.replanItineraryFromHere("no headway for " + stuck().ladder.stallTicks() + " ticks");
                ai.currentWaypoint = null;
                mob.getTravelCommon().setCurrentWaypoint(null);
                ai.paths.clearNav();
                break;
            case HOP:
                if (!ai.itinerary.abandonUnreachableGate()) hop();
                break;
            case EDGE_VIRTUALIZE:
                // Refuses unless the ground ahead is unloaded: a unit stuck on loaded terrain keeps climbing the ladder.
                mob.handOff.tryAtLoadedBorder(ai.targetVillage);
                break;
            default:
                break;
        }
    }

    /** A short, discreet hop: out of the water, up out of a hole, or a few blocks along the route. */
    void hop() {
        com.jubitus.travellers.debug.trace.TravellerTracer.incident(mob, "stuck",
                "hop firing pin=" + noMoveTicks + " stall=" + stuck().ladder.stallTicks() + " underground=" + undergroundTicks);
        // Each failed hop reaches further, so a unit is never left retrying the same impossible hop.
        int failed = stuck().ladder.failedHops();
        int maxHop = Math.max(TravellersConfig.TRAVEL.stuckHopBlocks, TravellersConfig.TRAVEL.stuckHopMaxBlocks);
        boolean rescued = mob.isInWater() && ai.rescue.teleportToNearestShoreTowardTarget(8, 6);
        if (!rescued) {
            int step = Math.min(maxHop, TravellersConfig.TRAVEL.stuckHopBlocks * (1 + failed));
            TravelRescue.HopResult r = ai.rescue.teleportToResumePoint(resumeRoute(step));
            if (r == TravelRescue.HopResult.DEFERRED) {
                stuck().ladder.hopDeferred();
                return;
            }
            rescued = r == TravelRescue.HopResult.MOVED;
        }
        if (!rescued) rescued = ai.rescue.teleportToSurfaceRescue(Math.min(maxHop, 5 * (1 + failed)), failed == 0 ? 6 : 3);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "stuck", "hop result=" + (rescued ? "moved" : "failed"));
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(mob, rescued
                ? com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_RESCUE_TP
                : com.jubitus.travellers.debug.profile.MovementAnomaly.TRAVEL_RESCUE_FAILED);
        if (rescued) {
            ai.resetStuckState();
            ai.currentWaypoint = ai.waypoints.computeNextWaypointToward(ai.targetVillage, ai.stepBlocks);
            mob.getTravelCommon().setCurrentWaypoint(ai.currentWaypoint);
            ai.debugMark(ai.currentWaypoint);
            ai.paths.pushPathToWaypoint();
            return;
        }
        stuck().ladder.hopFailed(ladderTimings());
    }

    /**
     * Hop candidates along the way the unit means to go, nearest first: (landing column, onward point
     * {@code TRAVEL.stuckHopOnwardBlocks} further on). The itinerary when it has one, else its current
     * waypoint then its goal. Never a straight line at the destination past the waypoint.
     */
    private List<BlockPos[]> resumeRoute(int step) {
        int onward = TravellersConfig.TRAVEL.stuckHopOnwardBlocks;
        int[] dists = {step, step + 4, step + 8};
        List<BlockPos[]> out = new ArrayList<>(dists.length);
        int y = MathHelper.floor(mob.posY);
        ItineraryState st = mob.getItineraryState();
        if (mob.isItineraryDriven() && ai.targetVillage != null && st.isFor(ai.targetVillage)) {
            Itinerary it = st.get();
            st.cursor.advance(it, mob.posX, mob.posZ);
            for (int d : dists) {
                st.cursor.computeCarrot(it, d);
                BlockPos col = new BlockPos(MathHelper.floor(st.cursor.carrotX), y, MathHelper.floor(st.cursor.carrotZ));
                st.cursor.computeCarrot(it, d + onward);
                out.add(new BlockPos[]{col, new BlockPos(MathHelper.floor(st.cursor.carrotX), y, MathHelper.floor(st.cursor.carrotZ))});
            }
            return out;
        }
        if (ai.targetVillage == null) return out;
        double[] xs, zs;
        if (ai.currentWaypoint != null) {
            xs = new double[]{mob.posX, ai.currentWaypoint.getX() + 0.5D, ai.targetVillage.getX() + 0.5D};
            zs = new double[]{mob.posZ, ai.currentWaypoint.getZ() + 0.5D, ai.targetVillage.getZ() + 0.5D};
        } else {
            xs = new double[]{mob.posX, ai.targetVillage.getX() + 0.5D};
            zs = new double[]{mob.posZ, ai.targetVillage.getZ() + 0.5D};
        }
        for (int d : dists) {
            out.add(new BlockPos[]{pointAlong(xs, zs, d, y), pointAlong(xs, zs, d + onward, y)});
        }
        return out;
    }

    private static BlockPos pointAlong(double[] xs, double[] zs, double dist, int y) {
        for (int i = 0; i + 1 < xs.length; i++) {
            double dx = xs[i + 1] - xs[i], dz = zs[i + 1] - zs[i];
            double len = Math.sqrt(dx * dx + dz * dz);
            if (dist <= len || i + 2 == xs.length) {
                double t = len < 1e-6 ? 0.0 : Math.min(1.0, dist / len);
                return new BlockPos(MathHelper.floor(xs[i] + dx * t), y, MathHelper.floor(zs[i] + dz * t));
            }
            dist -= len;
        }
        return new BlockPos(MathHelper.floor(xs[xs.length - 1]), y, MathHelper.floor(zs[zs.length - 1]));
    }
}
