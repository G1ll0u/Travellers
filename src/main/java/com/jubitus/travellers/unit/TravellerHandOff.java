package com.jubitus.travellers.unit;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/**
 * A loaded unit leaving the world for the offscreen simulation on its own initiative: the loaded
 * border probe, the stuck-at-border hand-off, and the forced hand-off that resolves soft blockers.
 * Whether it may virtualize at all is {@link VirtualizationGate}.
 */
public final class TravellerHandOff {

    // How far ahead the probe looks for loaded ground, clamped to the remaining distance.
    private static final int EDGE_PROBE_MIN_BLOCKS = 16;
    private static final int EDGE_PROBE_MAX_BLOCKS = 32;

    private final EntityTraveller unit;
    // Consecutive ticks spent facing unloaded terrain, and the tick the probe last ran (it is driven
    // from both the travel AI and the generic per-tick path, and must only count once per tick).
    private int edgeBlockedTicks = 0;
    private long edgeProbeLastTick = -1L;

    TravellerHandOff(EntityTraveller unit) {
        this.unit = unit;
    }

    /**
     * "The ground I am trying to walk onto is not loaded, so hand off to the offscreen simulation
     * instead of milling about at the boundary." Returns true if this call virtualized the entity.
     *
     * <p><b>Only for {@link com.jubitus.travellers.follow.EntityAIFollowLeader}
     * followers</b> (see {@code EntityTraveller.getEdgeProbeGoal()}) - a worker escorting a leader, or a traveller/raider
     * following its group leader. That AI never hands off to {@link com.jubitus.travellers.travel.EntityAITravel}
     * even when catching up over a long distance (it re-implements its own stepped waypoints instead),
     * so it has no equivalent of that task's progress-based stuck backstop and would otherwise only ever
     * leave via an actual chunk unload. Ordinary travel, go orders, missions and owner-catchup all end up
     * driven by {@code EntityAITravel} (directly, or via {@code FollowCatchup}),
     * which already measures "am I actually getting closer" over several seconds - a strictly more
     * reliable signal than a single directional sample, so this probe deliberately does not run for them
     * ({@code EntityTraveller.getEdgeProbeGoal()} returns null in that case).
     *
     * <p>Single directional sample, so it is inherently a guess - hardened against ever firing where a
     * player could notice: {@link TravellersConfig.TRAVEL#edgeStuckMinPlayerDistance} is checked first,
     * before any state here is even touched.
     */
    boolean tickEdgeProbe(@Nullable BlockPos goal) {
        if (unit.world == null || unit.world.isRemote || !(unit.world instanceof WorldServer)) return false;
        long now = unit.world.getTotalWorldTime();
        if (edgeProbeLastTick == now) return false;
        edgeProbeLastTick = now;

        if (goal == null) {
            edgeBlockedTicks = 0;
            return false;
        }
        double minPlayerDist = TravellersConfig.TRAVEL.edgeStuckMinPlayerDistance;
        if (minPlayerDist > 0
                && ((WorldServer) unit.world).getClosestPlayer(unit.posX, unit.posY, unit.posZ, minPlayerDist, false) != null) {
            edgeBlockedTicks = 0;
            return false;
        }
        // Just materialized: let it settle. A unit that appears right at the boundary would otherwise
        // be probed straight back out on its next tick, appearing and vanishing on the spot.
        if (com.jubitus.travellers.virtual.VirtualTravellerManager
                .get((WorldServer) unit.world).isInVirtualizeGrace(unit.getUniqueID())) {
            edgeBlockedTicks = 0;
            return false;
        }
        // Fighting in loaded terrain: stay physical and finish the fight. The offscreen battle sim
        // exists for units that are already virtual, not as a reason to pull one out of a fight it is
        // visibly having - and the goal used here is a travel destination the unit isn't pursuing
        // right now anyway.
        EntityLivingBase fighting = unit.getAttackTarget();
        if (fighting != null && fighting.isEntityAlive()) {
            edgeBlockedTicks = 0;
            return false;
        }

        if (!isGroundAheadUnloaded(goal)) {
            edgeBlockedTicks = 0;
            return false;
        }

        if (++edgeBlockedTicks < TravellersConfig.TRAVEL.edgeAheadUnloadedGraceTicks) return false;
        edgeBlockedTicks = 0;
        return tryAtLoadedBorder(goal);
    }

