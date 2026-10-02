package com.jubitus.travellers.unit.backpack;

import com.jubitus.travellers.unit.render.RenderTraveller;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.util.ResourceLocation;

public interface IBackpackStyle {
    String id();

    boolean isHideable();              // <-- per-style choice

    boolean matches(EntityTraveller e); // optional: culture-based, profession-based, etc.

    ResourceLocation texture(EntityTraveller e);

    void render(RenderTraveller renderer,
                EntityTraveller e,
                float limbSwing, float limbSwingAmount, float ageInTicks,
                float netHeadYaw, float headPitch, float scale);
}
