package com.jubitus.travellers.unit.render.baked;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.render.RenderLodHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Beyond {@code bakedSkinMinDistance}, a unit is drawn in one pass with a texture that already holds
 * its skin, clothes, armour and owner mark. Baked lazily per look, budgeted per tick, bounded LRU.
 */
@SideOnly(Side.CLIENT)
public final class BakedSkins {

    private static final int MAX_ENTRIES = 256;
    private static final int BAKES_PER_TICK = 4;
    // A look that cannot be flattened (custom armour model, missing texture): always full render.
    private static final ResourceLocation UNBAKEABLE = new ResourceLocation("travellers", "baked_unbakeable");

    private static final Map<BakedSkinKey, ResourceLocation> BAKED =
            new LinkedHashMap<BakedSkinKey, ResourceLocation>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BakedSkinKey, ResourceLocation> eldest) {
                    if (size() <= MAX_ENTRIES) return false;
                    release(eldest.getValue());
                    return true;
                }
            };
    // Decoded source textures; a null value remembers a missing one.
    private static final Map<ResourceLocation, SkinCompositor.Image> SOURCES = new HashMap<>();

    private static long budgetTick = Long.MIN_VALUE;
    private static int bakesThisTick;

    private BakedSkins() {
    }

    /** The baked texture to draw this unit with this frame, or null for the normal layered render. */
    @Nullable
    public static ResourceLocation textureFor(EntityTraveller entity, LayerBipedArmor armorLayer) {
        if (!RenderLodHelper.isBeyond(entity, TravellersConfig.ENTITY_RENDER_LOD.bakedSkinMinDistance)) {
            return null;
        }
        BakedSkinKey key = BakedSkinKey.of(entity);
        ResourceLocation texture = BAKED.get(key);
        if (texture == null) {
            if (!takeBudget(entity.world.getTotalWorldTime())) return null;
            texture = bake(entity, key, armorLayer);
            BAKED.put(key, texture);
        }
        return texture == UNBAKEABLE ? null : texture;
    }

    /** Resource packs changed: every baked look and decoded source is stale. */
    public static void clear() {
        for (Iterator<ResourceLocation> it = BAKED.values().iterator(); it.hasNext(); ) {
            release(it.next());
            it.remove();
        }
        SOURCES.clear();
    }

    private static boolean takeBudget(long tick) {
        if (tick != budgetTick) {
            budgetTick = tick;
            bakesThisTick = 0;
        }
        if (bakesThisTick >= BAKES_PER_TICK) return false;
        bakesThisTick++;
        return true;
    }

    private static ResourceLocation bake(EntityTraveller entity, BakedSkinKey key, LayerBipedArmor armorLayer) {
        SkinCompositor.Image base = source(key.base);
        SkinCompositor.Image clothes = key.clothes != null ? source(key.clothes) : null;
        SkinCompositor.Image mark = key.mark != null ? source(key.mark) : null;
        if (base == null || (key.clothes != null && clothes == null) || (key.mark != null && mark == null)) {
            return UNBAKEABLE;
        }

        int slots = BakedSkinKey.SLOTS.length;
        SkinCompositor.Image[] armour = new SkinCompositor.Image[slots];
        SkinCompositor.Image[] overlay = new SkinCompositor.Image[slots];
        int maxWidth = Math.max(base.width, Math.max(clothes != null ? clothes.width : 0, mark != null ? mark.width : 0));
        for (int i = 0; i < slots; i++) {
            if (key.armour[i] == null) continue;
            EntityEquipmentSlot slot = BakedSkinKey.SLOTS[i];
            ItemStack stack = entity.getItemStackFromSlot(slot);
            if (!(stack.getItem() instanceof ItemArmor)) return UNBAKEABLE;
            ModelBiped defaultModel = armorLayer.getModelFromSlot(slot);
            if (ForgeHooksClient.getArmorModel(entity, stack, slot, defaultModel) != defaultModel) return UNBAKEABLE;
            armour[i] = source(armorLayer.getArmorResource(entity, stack, slot, null));
            if (armour[i] == null) return UNBAKEABLE;
            if (key.armourColor[i] != -1) {
                overlay[i] = source(armorLayer.getArmorResource(entity, stack, slot, "overlay"));
                if (overlay[i] == null) return UNBAKEABLE;
            }
            maxWidth = Math.max(maxWidth, armour[i].width);
        }

        int w = SkinCompositor.outputWidth(maxWidth);
        int h = w / 2;
        int[] pixels = SkinCompositor.resize(base, w, h);
        if (clothes != null) SkinCompositor.over(pixels, SkinCompositor.resize(clothes, w, h), 0xFFFFFF);
        for (int i = 0; i < slots; i++) {
            if (armour[i] == null) continue;
            int[][] regions = regionsFor(BakedSkinKey.SLOTS[i]);
            int color = key.armourColor[i];
            SkinCompositor.cutout(pixels, SkinCompositor.resize(armour[i], w, h), color == -1 ? 0xFFFFFF : color, w, h, regions);
            if (overlay[i] != null) {
                SkinCompositor.cutout(pixels, SkinCompositor.resize(overlay[i], w, h), 0xFFFFFF, w, h, regions);
            }
        }
        if (mark != null) SkinCompositor.over(pixels, SkinCompositor.resize(mark, w, h), key.markColor);

        DynamicTexture texture = new DynamicTexture(w, h);
        System.arraycopy(pixels, 0, texture.getTextureData(), 0, pixels.length);
        texture.updateDynamicTexture();
        return Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("travellers_baked", texture);
    }

    private static int[][] regionsFor(EntityEquipmentSlot slot) {
        switch (slot) {
            case HEAD:
                return SkinCompositor.SLOT_HEAD;
            case CHEST:
                return SkinCompositor.SLOT_CHEST;
            case LEGS:
                return SkinCompositor.SLOT_LEGS;
            default:
                return SkinCompositor.SLOT_FEET;
        }
    }

    @Nullable
    private static SkinCompositor.Image source(ResourceLocation location) {
        if (SOURCES.containsKey(location)) return SOURCES.get(location);
        SkinCompositor.Image image = null;
        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(location)) {
            BufferedImage img = TextureUtil.readBufferedImage(resource.getInputStream());
            int w = img.getWidth(), h = img.getHeight();
            image = new SkinCompositor.Image(w, h, img.getRGB(0, 0, w, h, null, 0, w));
        } catch (Exception ignored) {
            // Missing or unreadable: the look falls back to the full render.
        }
        SOURCES.put(location, image);
        return image;
    }

    private static void release(ResourceLocation texture) {
        if (texture != UNBAKEABLE) {
            Minecraft.getMinecraft().getTextureManager().deleteTexture(texture);
        }
    }
}
