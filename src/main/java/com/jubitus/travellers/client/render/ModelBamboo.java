package com.jubitus.travellers.client.render;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;

public class ModelBamboo extends ModelBase {
    private final ModelRenderer carryingstick;
    private final ModelRenderer cube_r1;
    private final ModelRenderer cube_r2;
    private final ModelRenderer cube_r3;
    private final ModelRenderer cube_r4;

    public ModelBamboo() {
        textureWidth = 128;
        textureHeight = 128;

        carryingstick = new ModelRenderer(this);
        carryingstick.setRotationPoint(-3.0F, 2.0F, 10.0F);
        carryingstick.cubeList.add(new ModelBox(carryingstick, 0, 0, -4.0F, -5.0F, -26.0F, 3, 3, 32, 0.0F, false));
        carryingstick.cubeList.add(new ModelBox(carryingstick, 32, 35, -6.0F, 3.0F, -1.0F, 7, 7, 7, 0.0F, false));
        carryingstick.cubeList.add(new ModelBox(carryingstick, 32, 49, -6.0F, 3.0F, -26.0F, 7, 7, 7, 0.0F, false));

        cube_r1 = new ModelRenderer(this);
        cube_r1.setRotationPoint(-2.5F, 0.5F, -22.5F);
        carryingstick.addChild(cube_r1);
        setRotationAngle(cube_r1, 0.0F, 0.7854F, 0.0F);
        cube_r1.cubeList.add(new ModelBox(cube_r1, 70, 14, 0.0F, -2.5F, -4.5F, 0, 5, 9, 0.0F, false));

        cube_r2 = new ModelRenderer(this);
        cube_r2.setRotationPoint(-2.5F, 0.5F, -22.5F);
        carryingstick.addChild(cube_r2);
        setRotationAngle(cube_r2, 0.0F, -0.7854F, 0.0F);
        cube_r2.cubeList.add(new ModelBox(cube_r2, 70, 0, 0.0F, -2.5F, -4.5F, 0, 5, 9, 0.0F, false));

        cube_r3 = new ModelRenderer(this);
        cube_r3.setRotationPoint(-2.5F, 0.5F, 2.5F);
        carryingstick.addChild(cube_r3);
        setRotationAngle(cube_r3, 0.0F, 0.7854F, 0.0F);
        cube_r3.cubeList.add(new ModelBox(cube_r3, 56, 67, 0.0F, -2.5F, -4.5F, 0, 5, 9, 0.0F, false));

        cube_r4 = new ModelRenderer(this);
        cube_r4.setRotationPoint(-2.5F, 0.5F, 2.5F);
        carryingstick.addChild(cube_r4);
        setRotationAngle(cube_r4, 0.0F, -0.7854F, 0.0F);
        cube_r4.cubeList.add(new ModelBox(cube_r4, 0, 67, 0.0F, -2.5F, -4.5F, 0, 5, 9, 0.0F, false));
    }

    public void setRotationAngle(ModelRenderer modelRenderer, float x, float y, float z) {
        modelRenderer.rotateAngleX = x;
        modelRenderer.rotateAngleY = y;
        modelRenderer.rotateAngleZ = z;
    }

    @Override
    public void render(Entity entity, float f, float f1, float f2, float f3, float f4, float f5) {
        carryingstick.render(f5);
    }
}