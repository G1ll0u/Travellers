package com.jubitus.travellers.worldmap;

import com.jubitus.travellers.worldmap.gui.BattleStrikeAnimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BattleStrikeAnimatorTest {

    private static List<float[]> arrowsAt(BattleStrikeAnimator a, long now) {
        List<float[]> out = new ArrayList<>();
        a.forEachArrow(now, (argb, hx, hz, tx, tz) -> out.add(new float[]{hx, hz, tx, tz}));
        return out;
    }

    @Test
    void arrowFliesFromArcherToTargetThenDisappears() {
        BattleStrikeAnimator a = new BattleStrikeAnimator();
        a.addArrow(0, 0, 100, 0, 0xFFFF0000, 1000);
        assertEquals(0, arrowsAt(a, 999).size(), "not started yet");
        List<float[]> mid = arrowsAt(a, 1000 + BattleStrikeAnimator.ARROW_FLIGHT_MS / 2);
        assertEquals(1, mid.size());
        assertEquals(50f, mid.get(0)[0], 0.5f);
        assertTrue(mid.get(0)[2] < mid.get(0)[0], "trail stays behind the head");
        assertEquals(0, arrowsAt(a, 1000 + BattleStrikeAnimator.ARROW_FLIGHT_MS + 1).size());
        assertTrue(a.isIdle());
    }

    @Test
    void lungeGoesTowardOpponentAndBack() {
        BattleStrikeAnimator a = new BattleStrikeAnimator();
        UUID id = UUID.randomUUID();
        a.addLunge(id, 0, 0, 0, 10, 0);
        float[] out = new float[3];
        assertTrue(a.lunge(id, BattleStrikeAnimator.LUNGE_MS / 2, out));
        assertEquals(0f, out[0], 1e-4f);
        assertEquals(1f, out[1], 1e-4f);
        assertEquals(1f, out[2], 1e-3f);
        assertFalse(a.lunge(id, BattleStrikeAnimator.LUNGE_MS + 1, out));
    }

    @Test
    void lungeWithoutDirectionIsIgnored() {
        BattleStrikeAnimator a = new BattleStrikeAnimator();
        UUID id = UUID.randomUUID();
        a.addLunge(id, 5, 5, 5.1f, 5, 0);
        assertFalse(a.lunge(id, 10, new float[3]));
    }

    @Test
    void simultaneousArrowsAreCapped() {
        BattleStrikeAnimator a = new BattleStrikeAnimator();
        for (int i = 0; i < 500; i++) a.addArrow(0, 0, 10, 10, 0xFFFFFFFF, 0);
        assertEquals(64, arrowsAt(a, 100).size());
    }
}
