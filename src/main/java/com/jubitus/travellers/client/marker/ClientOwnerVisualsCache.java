package com.jubitus.travellers.client.marker;

import com.jubitus.travellers.server.marker.OwnerVisualsDefaults;

/**
 * Client-side holder of the local player's own mark color/style, kept in sync by
 * {@link com.jubitus.travellers.server.net.message.marker.MsgOwnerVisualsSync} instead of
 * being scavenged from worker map packets.
 */
public final class ClientOwnerVisualsCache {

    private static volatile int color = OwnerVisualsDefaults.DEFAULT_COLOR;
    private static volatile String styleId = OwnerVisualsDefaults.DEFAULT_STYLE;

    private ClientOwnerVisualsCache() {
    }

    public static void set(int color, String styleId) {
        ClientOwnerVisualsCache.color = color & 0xFFFFFF;
        ClientOwnerVisualsCache.styleId = styleId;
    }

    public static int getColor() {
        return color;
    }

    public static String getStyleId() {
        return styleId;
    }
}
