package com.jubitus.travellers.entity.entityai.follow.formations;

import com.jubitus.travellers.config.TravellersConfig;

import java.util.ArrayList;
import java.util.List;

public final class WorkerFormationPreviewLayout {

    private WorkerFormationPreviewLayout() {
    }

    /** Capped at 64 points — suitable for GUI previews. */
    public static List<Point> buildPreview(WorkerFormationSettings settings, int count) {
        return buildLayout(settings, Math.min(64, count));
    }

    /** No cap on count — suitable for map display with large groups. */
    public static List<Point> buildLayout(WorkerFormationSettings settings, int count) {
        WorkerFormationSettings s = settings.copy().clamp();
        int max = Math.max(1, count);
        List<Point> out = new ArrayList<>(max);

        // Escort slots come first in the output so the preview shows them near the leader
        int escorts = Math.min(s.numEscorts, max);

        int formation = max - escorts;
        if (formation <= 0) return out;

        switch (resolvePreviewStyle(s.style, formation)) {
            case LINE:
                buildLine(out, s, formation);
                break;
            case COLUMN:
                buildColumn(out, s, formation);
                break;
            case WEDGE:
                buildWedge(out, s, formation);
                break;
            case COMPACT:
            default:
                buildCompact(out, s, formation);
                break;
        }

        return out;
    }

    private static WorkerFormationStyle resolvePreviewStyle(WorkerFormationStyle style, int count) {
        if (style == WorkerFormationStyle.ADAPTIVE) {
            if (count <= 8)  return WorkerFormationStyle.WEDGE;
            if (count <= 60) return WorkerFormationStyle.COMPACT;
            return WorkerFormationStyle.LINE;
        }
        return style;
    }

    private static void buildCompact(List<Point> out, WorkerFormationSettings s, int count) {
        double dist = s.followDistance * 10.0D;
        double spacing = s.spacing * 10.0D;
        int width = Math.max(2, Math.min(s.width, TravellersConfig.WORKER_FORMATION.maxRowWidth) + 2);

        int i = 0;
        int row = 0;
        while (i < count) {
            int rowCount = row == 0 ? Math.min(width, count) : Math.min(width + row, count - i);
            double back = dist + row * spacing * 1.15D;

            for (int c = 0; c < rowCount && i < count; c++, i++) {
                double side = (c - (rowCount - 1) * 0.5D) * spacing;
                out.add(new Point((int) Math.round(side), (int) Math.round(back)));
            }

            row++;
        }
    }

    private static void buildLine(List<Point> out, WorkerFormationSettings s, int count) {
        double dist = s.followDistance * 10.0D;
        double spacing = s.spacing * 10.0D;
        // Mirror coordinator: perLine = exactly settings.width, no cap
        int perRow = Math.max(1, s.width);

        for (int i = 0; i < count; i++) {
            int row = i / perRow;
            int col = i % perRow;
            double side = (col - (perRow - 1) * 0.5D) * spacing * 1.2D;
            double back = dist + row * spacing;
            out.add(new Point((int) Math.round(side), (int) Math.round(back)));
        }
    }

    private static void buildColumn(List<Point> out, WorkerFormationSettings s, int count) {
        double dist = s.followDistance * 10.0D;
        double spacing = s.spacing * 9.0D;
        // Mirror coordinator: lanes = width, capped at the same server safety limit.
        int lanes = Math.max(1, Math.min(s.width, TravellersConfig.WORKER_FORMATION.maxRowWidth));

        for (int i = 0; i < count; i++) {
            int row = i / lanes;
            int lane = i % lanes;
            double side = lanes == 1 ? 0.0D : (lane - (lanes - 1) * 0.5D) * spacing * 0.6D;
            double back = dist + row * spacing;
            out.add(new Point((int) Math.round(side), (int) Math.round(back)));
        }
    }

    private static void buildWedge(List<Point> out, WorkerFormationSettings s, int count) {
        double dist = s.followDistance * 10.0D;
        double spacing = s.spacing * 9.5D;
        // Mirror coordinator: row growth caps at width, capped at the same server safety limit.
        int capWidth = Math.max(1, Math.min(s.width, TravellersConfig.WORKER_FORMATION.maxRowWidth));

        int i = 0;
        int row = 0;
        while (i < count) {
            int rowCount = row == 0 ? 1 : Math.min(Math.max(2, row * 2), capWidth);
            double back = dist + row * spacing;

            for (int c = 0; c < rowCount && i < count; c++, i++) {
                double side = rowCount == 1 ? 0.0D : (c - (rowCount - 1) * 0.5D) * spacing * 0.9D;
                out.add(new Point((int) Math.round(side), (int) Math.round(back)));
            }

            row++;
        }
    }

    public static final class Point {
        public final int x;
        public final int z;

        public Point(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}