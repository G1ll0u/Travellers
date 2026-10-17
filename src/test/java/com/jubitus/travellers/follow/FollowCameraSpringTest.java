package com.jubitus.travellers.follow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowCameraSpringTest {

    @Test
    void convergesWithoutOvershoot() {
        FollowCameraSpring s = new FollowCameraSpring(0.3f, 240f);
        float v = 0f;
        for (int i = 0; i < 300; i++) {
            v = s.update(v, 90f, 1f / 60f);
            assertTrue(v <= 90.001f, "overshot: " + v);
        }
        assertEquals(90f, v, 0.5f);
    }

    @Test
    void takesTheShortWayAcrossTheSeam() {
        FollowCameraSpring s = new FollowCameraSpring(0.3f, 240f);
        float v = s.update(170f, -170f, 1f / 60f);
        assertTrue(v > 170f || v < -170f, "went the long way: " + v);
    }

    @Test
    void firstFrameIsNotAJump() {
        FollowCameraSpring s = new FollowCameraSpring(0.3f, 240f);
        float v = s.update(0f, 180f, 1f / 60f);
        assertTrue(Math.abs(v) < 5f, "jumped: " + v);
    }
}