    /** True when the ground a short way ahead toward the goal is not loaded, i.e. this unit is at the loaded border. */
    private boolean isGroundAheadUnloaded(@Nullable BlockPos goal) {
        if (goal == null || !(unit.world instanceof WorldServer)) return false;
        BlockPos from = unit.getPosition();
        double dx = goal.getX() - from.getX();
        double dz = goal.getZ() - from.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq < 1.0) return false;
        double dist = Math.sqrt(distSq);
        int probeDist = (int) Math.max(EDGE_PROBE_MIN_BLOCKS, Math.min(EDGE_PROBE_MAX_BLOCKS, dist));
        int px = from.getX() + (int) Math.round(dx / dist * probeDist);
        int pz = from.getZ() + (int) Math.round(dz / dist * probeDist);
        return !LoadedChunks.isChunkLoaded(
                (WorldServer) unit.world, px >> 4, pz >> 4);
    }

    /**
     * Virtualize now, resolving the soft blockers {@link VirtualizationGate} normally
     * obeys instead of waiting them out: a boat this mod spawned is dismounted and killed, and a
     * raised hand is dropped. A bonded horse mount is NOT dismounted here - it is an allowed rider
     * (see {@link VirtualizationGate}) and {@code tryVirtualize()} below captures it
     * along with the entity.
     *
     * <p>Used by the two paths that get no second chance. {@link com.jubitus.travellers.virtual.ChunkUnloadVirtualizeHandler}
     * is one: whatever isn't captured as the chunk unloads is written into the chunk file instead and
     * stops existing as far as the offscreen simulation is concerned. The other is a unit vanilla has
     * stopped ticking ({@link com.jubitus.travellers.virtual.VirtualTravellerManager#isFrozenByVanilla}),
     * where the blockers are not merely inconvenient but permanent: an entity that stopped ticking
     * mid-meal keeps {@code isHandActive()} true forever, so declining to virtualize it for that
     * reason leaves it standing at the border for good.
     *
     * <p>Anything still ineligible after that (already dead, no server world) is left alone.
     */
    public boolean forceResolvingBlockers() {
        if (unit.isDead) return false;
        if (!(unit.world instanceof WorldServer)) return false;

        if (unit.isRiding() && !unit.isMountedOnBondedMount()) {
            Entity ride = unit.getRidingEntity();
            unit.dismountRidingEntity();
            if (ride instanceof EntityTravellerBoat) {
                ((EntityTravellerBoat) ride).requestKill();
            }
        }
        if (unit.isHandActive()) unit.resetActiveHand();
        // A flight cannot simply be waited out here: this path exists for units vanilla has stopped
        // ticking, where the controller will never get another tick to land them.
        boolean wasFlying = unit.elytraFlight.isActive();
        if (wasFlying) {
            unit.elytraFlight.reset();
            unit.setElytraFlying(false);
        }

        // virtualizeNow(), NOT tryVirtualize(): the eligibility gate is deliberately bypassed here.
        // Every condition it protects assumes the unit is still running its AI and will finish what it
        // is doing, and this method exists precisely for the cases where it never will - a frozen unit,
        // or a chunk about to be serialized. Re-asking the gate is what left units stuck forever with a
        // blocker that can no longer end: a worker awaiting a pickup nobody can perform, one parked
        // inside its job village's arrival radius, a hand raised mid-meal. The physical blockers were
        // just resolved above, so there is nothing left for the gate to legitimately catch.
        boolean virtualized = virtualizeNow();
        // virtualizeNow() can still refuse (a record already owns this identity) after the flight was
        // already cleared
        // above - left as-is, that strands the entity mid-air with the elytra flag off and nobody
        // steering it: an uncontrolled freefall from whatever height it was at, wings still on its back.
        // Re-arm a forced landing instead so it comes down under control.
        if (!virtualized && wasFlying) {
            unit.elytraFlight.emergencyRecover();
        }
        return virtualized;
    }

    /**
     * The virtualize itself, with no eligibility check. Its callers have already
     * established that this unit must leave the world: {@link #forceResolvingBlockers()} after resolving the physical blockers by hand.
     */
    private boolean virtualizeNow() {
        if (!(unit.world instanceof net.minecraft.world.WorldServer)) return false;
        net.minecraft.world.WorldServer ws = (net.minecraft.world.WorldServer) unit.world;
        com.jubitus.travellers.virtual.VirtualTravellerManager mgr =
                com.jubitus.travellers.virtual.VirtualTravellerManager.get(ws);
        // Workers carry hire/mission/escort state that virtualizeTraveller() doesn't know how to
        // capture (it only writes the plain traveller/raider fields) - they must go through the
        // worker-specific virtualize path or that state is silently dropped.
        // The manager's own answer, never an assumed true: it refuses when a virtual record already
        // holds this identity, and the caller that matters most (ChunkUnloadVirtualizeHandler) is
        // relying on "returned true" meaning "this entity is dead now" - its whole safety argument is
        // that the body is gone before the chunk is serialized.
        if (unit instanceof EntityTravellerWorker) {
            return mgr.virtualizeWorker(ws, (EntityTravellerWorker) unit, unit.world.getTotalWorldTime());
        }
        return mgr.virtualizeTraveller(ws, unit, unit.world.getTotalWorldTime());
    }

    /**
     * Hands this unit to the offscreen simulation when the caller (travel AI, boat mode) has
     * exhaustively established that it cannot go anywhere and the ground ahead is not loaded: the
     * loaded border, not a cliff or a wall. Never with a player within
     * {@code TRAVEL.edgeStuckMinPlayerDistance}.
     */
    public boolean tryAtLoadedBorder(@Nullable BlockPos goal) {
        if (unit.world.isRemote || !(unit.world instanceof WorldServer)) return false;
        if (unit.isAutoEating() || unit.isHandActive()) return false;
        // Stuck on a cliff or a wall is not a reason to disappear: only the loaded border is.
        if (!isGroundAheadUnloaded(goal)) return false;

        final WorldServer ws = (WorldServer) unit.world;
        double minDist = TravellersConfig.TRAVEL.edgeStuckMinPlayerDistance;
        if (minDist > 0 && ws.getClosestPlayer(unit.posX, unit.posY, unit.posZ, minDist, false) != null) {
            return false;
        }

        // Resolve the soft blockers rather than obeying them. This path is only ever reached after the
        // caller has exhaustively established that the unit is going nowhere, so a blocker here is not
        // "wait, it is busy" but "it will be busy forever". A boat is the case that made this visible:
        // riding one fails the ordinary gate, EntityAITravel's boat branch returns before the stall
        // ladder that would notice, and checkVirtualize's non-frozen path refuses it too - so a traveller
        // whose boat ran out of resolvable waypoints at the edge of loaded terrain sat in the water
        // indefinitely. Plain tryVirtualize() also meant falling through to setDead() below, silently
        // deleting the unit instead of handing it to the offscreen simulation.
        com.jubitus.travellers.virtual.VirtualizationLog.virtualize("stuck at loaded border", unit);
        return forceResolvingBlockers();
    }
}
