package com.jubitus.travellers.client.render.marker;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;

import net.minecraft.client.model.ModelBiped;

public class ModelTravellerArmband extends ModelBiped {

    public ModelTravellerArmband() {
        // 0.75F gives enough physical separation from the body mesh (0.0F) to avoid
        // z-fighting without looking puffy. Vanilla outer armor uses 1.0F.
        super(0.75F, 0.0F, 64, 32);
    }
}