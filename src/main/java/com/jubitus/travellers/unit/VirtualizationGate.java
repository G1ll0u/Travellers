package com.jubitus.travellers.unit;

/**
 * The one rule for "may this loaded unit be handed to the offscreen simulation now". Callers decide WHEN
 * to ask (unloaded ground, frozen by vanilla); player distance is never part of it.
 *
 * <p>Identical for traveller, raider and worker: only physical blockers, never combat or a mission (the offscreen
 * simulation finishes those). {@code TravellerHandOff.forceResolvingBlockers()} bypasses this gate on purpose
 * (its callers cannot wait for a blocker to end).
 */
public final class VirtualizationGate {

    private VirtualizationGate() {
    }

    public static boolean mayVirtualize(EntityTraveller unit) {
        if (unit.isDebugPathTest()) return false;
        if (unit.isDead) return false;
        if (unit.isHandActive()) return false;
        // A bonded mount comes along into virtualization; any other ride (boat, someone else's horse) blocks.
        if (unit.isRiding() && !unit.isMountedOnBondedMount()) return false;
        // Mid-flight there is no sane position to hand to the offscreen simulation, which only walks records on the ground.
        if (unit.elytraFlight.isActive()) return false;
        return true;
    }
}
