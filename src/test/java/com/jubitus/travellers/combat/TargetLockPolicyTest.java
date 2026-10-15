package com.jubitus.travellers.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetLockPolicyTest {

    private static TargetLockPolicy.Situation locked(int age) {
        TargetLockPolicy.Situation s = new TargetLockPolicy.Situation();
        s.currentAgeTicks = age;
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
}
