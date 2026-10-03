package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.TravellersGuiTheme;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** One village of the send / recruit lists, as received from the server. */
final class VillageRow {

    final BlockPos center;
    final String display;
    final int distance;

    VillageRow(BlockPos center, String display, int distance) {
        this.center = center;
        this.display = display;
        this.distance = distance;
    }

    /** Rows built from the server's three parallel lists, nearest first. */
    static List<VillageRow> fromServer(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        List<VillageRow> rows = new ArrayList<>();
        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) rows.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        rows.sort(Comparator.comparingInt(v -> v.distance));
        return rows;
    }

    /** "Name (120m)", the name cut in the middle to fit maxWidth; drops a distance the server already appended. */
    String label(FontRenderer fr, int maxWidth) {
        String name = display;
        int idx = display.lastIndexOf(" (");
        if (idx > 0 && display.endsWith("m)")) name = display.substring(0, idx);
        return TravellersGuiTheme.shortenMiddle(fr, name, " (" + distance + "m)", maxWidth);
    }
}
