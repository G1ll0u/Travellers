package com.jubitus.travellers.debug.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side holder for whatever the server last said about the traced travellers. Deliberately a
 * plain cache with a staleness timeout rather than anything that tracks entities itself: when a trace
 * ends the server simply stops sending, and the panel disappears on its own.
 */
@SideOnly(Side.CLIENT)
public final class ClientTraceHud {

    private ClientTraceHud() {
    }

    /** Nothing heard from the server for this long means no trace is running any more. */
    private static final long STALE_MS = 1500L;

    private static List<String> lines = new ArrayList<>();
    private static int[] tracedIds = new int[0];
    private static long lastUpdate;

    public static void accept(List<String> newLines, int[] newIds) {
        lines = newLines;
        tracedIds = newIds;
        lastUpdate = System.currentTimeMillis();
    }

    public static void clear() {
        lines = new ArrayList<>();
        tracedIds = new int[0];
        lastUpdate = 0L;
    }

    private static boolean isFresh() {
        return lastUpdate != 0L && System.currentTimeMillis() - lastUpdate < STALE_MS;
    }

    public static List<String> getLines() {
        return isFresh() ? lines : java.util.Collections.emptyList();
    }

    /** The traced entities that are currently loaded on this client, for the in-world markers. */
    public static List<Entity> getTracedEntities() {
        List<Entity> out = new ArrayList<>();
        if (!isFresh()) return out;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) return out;
        for (int id : tracedIds) {
            Entity e = mc.world.getEntityByID(id);
            if (e != null) out.add(e);
        }
        return out;
    }
}
