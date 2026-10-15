package com.jubitus.travellers.combat;

/**
 * The one rule deciding whether a live attack target may be replaced by another one.
 * Pure logic: every input is computed by the caller (TravellerCombatLogic).
 */
public final class TargetLockPolicy {

    // Lock in a calm or crowded fight: a unit keeps its target against passing hits.
    public static final int LOCK_TICKS = 4 * 20;
    // Lock when several enemies are on me: shorter, so the unit can turn on whoever is killing it.
    public static final int OUTNUMBERED_LOCK_TICKS = 2 * 20;
    // Distinct recent aggressors that make a unit "outnumbered".
    public static final int OUTNUMBERED_THREATS = 3;
    // An aggressor this close breaks the lock (squared distance, 3 blocks).
    public static final double CLOSE_HIT_DIST_SQ = 3.0D * 3.0D;

    private TargetLockPolicy() {
    }

    /** What the caller knows about the switch being requested. */
    public static final class Situation {
        /** The current target is dead, ignored or otherwise already worthless. */
        public boolean currentUseless;
        /** Ticks since the current target was adopted. */
        public int currentAgeTicks;
        /** Distinct enemies that recently hit this unit. */
        public int recentThreats;
        /** The candidate is attacking this unit's leader/owner. */
        public boolean candidateAttacksLeader;
        /** The candidate is the one that just hit this unit and is within CLOSE_HIT_DIST_SQ. */
        public boolean candidateHitsMeClose;
    }

    public static int lockTicks(int recentThreats) {
        return recentThreats >= OUTNUMBERED_THREATS ? OUTNUMBERED_LOCK_TICKS : LOCK_TICKS;
    }

    /** Priority ladder: leader threat > close hit on me > current target (locked) > anything else. */
    public static boolean mayReplace(Situation s) {
        if (s.currentUseless) return true;
        if (s.candidateAttacksLeader) return true;
        if (s.candidateHitsMeClose) return true;
        return s.currentAgeTicks >= lockTicks(s.recentThreats);
    }
}
