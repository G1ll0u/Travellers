package com.jubitus.travellers.client.render;

import com.jubitus.travellers.client.render.marker.LayerTravellerArmband;
import com.jubitus.travellers.client.render.models.ModelSimpleHumanoid;
import com.jubitus.travellers.client.render.textures.LayerTravellerBackpack;
import com.jubitus.travellers.client.render.textures.LayerTravellerClothes;
import com.jubitus.travellers.entity.culture.CultureDef;
import com.jubitus.travellers.entity.culture.TravellerAppearance;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;

public class RenderTraveller extends RenderBiped<EntityTraveller> {

    public RenderTraveller(RenderManager renderManagerIn) {
        super(renderManagerIn, new ModelSimpleHumanoid(), 0.5F);

        this.addLayer(new LayerHeldItem(this));

        this.addLayer(new LayerBipedArmor(this) {
            @Override
            protected void initArmor() {
                this.modelLeggings = new ModelSimpleHumanoid(0.15F, 64, 32);
                this.modelArmor = new ModelSimpleHumanoid(0.55F, 64, 32);
            }
        });

        this.addLayer(new LayerCustomHead(((ModelBiped) this.getMainModel()).bipedHead));
        this.addLayer(new LayerTravellerClothes(this));
        this.addLayer(new LayerTravellerBackpack(this));
        this.addLayer(new LayerTravellerArmband(this)); // toujours en dernier
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTraveller e) {
        // base nude or "underwear + hair" layer from Millénaire
        return e.getActiveAppearance().baseTexture;
    }

    @Override
    protected void preRenderCallback(EntityTraveller entity, float partialTickTime) {
        float s = entity.getRenderScale();
        GlStateManager.scale(s, s, s);
    }

    @Override
    protected void applyRotations(EntityTraveller entity, float ageInTicks, float rotationYaw, float partialTicks) {
        if (entity.isEntityAlive() && entity.isSleepingInBed()) {
            GlStateManager.rotate(entity.getBedOrientationInDegrees(), 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(this.getDeathMaxRotation(entity), 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(270.0F, 0.0F, 1.0F, 0.0F);
        } else {
            super.applyRotations(entity, ageInTicks, rotationYaw, partialTicks);
        }
    }

    /** Shifts the render back toward the bed's foot half, mirroring RenderPlayer's sleep handling. */
    @Override
    protected void renderLivingAt(EntityTraveller entity, double x, double y, double z) {
        if (entity.isEntityAlive() && entity.isSleepingInBed()) {
            Vec3d offset = entity.getSleepRenderOffset();
            super.renderLivingAt(entity, x + offset.x, y + offset.y, z + offset.z);
        } else {
            super.renderLivingAt(entity, x, y, z);
        }
    }
}
