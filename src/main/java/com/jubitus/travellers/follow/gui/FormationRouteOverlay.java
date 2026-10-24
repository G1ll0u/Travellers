package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.MsgGroupRoute;
import com.jubitus.travellers.follow.net.MsgGroupRouteRequest;
import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worldmap.gui.MapRouteLine;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.UUID;

/** The planned route still ahead of the selected group (or lone worker) on the Formation Hub map, in the owner's colour. */
@SideOnly(Side.CLIENT)
public final class FormationRouteOverlay {

    private static final long REFRESH_MS = 1000L;

    @Nullable
    private static MsgGroupRoute route;
    @Nullable
    private static UUID shown;
    private static long lastRequestMs;

    private FormationRouteOverlay() {
    }

    public static void accept(MsgGroupRoute msg) {
        if (msg.workerId.equals(shown)) route = msg;
    }

    /** Called every frame with the worker whose route should show (null: none); asks the server about once a second. */
    static void track(@Nullable UUID worker) {
        if (worker == null || !worker.equals(shown)) {
            shown = worker;
            route = null;
            lastRequestMs = 0L;
        }
        long now = Minecraft.getSystemTime();
        if (shown != null && now - lastRequestMs > REFRESH_MS) {
            lastRequestMs = now;
            TravellersNetwork.CHANNEL.sendToServer(new MsgGroupRouteRequest(shown));
        }
    }

    static void clear() {
        shown = null;
        route = null;
    }

    static void draw(WorldMapController map) {
        MsgGroupRoute r = route;
        if (r == null || r.xs.length < 2) return;
        int color = 0xFF000000 | ClientOwnerVisualsCache.getColor();
        float cx = map.panel.getCenterXf(), cz = map.panel.getCenterZf(), bpp = map.panel.getZoomBlocksPerPixel();
        MapRouteLine.draw(map.mapX, map.mapY, map.mapW, map.mapH, cx, cz, bpp, r.xs, r.zs, i -> color);
        int last = r.xs.length - 1;
        MapRouteLine.dot(map.mapX + map.mapW / 2f + (r.xs[last] + 0.5f - cx) / bpp,
                map.mapY + map.mapH / 2f + (r.zs[last] + 0.5f - cz) / bpp, 2, color);
    }
}
