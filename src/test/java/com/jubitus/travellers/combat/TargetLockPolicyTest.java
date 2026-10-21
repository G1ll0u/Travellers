package com.jubitus.travellers.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetLockPolicyTest {

    /** Approaching a target 20 blocks away; the candidate is 15 blocks away (no close threat). */
    private static TargetLockPolicy.Situation locked(int age) {
        TargetLockPolicy.Situation s = new TargetLockPolicy.Situation();
        s.currentAgeTicks = age;
        s.currentDistSq = 20 * 20;
        s.candidateDistSq = 15 * 15;
        return s;
    }

    @Test
    void youngTargetIsKeptAgainstPassingHit() {
        assertFalse(TargetLockPolicy.mayReplace(locked(10)));
    }

    @Test
    void lockExpiresAfterFourSeconds() {
        assertFalse(TargetLockPolicy.mayReplace(locked(TargetLockPolicy.LOCK_TICKS - 1)));
        assertTrue(TargetLockPolicy.mayReplace(locked(TargetLockPolicy.LOCK_TICKS)));
    }

    @Test
    void outnumberedUnitHasShorterLock() {
        TargetLockPolicy.Situation s = locked(TargetLockPolicy.OUTNUMBERED_LOCK_TICKS);
        s.recentThreats = TargetLockPolicy.OUTNUMBERED_THREATS;
        assertTrue(TargetLockPolicy.mayReplace(s));
        s.recentThreats = TargetLockPolicy.OUTNUMBERED_THREATS - 1;
        assertFalse(TargetLockPolicy.mayReplace(s));
        assertEquals(TargetLockPolicy.LOCK_TICKS, TargetLockPolicy.lockTicks(0));
    }

    @Test
    void leaderThreatAndCloseHitBreakTheLock() {
        TargetLockPolicy.Situation a = locked(0);
        a.candidateAttacksLeader = true;
        assertTrue(TargetLockPolicy.mayReplace(a));
        TargetLockPolicy.Situation b = locked(0);
        b.candidateHitsMeClose = true;
        assertTrue(TargetLockPolicy.mayReplace(b));
    }

    @Test
    void uselessTargetIsAlwaysReplaced() {
        TargetLockPolicy.Situation s = locked(0);
        s.currentUseless = true;
        assertTrue(TargetLockPolicy.mayReplace(s));
    }

    @Test
    void verdictNamesTheRuleThatAllowedTheSwitch() {
        TargetLockPolicy.Situation s = locked(0);
        assertEquals(TargetLockPolicy.Verdict.LOCKED, TargetLockPolicy.decide(s));
        s.candidateHitsMeClose = true;
        assertEquals(TargetLockPolicy.Verdict.HIT_CLOSE, TargetLockPolicy.decide(s));
        s.candidateAttacksLeader = true;
        assertEquals(TargetLockPolicy.Verdict.LEADER_ATTACKED, TargetLockPolicy.decide(s));
        assertEquals(TargetLockPolicy.Verdict.LOCK_EXPIRED, TargetLockPolicy.decide(locked(TargetLockPolicy.LOCK_TICKS)));
    }

    @Test
    void enemyCrossingThePathBeatsAFarTarget() {
        TargetLockPolicy.Situation s = locked(0);
        s.currentDistSq = 30 * 30;
        s.candidateDistSq = 2 * 2;
        assertEquals(TargetLockPolicy.Verdict.CLOSE_THREAT, TargetLockPolicy.decide(s));
    }

    @Test
    void closeEnemyNotClearlyNearerNeedsToBeComingAtMe() {
        TargetLockPolicy.Situation s = locked(0);
        s.currentDistSq = 8 * 8;
        s.candidateDistSq = 5 * 5;
        assertEquals(TargetLockPolicy.Verdict.LOCKED, TargetLockPolicy.decide(s));
        s.candidateAttacksMe = true;
        assertEquals(TargetLockPolicy.Verdict.CLOSE_THREAT, TargetLockPolicy.decide(s));
    }

    @Test
    void archerTurnsOnARusherSooner() {
        TargetLockPolicy.Situation s = locked(0);
        s.currentDistSq = 40 * 40;
        s.candidateDistSq = 7 * 7;
        s.candidateAttacksMe = true;
        assertEquals(TargetLockPolicy.Verdict.LOCKED, TargetLockPolicy.decide(s));
        s.archer = true;
        assertEquals(TargetLockPolicy.Verdict.CLOSE_THREAT, TargetLockPolicy.decide(s));
    }

    @Test
    void unitInContactOnlyMovesForUrgency() {
        TargetLockPolicy.Situation s = locked(TargetLockPolicy.LOCK_TICKS * 10);
        s.inContact = true;
        s.currentDistSq = 2 * 2;
        s.candidateDistSq = 1;
        s.candidateAttacksMe = true;
        assertEquals(TargetLockPolicy.Verdict.LOCKED, TargetLockPolicy.decide(s));
        s.candidateHitsMeClose = true;
        assertEquals(TargetLockPolicy.Verdict.HIT_CLOSE, TargetLockPolicy.decide(s));
    }
}
