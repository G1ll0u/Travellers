package com.jubitus.travellers.unit.render.baked;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.render.RenderLodHelper;
import com.jubitus.travellers.unit.render.textures.LayerTravellerClothes;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/** Everything that changes a unit's flattened look; equal keys share one baked texture. */
final class BakedSkinKey {

    // Composite order: inner leggings first, then the outer armour pieces.
    static final EntityEquipmentSlot[] SLOTS = {
            EntityEquipmentSlot.LEGS, EntityEquipmentSlot.CHEST, EntityEquipmentSlot.FEET, EntityEquipmentSlot.HEAD};

    final ResourceLocation base;
    @Nullable
    final ResourceLocation clothes;
    final Item[] armour = new Item[SLOTS.length];
    final int[] armourMeta = new int[SLOTS.length];
    final int[] armourColor = new int[SLOTS.length];
    @Nullable
    final ResourceLocation mark;
    final int markColor;
    private final int hash;

    private BakedSkinKey(EntityTraveller e) {
        base = e.getActiveAppearance().baseTexture;
        clothes = RenderLodHelper.isBeyond(e, TravellersConfig.ENTITY_RENDER_LOD.clothesLayerMaxDistance)
                ? null : LayerTravellerClothes.clothesFor(e);
        for (int i = 0; i < SLOTS.length; i++) {
            ItemStack stack = e.getItemStackFromSlot(SLOTS[i]);
            if (stack.getItem() instanceof ItemArmor && ((ItemArmor) stack.getItem()).getEquipmentSlot() == SLOTS[i]) {
                ItemArmor item = (ItemArmor) stack.getItem();
                armour[i] = item;
                armourMeta[i] = stack.getMetadata();
                armourColor[i] = item.hasOverlay(stack) ? item.getColor(stack) & 0xFFFFFF : -1;
            }
        }
        EntityTravellerWorker worker = e instanceof EntityTravellerWorker ? (EntityTravellerWorker) e : null;
        if (worker != null && worker.shouldRenderOwnerMark()
                && !RenderLodHelper.isBeyond(e, TravellersConfig.ENTITY_RENDER_LOD.armbandLayerMaxDistance)) {
            mark = worker.getOwnerMarkStyle().getTexture(worker.isVisualLeader());
            markColor = worker.getOwnerMarkColor();
        } else {
            mark = null;
            markColor = 0;
        }
        hash = Objects.hash(base, clothes, Arrays.hashCode(armour), Arrays.hashCode(armourMeta),
                Arrays.hashCode(armourColor), mark, markColor);
    }

    static BakedSkinKey of(EntityTraveller e) {
        return new BakedSkinKey(e);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BakedSkinKey)) return false;
        BakedSkinKey k = (BakedSkinKey) o;
        return hash == k.hash && markColor == k.markColor && base.equals(k.base)
                && Objects.equals(clothes, k.clothes) && Objects.equals(mark, k.mark)
                && Arrays.equals(armour, k.armour) && Arrays.equals(armourMeta, k.armourMeta)
                && Arrays.equals(armourColor, k.armourColor);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
