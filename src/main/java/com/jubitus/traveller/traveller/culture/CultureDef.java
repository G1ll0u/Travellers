package com.jubitus.traveller.traveller.culture;

import com.jubitus.traveller.Reference;
import net.minecraft.util.SoundEvent;

public final class CultureDef {
    public final String id;             // "en", "fr", ... (stable, saved to NBT)
    public final String displayName;
    public final int travellerTextureCount;
    public final int raiderTextureCount;

    public CultureDef(String id, String displayName, int travTex, int raidTex) {
        this.id = id;
        this.displayName = displayName;
        this.travellerTextureCount = Math.max(1, travTex);
        this.raiderTextureCount   = Math.max(1, raidTex);
    }

    public net.minecraft.util.ResourceLocation travellerTexture(int index) {
        int i = Math.max(0, Math.min(index, travellerTextureCount - 1));
        return new net.minecraft.util.ResourceLocation(
                Reference.MOD_ID, String.format("textures/entity/%s/traveller_%d.png", id, i));
    }

    public net.minecraft.util.ResourceLocation raiderTexture(int index) {
        int i = Math.max(0, Math.min(index, raiderTextureCount - 1));
        return new net.minecraft.util.ResourceLocation(
                Reference.MOD_ID, String.format("textures/entity/%s/raider_%d.png", id, i));
    }
}
