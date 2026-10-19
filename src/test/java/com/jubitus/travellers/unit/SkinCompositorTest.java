package com.jubitus.travellers.unit;

import com.jubitus.travellers.unit.render.baked.SkinCompositor;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkinCompositorTest {

    private static int at(int[] px, int u, int v) {
        return px[v * 64 + u];
    }

    @Test
    void cutoutOnlyTouchesTheSlotParts() {
        int[] dst = new int[64 * 32];
        Arrays.fill(dst, 0xFF000000);
        int[] armour = new int[64 * 32];
        Arrays.fill(armour, 0xFFFFFFFF);
        SkinCompositor.cutout(dst, armour, 0xFFFFFF, 64, 32, SkinCompositor.SLOT_FEET);
        assertEquals(0xFFFFFFFF, at(dst, 5, 20));   // leg
        assertEquals(0xFF000000, at(dst, 20, 20));  // body untouched
        assertEquals(0xFF000000, at(dst, 5, 5));    // head untouched
    }

    @Test
    void cutoutTintsAndDropsNearlyTransparentTexels() {
        int[] dst = new int[64 * 32];
        int[] armour = new int[64 * 32];
        Arrays.fill(armour, 0xFFFFFFFF);
        armour[20 * 64 + 20] = 0x10FFFFFF;
        SkinCompositor.cutout(dst, armour, 0xFF0000, 64, 32, SkinCompositor.SLOT_CHEST);
        assertEquals(0xFFFF0000, at(dst, 21, 20));
        assertEquals(0, at(dst, 20, 20));
    }

    @Test
    void overBlendsByAlpha() {
        int[] dst = {0xFF000000, 0x00000000};
        int[] src = {0x80FFFFFF, 0xFF00FF00};
        SkinCompositor.over(dst, src, 0xFFFFFF);
        assertEquals(0xFF808080, dst[0]);
        assertEquals(0xFF00FF00, dst[1]);
    }

    @Test
    void resizeStretchesAnySourceOntoTheLayout() {
        int[] src = new int[64 * 64];
        Arrays.fill(src, 0, 64 * 32, 1);
        Arrays.fill(src, 64 * 32, src.length, 2);
        int[] out = SkinCompositor.resize(new SkinCompositor.Image(64, 64, src), 64, 32);
        assertEquals(1, at(out, 0, 0));
        assertEquals(2, at(out, 0, 31));
        assertEquals(128, SkinCompositor.outputWidth(130));
        assertEquals(64, SkinCompositor.outputWidth(16));
    }
}
