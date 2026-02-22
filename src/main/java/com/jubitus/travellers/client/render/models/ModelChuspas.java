package com.jubitus.travellers.client.render.models;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;

public class ModelChuspas extends ModelBase {
    private final ModelRenderer chuspa;

    public ModelChuspas() {
        textureWidth = 64;
        textureHeight = 64;

        chuspa = new ModelRenderer(this);
        chuspa.setRotationPoint(-5.0F, 15.0F, -2.0F);
        chuspa.cubeList.add(new ModelBox(chuspa, 48, 34, -1.0F, -6.0F, -1.0F, 2, 6, 6, 0.0F, false));
        chuspa.cubeList.add(new ModelBox(chuspa, 0, 0, -1.0F, -16.0F, -1.0F, 12, 12, 6, 0.0F, false));
    }

    @Override
    public void render(Entity entity, float f, float f1, float f2, float f3, float f4, float f5) {
        chuspa.render(f5);
    }

    public void setRotationAngle(ModelRenderer modelRenderer, float x, float y, float z) {
        modelRenderer.rotateAngleX = x;
        modelRenderer.rotateAngleY = y;
        modelRenderer.rotateAngleZ = z;
    }
}