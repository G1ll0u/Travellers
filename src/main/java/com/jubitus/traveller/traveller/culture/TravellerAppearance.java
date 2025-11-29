package com.jubitus.traveller.traveller.culture;

import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;

public final class TravellerAppearance {
    public final ResourceLocation baseTexture;
    public final java.util.List<ResourceLocation> clothes = new java.util.ArrayList<>();

    public TravellerAppearance(ResourceLocation baseTexture) {
        this.baseTexture = baseTexture;
    }

    @Nullable
    public ResourceLocation pickRandomClothes(java.util.Random r) {
        if (clothes.isEmpty()) return null;
        return clothes.get(r.nextInt(clothes.size()));
    }
}

