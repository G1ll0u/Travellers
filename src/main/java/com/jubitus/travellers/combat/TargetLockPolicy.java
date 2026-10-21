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
    // Trading blows: target this close (squared, 4 blocks) and a blow exchanged recently.
    public static final double CONTACT_DIST_SQ = 4.0D * 4.0D;
    public static final int CONTACT_BLOW_TICKS = 3 * 20;
    // An enemy this close beats a target still being approached (melee / archer).
    public static final double CLOSE_THREAT_DIST = 6.0D;
    public static final double ARCHER_CLOSE_THREAT_DIST = 8.0D;
    // How often an engaged unit looks around for a close threat.
    public static final int REASSESS_TICKS = 20;

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
        /** This unit is trading blows with its current target (see CONTACT_DIST_SQ). */
        public boolean inContact;
        public double currentDistSq;
        public double candidateDistSq;
        /** The candidate is attacking this unit. */
        public boolean candidateAttacksMe;
        public boolean archer;
    }

    /** Why a switch is allowed, or LOCKED when it is not. */
    public enum Verdict { CURRENT_USELESS, LEADER_ATTACKED, HIT_CLOSE, CLOSE_THREAT, LOCK_EXPIRED, LOCKED }

    public static int lockTicks(int recentThreats) {
        return recentThreats >= OUTNUMBERED_THREATS ? OUTNUMBERED_LOCK_TICKS : LOCK_TICKS;
    }

    public static double closeThreatDist(boolean archer) {
        return archer ? ARCHER_CLOSE_THREAT_DIST : CLOSE_THREAT_DIST;
    }

    /**
     * Priority ladder: leader threat > close hit on me > close threat while approaching > current target (locked)
     * > anything else. A unit in contact is loyal: only the first two move it.
     */
    public static Verdict decide(Situation s) {
        if (s.currentUseless) return Verdict.CURRENT_USELESS;
        if (s.candidateAttacksLeader) return Verdict.LEADER_ATTACKED;
        if (s.candidateHitsMeClose) return Verdict.HIT_CLOSE;
        if (s.inContact) return Verdict.LOCKED;
        if (isCloseThreat(s)) return Verdict.CLOSE_THREAT;
        return s.currentAgeTicks >= lockTicks(s.recentThreats) ? Verdict.LOCK_EXPIRED : Verdict.LOCKED;
    }

    /** Close enough to matter, and either coming at me or clearly nearer than what I am walking to (half the distance). */
    private static boolean isCloseThreat(Situation s) {
        double d = closeThreatDist(s.archer);
        if (s.candidateDistSq > d * d) return false;
        return s.candidateAttacksMe || s.candidateDistSq * 4.0D <= s.currentDistSq;
    }

    public static boolean mayReplace(Situation s) {
        return decide(s) != Verdict.LOCKED;
    }
}
