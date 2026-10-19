package com.jubitus.travellers.unit.render;

import com.jubitus.travellers.marker.render.LayerTravellerArmband;
import com.jubitus.travellers.unit.render.models.ModelSimpleHumanoid;
import com.jubitus.travellers.unit.render.textures.LayerTravellerBackpack;
import com.jubitus.travellers.unit.render.textures.LayerTravellerClothes;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.render.baked.BakedSkins;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderBiped;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.client.renderer.entity.layers.LayerCustomHead;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

public class RenderTraveller extends RenderBiped<EntityTraveller> {

    private final LayerBipedArmor armorLayer;
    // Set for the duration of one doRender when this unit is drawn from its baked skin.
    @Nullable
    private ResourceLocation bakedTexture;

    public RenderTraveller(RenderManager renderManagerIn) {
        super(renderManagerIn, new ModelSimpleHumanoid(), 0.5F);

        this.addLayer(new LayerHeldItem(this));

        this.armorLayer = new LayerBipedArmor(this) {
            @Override
            protected void initArmor() {
                this.modelLeggings = new ModelSimpleHumanoid(0.15F, 64, 32);
                this.modelArmor = new ModelSimpleHumanoid(0.55F, 64, 32);
            }

            @Override
            public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                      float ageInTicks, float netHeadYaw, float headPitch, float scale) {
                if (isRenderingBaked()) return;
                super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
            }
        };
        this.addLayer(armorLayer);

        this.addLayer(new LayerCustomHead(((ModelBiped) this.getMainModel()).bipedHead));
        this.addLayer(new LayerTravellerClothes(this));
        this.addLayer(new LayerTravellerBackpack(this));
        this.addLayer(new LayerTravellerArmband(this)); // toujours en dernier
    }

    @Override
    public void doRender(EntityTraveller entity, double x, double y, double z, float entityYaw, float partialTicks) {
        bakedTexture = BakedSkins.textureFor(entity, armorLayer);
        try {
            super.doRender(entity, x, y, z, entityYaw, partialTicks);
        } finally {
            bakedTexture = null;
        }
    }

    /** True while the current unit's clothes, armour and mark are already in its baked skin. */
    public boolean isRenderingBaked() {
        return bakedTexture != null;
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTraveller e) {
        if (bakedTexture != null) return bakedTexture;
        // base nude or "underwear + hair" layer from Millénaire
        return e.getActiveAppearance().baseTexture;
    }

    @Override
    protected void preRenderCallback(EntityTraveller entity, float partialTickTime) {
        float s = entity.getRenderScale();
        GlStateManager.scale(s, s, s);

        // Plain ModelBiped never sets ArmPose.BOW_AND_ARROW on its own - only ModelSkeleton and
        // RenderPlayer do that explicitly in vanilla. Without it a drawn bow still renders (isHandActive
        // drives the item's own pulled/nocked frame independently), but the arms stay in the generic
        // one-handed idle hold instead of the real two-handed draw stance.
        ModelBiped model = (ModelBiped) this.getMainModel();
        model.rightArmPose = ModelBiped.ArmPose.EMPTY;
        model.leftArmPose = ModelBiped.ArmPose.EMPTY;
        if (entity.isAimingBow()) {
            // Mirrors ModelSkeleton.setLivingAnimations: only the primary hand gets the pose, the
            // other arm's rotation is derived from it inside ModelBiped.setRotationAngles.
            if (entity.getPrimaryHand() == net.minecraft.util.EnumHandSide.RIGHT) {
                model.rightArmPose = ModelBiped.ArmPose.BOW_AND_ARROW;
            } else {
                model.leftArmPose = ModelBiped.ArmPose.BOW_AND_ARROW;
            }
        }
    }

    @Override
    protected void applyRotations(EntityTraveller entity, float ageInTicks, float rotationYaw, float partialTicks) {
        if (entity.isEntityAlive() && entity.sleep.isSleeping()) {
            GlStateManager.rotate(entity.sleep.getBedOrientationInDegrees(), 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(this.getDeathMaxRotation(entity), 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(270.0F, 0.0F, 1.0F, 0.0F);
        } else if (entity.isElytraFlying()) {
            // Same flight pose vanilla gives a gliding player (RenderPlayer.applyRotations): tip into
            // the dive over the first second of flight, then bank by the angle between where they are
            // actually moving and where they are pointing - which is what makes a turn read as a lean.
            super.applyRotations(entity, ageInTicks, rotationYaw, partialTicks);

            float flyingTicks = (float) entity.getTicksElytraFlying() + partialTicks;
            float tipIn = MathHelper.clamp(flyingTicks * flyingTicks / 100.0F, 0.0F, 1.0F);
            GlStateManager.rotate(tipIn * (-90.0F - entity.rotationPitch), 1.0F, 0.0F, 0.0F);

            // The lean itself is smoothed on the entity (EntityTraveller.tickElytraBank), not computed
            // from raw motion here: at low speed that raw angle is noise and makes the model snap about.
            GlStateManager.rotate(entity.getElytraBank(partialTicks), 0.0F, 1.0F, 0.0F);
        } else {
            super.applyRotations(entity, ageInTicks, rotationYaw, partialTicks);
        }
    }

    /** Shifts the render back toward the bed's foot half, mirroring RenderPlayer's sleep handling. */
    @Override
    protected void renderLivingAt(EntityTraveller entity, double x, double y, double z) {
        if (entity.isEntityAlive() && entity.sleep.isSleeping()) {
            Vec3d offset = entity.sleep.getRenderOffset();
            super.renderLivingAt(entity, x + offset.x, y + offset.y, z + offset.z);
        } else {
            super.renderLivingAt(entity, x, y, z);
        }
    }
}
