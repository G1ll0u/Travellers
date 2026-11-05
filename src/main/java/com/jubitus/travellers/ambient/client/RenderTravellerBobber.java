package com.jubitus.travellers.ambient.client;

import com.jubitus.travellers.ambient.EntityTravellerBobber;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Draws the bobber and its line to the traveller's rod the way vanilla draws a player's hook. */
@SideOnly(Side.CLIENT)
public class RenderTravellerBobber extends Render<EntityTravellerBobber> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/particle/particles.png");
    private static final int LINE_SEGMENTS = 16;

    public RenderTravellerBobber(RenderManager manager) {
        super(manager);
    }

    @Override
    public void doRender(EntityTravellerBobber entity, double x, double y, double z, float entityYaw, float partialTicks) {
        EntityLivingBase owner = entity.getOwner();
        if (owner == null || renderOutlines) return;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.getBuffer();

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.enableRescaleNormal();
        GlStateManager.scale(0.5F, 0.5F, 0.5F);
        bindEntityTexture(entity);
        GlStateManager.rotate(180.0F - renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate((renderManager.options.thirdPersonView == 2 ? -1 : 1) * -renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        buf.begin(7, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buf.pos(-0.5D, -0.5D, 0.0D).tex(0.0625D, 0.1875D).normal(0.0F, 1.0F, 0.0F).endVertex();
        buf.pos(0.5D, -0.5D, 0.0D).tex(0.125D, 0.1875D).normal(0.0F, 1.0F, 0.0F).endVertex();
        buf.pos(0.5D, 0.5D, 0.0D).tex(0.125D, 0.125D).normal(0.0F, 1.0F, 0.0F).endVertex();
        buf.pos(-0.5D, 0.5D, 0.0D).tex(0.0625D, 0.125D).normal(0.0F, 1.0F, 0.0F).endVertex();
        tessellator.draw();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();

        Vec3d tip = EntityTravellerBobber.rodTip(owner, partialTicks);
        double bx = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
        double by = entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks + 0.25D;
        double bz = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
        double dx = tip.x - bx;
        double dy = tip.y - by;
        double dz = tip.z - bz;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        buf.begin(3, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i <= LINE_SEGMENTS; i++) {
            float f = i / (float) LINE_SEGMENTS;
            // Same sag curve as vanilla: the line droops toward the bobber.
            buf.pos(x + dx * f, y + dy * (f * f + f) * 0.5D + 0.25D, z + dz * f).color(0, 0, 0, 255).endVertex();
        }
        tessellator.draw();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityTravellerBobber entity) {
        return TEXTURE;
    }
}
