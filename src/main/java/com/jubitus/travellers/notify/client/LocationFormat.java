package com.jubitus.travellers.notify.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * Client-side distance+cardinal phrasing relative to the live player position (e.g. "39m to the
 * SE"), computed at display time rather than baked in server-side, so it stays meaningful
 * regardless of when the underlying event actually happened (including offline-queued notices
 * shown after login).
 */
public final class LocationFormat {

    private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private LocationFormat() {
    }

    /** Null if the player or its dimension can't be resolved (e.g. location in another dimension). */
    @Nullable
    public static String describe(BlockPos loc, int dimension) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null || player.world == null || player.world.provider.getDimension() != dimension) {
            return null;
        }

        double dx = loc.getX() - player.posX;
        double dz = loc.getZ() - player.posZ;
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

        return I18n.format("travellers.notify.location_suffix", distance, cardinal(dx, dz));
    }

    /** Compass direction (N, NE, ... NW) of a world offset; +X is east, +Z is south. */
    public static String cardinal(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360.0;
        return CARDINALS[((int) Math.round(angle / 45.0)) % 8];
    }
}
