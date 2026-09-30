package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.CultureRegistry;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws a static 16x16 front-facing head for a worker, sampled from the front of its
 * culture skin (player-skin layout). Falls back to a solid owner-colored square when the
 * texture cannot be resolved. No per-frame I/O: the skin height is read once and cached.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHeadRenderer {

    // Skin height (32 for legacy Millenaire skins, 64 for modern), cached per texture.
    private static final Map<ResourceLocation, Integer> HEIGHT_CACHE = new HashMap<>();

    private WorkerHeadRenderer() {}

    public static void draw(String cultureId, int textureIndex, int ownerColor, int x, int y, int size) {
        ResourceLocation tex = resolveTexture(cultureId, textureIndex);
        if (tex == null) {
            Gui.drawRect(x, y, x + size, y + size, 0xFF000000 | (ownerColor & 0xFFFFFF));
            return;
        }

        float tileH = skinHeight(tex);
        GlStateManager.color(1F, 1F, 1F, 1F);
        GlStateManager.enableBlend();
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        // Player-skin layout: face front = (8,8) 8x8, hat overlay = (40,8) 8x8.
        Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, size, size, 64, tileH);
        Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, size, size, 64, tileH);
    }

    private static ResourceLocation resolveTexture(String cultureId, int textureIndex) {
        if (cultureId == null || cultureId.isEmpty()) return null;
        try {
            CultureDef c = CultureRegistry.get(cultureId);
            if (c == null) return null;
            // Every leader shown here is an EntityTravellerWorker, whose TextureIdx is rolled
            // against the worker pool, not the traveller one (see EntityTraveller.getActiveAppearance()).
            TravellerAppearance app = c.getWorkerAppearance(textureIndex);
            return app != null ? app.baseTexture : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static int skinHeight(ResourceLocation rl) {
        Integer h = HEIGHT_CACHE.get(rl);
        if (h != null) return h;
        int height = 64;
        try {
            IResource res = Minecraft.getMinecraft().getResourceManager().getResource(rl);
            BufferedImage img = TextureUtil.readBufferedImage(res.getInputStream());
            if (img != null) height = img.getHeight();
        } catch (Exception ignored) {
            // default 64
        }
        HEIGHT_CACHE.put(rl, height);
        return height;
    }
}
