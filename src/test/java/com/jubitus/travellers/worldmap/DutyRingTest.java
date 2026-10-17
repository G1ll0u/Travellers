package com.jubitus.travellers.worldmap;

import com.jubitus.travellers.worldmap.gui.DutyRing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DutyRingTest {

    private static List<UUID> ids(int n) {
        List<UUID> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new UUID(0, i));
        return out;
    }

    private static DutyRing ring(List<UUID> members, float markerPx, float bpp) {
        DutyRing r = new DutyRing();
        r.clear();
        for (UUID id : members) r.add(id, 100, 200);
        r.layout();
        r.setScale(markerPx, bpp);
        return r;
    }

    @Test
    void workersStayClearOfTheZoneIconAndOfEachOther() {
        List<UUID> members = ids(5);
        float markerPx = 6f, bpp = 4f;
        DutyRing r = ring(members, markerPx, bpp);
        for (int i = 0; i < members.size(); i++) {
            float[] a = r.position(members.get(i));
            double fromZone = Math.hypot(a[0] - 100.5, a[1] - 200.5) / bpp;
            assertTrue(fromZone >= markerPx * 2f - 1e-3, "on the icon: " + fromZone);
            for (int j = i + 1; j < members.size(); j++) {
                float[] b = r.position(members.get(j));
                assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) / bpp >= markerPx * 2f - 1e-3, "overlapping");
            }
        }
    }

    @Test
    void crowdedRingWidensSoNeighboursDoNotOverlap() {
        List<UUID> members = ids(30);
        DutyRing r = ring(members, 6f, 1f);
        float[] a = r.position(members.get(0));
        float[] b = r.position(members.get(1));
        assertTrue(Math.hypot(a[0] - b[0], a[1] - b[1]) >= 12f * 0.99f);
    }

    @Test
    void slotsDoNotDependOnArrivalOrder() {
        List<UUID> members = ids(4);
        DutyRing r1 = ring(members, 6f, 2f);
        List<UUID> reversed = new ArrayList<>(members);
        java.util.Collections.reverse(reversed);
        DutyRing r2 = ring(reversed, 6f, 2f);
        for (UUID id : members) assertArrayEquals(r1.position(id), r2.position(id), 1e-4f);
        assertFalse(r1.covers(new UUID(1, 1)));
    }
}
