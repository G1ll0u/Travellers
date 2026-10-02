package com.jubitus.travellers.unit.render;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Distance-based LOD check shared by traveller render layers, so cosmetic
 * layers can be skipped for entities far from the camera (matters when many
 * travellers are visible at once).
 */
public final class RenderLodHelper {

    private RenderLodHelper() {
    }

    public static boolean isBeyond(Entity entity, int maxDistance) {
        if (!TravellersConfig.ENTITY_RENDER_LOD.enabled) {
            return false;
        }
        // A GUI portrait must always show the full outfit, however far the entity has wandered
        // while the menu was open (the game keeps running behind it).
        if (com.jubitus.travellers.client.gui.hub.WorkerPortraitRenderer.isRenderingPortrait()) {
            return false;
        }
        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null) {
            return false;
        }
        double maxDistSq = (double) maxDistance * (double) maxDistance;
        return player.getDistanceSq(entity) > maxDistSq;
    }
}