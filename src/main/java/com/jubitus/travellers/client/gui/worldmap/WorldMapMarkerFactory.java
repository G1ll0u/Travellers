package com.jubitus.travellers.client.gui.worldmap;

import net.minecraft.util.math.BlockPos;

public final class WorldMapMarkerFactory {

    public static final int COLOR_VILLAGE = 0xFFE8D5A3;
    public static final int COLOR_HOME    = 0xFFE8D5A3;

    private WorldMapMarkerFactory() {}

    public static GuiWorldMapPanel.Marker player(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, 0xFF000000 | (ownerRgb & 0xFFFFFF), 3,
                GuiWorldMapPanel.Marker.MarkerType.PLAYER, name);
    }

    public static GuiWorldMapPanel.Marker leader(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, 0xFF000000 | (ownerRgb & 0xFFFFFF), 3,
                GuiWorldMapPanel.Marker.MarkerType.LEADER, name);
    }

    public static GuiWorldMapPanel.Marker follower(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, 0xFF000000 | darken(ownerRgb, 0.50f), 2,
                GuiWorldMapPanel.Marker.MarkerType.FOLLOWER, name);
    }

    public static GuiWorldMapPanel.Marker village(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, COLOR_VILLAGE, 2,
                GuiWorldMapPanel.Marker.MarkerType.VILLAGE, name);
    }

    public static GuiWorldMapPanel.Marker home(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, COLOR_HOME, 3,
                GuiWorldMapPanel.Marker.MarkerType.HOME, name);
    }

    public static GuiWorldMapPanel.Marker home(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, 0xFF000000 | (ownerRgb & 0xFFFFFF), 3,
                GuiWorldMapPanel.Marker.MarkerType.HOME, name);
    }

    public static GuiWorldMapPanel.Marker controlledVillage(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, 0xFF000000 | (ownerRgb & 0xFFFFFF), 2,
                GuiWorldMapPanel.Marker.MarkerType.VILLAGE, name);
    }

    /** Village highlighted (selected / checked). */
    public static GuiWorldMapPanel.Marker villageHighlighted(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, 0xFFFFD060, 3,
                GuiWorldMapPanel.Marker.MarkerType.VILLAGE, name);
    }

    /** Enemy village (red). */
    public static GuiWorldMapPanel.Marker villageEnemy(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, 0xFFCC3333, 2,
                GuiWorldMapPanel.Marker.MarkerType.VILLAGE, name);
    }

    /** Enemy village that is also selected. */
    public static GuiWorldMapPanel.Marker villageEnemyHighlighted(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, 0xFFFF6644, 3,
                GuiWorldMapPanel.Marker.MarkerType.VILLAGE, name);
    }

    /** Darkens an RGB value (no alpha) by the given factor (0=black, 1=unchanged). */
    public static int darken(int rgb, float factor) {
        int r = clamp((int)(((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int)(((rgb >>  8) & 0xFF) * factor));
        int b = clamp((int)(( rgb        & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
