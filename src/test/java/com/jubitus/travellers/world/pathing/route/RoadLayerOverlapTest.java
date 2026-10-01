package com.jubitus.travellers.world.pathing.route;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadLayerOverlapTest {

    private static List<BlockPos> line(int y0, int y1, int z, int... xs) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            int y = y0 + (y1 - y0) * i / Math.max(1, xs.length - 1);
            out.add(new BlockPos(xs[i], y, z));
        }
        return out;
    }

    @Test
    void aRoadUnderABridgeTakesTheDeckHeight() {
        List<BlockPos> bridge = line(105, 100, -24, -26, -68);
        List<BlockPos> roadUnder = Arrays.asList(new BlockPos(-24, 105, -25), new BlockPos(-40, 91, -30),
                new BlockPos(-56, 92, -24), new BlockPos(-68, 100, -24));
        RoadLayer layer = RoadLayer.build(Arrays.asList(bridge, roadUnder), 0.5, 16);
        int checked = 0;
        for (int i = 0; i < layer.size(); i++) {
            if (layer.xs[i] == -40 && layer.zs[i] == -30) {
                assertEquals(true, layer.ys[i] >= 100, "ravine vertex raised to the deck, got " + layer.ys[i]);
                checked++;
            }
        }
        assertEquals(1, checked, "the ravine vertex is part of the layer");
    }

    @Test
    void theTwoLegsOfOneSwitchbackStayApart() {
        List<BlockPos> hairpin = Arrays.asList(new BlockPos(0, 60, 0), new BlockPos(16, 64, 0),
                new BlockPos(16, 68, 4), new BlockPos(0, 72, 4));
        RoadLayer layer = RoadLayer.build(java.util.Collections.singletonList(hairpin), 0.5, 16);
        assertEquals(60, layer.ys[0], "the lower leg keeps its own height");
    }
}
