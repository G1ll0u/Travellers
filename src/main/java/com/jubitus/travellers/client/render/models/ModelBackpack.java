package com.jubitus.travellers.client.render.models;

import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.Entity;

// Made with Blockbench, adapted for attachment to ModelBiped body
public class ModelBackpack extends ModelBase {

    private final ModelRenderer root;
    private final ModelRenderer pack;

    public ModelBackpack() {
        this.textureWidth = 32;
        this.textureHeight = 32;

        // Root is at the same origin as ModelBiped body (0,0,0 in torso space)
        root = new ModelRenderer(this);
        root.setRotationPoint(0.0F, 0.0F, 0.0F);

        // One child piece: your backpack cube, rotated 180° like in Blockbench.
        pack = new ModelRenderer(this);
        // Pivot: roughly mid-torso, slightly behind
        pack.setRotationPoint(0.0F, 6.0F, 3.5F);
        setRotationAngle(pack, 0.0F, (float) Math.PI, 0.0F); // faces “south” (back)

        // Your cube: 6 × 11 × 6, UV 0,0
        // (x,y,z offsets only move it relative to the pivot; UV mapping stays the same)
        pack.cubeList.add(new ModelBox(
                pack,
                0, 0,        // u, v
                -3.0F,       // x
                -6.0F,       // y  (so it goes 0..11 in body space)
                -3.0F,       // z
                6,           // width
                11,          // height
                6,           // depth
                0.0F,
                false
        ));

        root.addChild(pack);
    }

    public void setRotationAngle(ModelRenderer modelRenderer, float x, float y, float z) {
        modelRenderer.rotateAngleX = x;
        modelRenderer.rotateAngleY = y;
        modelRenderer.rotateAngleZ = z;
    }

    @Override
    public void render(Entity entity,
                       float limbSwing,
                       float limbSwingAmount,
                       float ageInTicks,
                       float netHeadYaw,
                       float headPitch,
                       float scale) {
        root.render(scale);
    }
}
