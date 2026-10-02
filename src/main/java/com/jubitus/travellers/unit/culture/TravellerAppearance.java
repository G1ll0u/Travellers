package com.jubitus.travellers.unit.culture;

import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public final class TravellerAppearance {
    public final ResourceLocation baseTexture;
    public final java.util.List<ResourceLocation> clothes = new java.util.ArrayList<>();
    /** From the villager txt's baseheight= field; Millenaire default is 1.0 when absent. */
    public final float baseHeight;

    /** From the same villager txt's defaultweapon=/startingInv= fields, if any were present. */
    @Nullable
    public VillagerEquipmentProfile equipmentProfile;

    /** The villager .txt's own filename (without extension), e.g. "farmer" or "merchant_art" - the
     * only "job id" that exists in Millénaire's data. Null when built from a file with no recognizable
     * name (shouldn't happen in practice, but never assume one exists). See {@link JobLabelFormatter}. */
    @Nullable
    public String jobId;

    public TravellerAppearance(ResourceLocation baseTexture) {
        this(baseTexture, 1.0f);
    }

    public TravellerAppearance(ResourceLocation baseTexture, float baseHeight) {
        this.baseTexture = baseTexture;
        this.baseHeight = baseHeight;
    }

    @Nullable
    public ResourceLocation pickRandomClothes(java.util.Random r) {
        if (clothes.isEmpty()) return null;
        return clothes.get(r.nextInt(clothes.size()));
    }
}

