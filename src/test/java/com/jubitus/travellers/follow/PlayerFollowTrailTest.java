package com.jubitus.travellers.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlayerFollowTrailTest {

    @Test
    void tinyMovesAreNotRecorded() {
        PlayerFollowTrail t = new PlayerFollowTrail();
        t.record(0, 64, 0);
        t.record(0.1, 64, 0);
        assertEquals(1, t.size());
    }

    @Test
    void walksCrumbsInOrder() {
        PlayerFollowTrail t = new PlayerFollowTrail();
        t.record(0, 64, 0);
        t.record(3, 64, 0);
        t.record(3, 64, 3);
        assertArrayEquals(new double[]{0, 64, 0}, t.nextWaypoint(-2, 64, 0));
        assertArrayEquals(new double[]{3, 64, 0}, t.nextWaypoint(0.2, 64, 0));
        assertArrayEquals(new double[]{3, 64, 3}, t.nextWaypoint(3, 64, 0.1));
    }

    @Test
    void passedCrumbIsSkipped() {
        PlayerFollowTrail t = new PlayerFollowTrail();
        t.record(0, 64, 0);
        t.record(2, 64, 0);
        t.record(4, 64, 0);
        // Player is already past the first crumb, nearer to the second.
        assertArrayEquals(new double[]{2, 64, 0}, t.nextWaypoint(1.5, 64, 1.0));
    }

    @Test
    void teleportDropsTrail() {
        PlayerFollowTrail t = new PlayerFollowTrail();
        t.record(0, 64, 0);
        t.record(2, 64, 0);
        t.record(100, 64, 0);
        assertEquals(1, t.size());
    }

    @Test
    void emptyOrReachedLastCrumbGivesNothing() {
        PlayerFollowTrail t = new PlayerFollowTrail();
        assertNull(t.nextWaypoint(0, 64, 0));
        t.record(1, 64, 0);
        assertNull(t.nextWaypoint(1, 64, 0));
    }
}
