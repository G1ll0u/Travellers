package com.jubitus.travellers.client.gui.hub;

import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/**
 * Draws a live head-and-shoulders portrait of a traveller inside a GUI, with the head turning
 * towards the mouse cursor while the body stays still.
 *
 * <p>The entity itself is rendered — not a copy and not a flat skin sample — so armour, held
 * items and every render layer (clothes, backpack, armband) come along for free.
 *
 * <p>Three things this has to get right:
 * <ul>
 *   <li><b>Only the head moves.</b> {@code RenderLivingBase} derives the head's own rotation from
 *       {@code rotationYawHead - renderYawOffset}. Pinning the body angles and moving only the head
 *       angles turns the head alone; rotating the whole GL matrix (what vanilla's
 *       {@code GuiInventory.drawEntityOnScreen} does) would tilt the entire body instead.</li>
 *   <li><b>Same framing for everyone.</b> Individuals are rolled at different {@code getRenderScale()},
 *       which {@code RenderTraveller.preRenderCallback} applies as a GL scale. Dividing it back out
 *       here makes the on-screen pixel size independent of it, so a short worker's head is drawn
 *       exactly as large as a tall one's.</li>
 *   <li><b>Nothing spills out.</b> A scissor box clips the body to the portrait frame. Scissor
 *       coordinates are real window pixels with a bottom-left origin, so they need the GUI scale
 *       factor and a flipped Y — getting that wrong is the classic "it works at one resolution" bug.</li>
 * </ul>
 *
 * <p>All entity rotation fields are saved and restored around the render, so the entity keeps
 * behaving normally in the world while the menu is open (the game is not paused).
 */
@SideOnly(Side.CLIENT)
public final class WorkerPortraitRenderer {

    /** Eye height in model space. The biped spans ~2.01 blocks; eyes sit just under the head's middle. */
    private static final float EYE_HEIGHT_MODEL = 1.72F;

    /** Rendered pixels per model block, relative to the portrait size. Sets how tight the framing is. */
    private static final float ZOOM_FACTOR = 0.9F;

    /** Body angle: 0 = facing the player straight on. */
    private static final float BODY_YAW = 0.0F;

    /**
     * True while a portrait is being drawn. {@code RenderLodHelper} honours this so cosmetic layers
     * are never culled in a portrait — otherwise a worker that wanders off while the menu is open
     * would silently lose its clothes and backpack in the frame.
     */
    private static boolean renderingPortrait = false;

    /**
     * Last rendered angles, eased towards the target every draw call so the head catches up to the
     * cursor gradually instead of snapping to it every frame. Shared static state is fine — only one
     * portrait is ever on screen at a time; switching to a different worker's hub just re-settles it.
     */
    private static float smoothedYaw = 0.0F;
    private static float smoothedPitch = 0.0F;

    private WorkerPortraitRenderer() {}

    public static boolean isRenderingPortrait() {
        return renderingPortrait;
    }

    /**
     * @param x    left edge of the portrait frame
     * @param y    top edge of the portrait frame
     * @param size width and height of the portrait frame
     */
    public static void draw(EntityTraveller entity, int x, int y, int size, int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(x, y, size, size);

        if (entity == null || entity.isDead) return;

        int centerX = x + size / 2;
        int centerY = y + size / 2;

        float scale = entity.getRenderScale();
        if (scale <= 0.0F) scale = 1.0F;
        float pixelsPerBlock = size * ZOOM_FACTOR;
        // preRenderCallback multiplies by getRenderScale() again, so divide it out here.
        float glScale = pixelsPerBlock / scale;

        float targetYaw = 0.0F;
        float targetPitch = 0.0F;
        if (TravellersConfig.WORKER_HUB.headFollowsCursor) {
            // Yaw is mirrored: the GL transform below scales X by -1 (vanilla's own portrait
            // transform), so a positive rotation on the entity reads as a turn to the left here.
            targetYaw = angleTowards(centerX - mouseX, TravellersConfig.WORKER_HUB.headMaxYaw);
            targetPitch = pitchTowards(centerY, mouseY, TravellersConfig.WORKER_HUB.headMaxPitch);
        }

        float smoothing = (float) TravellersConfig.WORKER_HUB.headSmoothing;
        smoothedYaw += (targetYaw - smoothedYaw) * smoothing;
        smoothedPitch += (targetPitch - smoothedPitch) * smoothing;

        // The portrait frame has a 1px border; clip inside it.
        beginClip(x + 1, y + 1, size - 2, size - 2);
        renderEntity(entity, centerX, centerY, glScale, pixelsPerBlock, smoothedYaw, smoothedPitch);
        endClip();
    }

    /** Saturating angle: precise near the portrait, clamped to a plausible neck rotation far from it. */
    private static float angleTowards(int delta, int maxDegrees) {
        float raw = (float) Math.toDegrees(Math.atan(delta / 60.0D)) * 1.5F;
        return Math.max(-maxDegrees, Math.min(maxDegrees, raw));
    }

    /**
     * Linear over the real screen span above/below the portrait, so max pitch needs the cursor at
     * the actual top/bottom edge of the screen — not just a fixed pixel offset above the head, which
     * read as wildly over-sensitive on anything but a very tall window.
     */
    private static float pitchTowards(int centerY, int mouseY, int maxDegrees) {
        int delta = mouseY - centerY;
        int scaledHeight = new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight();
        int span = Math.max(1, delta >= 0 ? scaledHeight - centerY : centerY);
        float frac = Math.max(-1.0F, Math.min(1.0F, delta / (float) span));
        return frac * maxDegrees;
    }

    private static void renderEntity(EntityTraveller entity, int centerX, int centerY,
                                     float glScale, float pixelsPerBlock,
                                     float headYaw, float headPitch) {
        float oldRenderYawOffset = entity.renderYawOffset;
        float oldPrevRenderYawOffset = entity.prevRenderYawOffset;
        float oldRotationYaw = entity.rotationYaw;
        float oldPrevRotationYaw = entity.prevRotationYaw;
        float oldRotationPitch = entity.rotationPitch;
        float oldPrevRotationPitch = entity.prevRotationPitch;
        float oldRotationYawHead = entity.rotationYawHead;
        float oldPrevRotationYawHead = entity.prevRotationYawHead;

        // Body pinned, head free. prev* must match current* or the renderer interpolates between
        // the world pose and the portrait pose and the head jitters every frame.
        entity.renderYawOffset = entity.prevRenderYawOffset = BODY_YAW;
        entity.rotationYaw = entity.prevRotationYaw = BODY_YAW;
        entity.rotationYawHead = entity.prevRotationYawHead = BODY_YAW + headYaw;
        entity.rotationPitch = entity.prevRotationPitch = headPitch;

        RenderManager rm = Minecraft.getMinecraft().getRenderManager();
        float oldViewY = rm.playerViewY;
        boolean oldShadow = rm.isRenderShadow();

        renderingPortrait = true;
        // Gui.drawRect leaves the GL colour set to whatever it just filled and never restores it,
        // so the portrait frame drawn a moment ago would tint the entity's own texture. Armour
        // escapes it because LayerArmorBase sets its own colour, which is why only the skin looked
        // wrong. Reset it before anything is drawn.
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableColorMaterial();
        GlStateManager.pushMatrix();
        // Vanilla's GuiInventory transform, with the origin moved so the eyes land on the frame center.
        GlStateManager.translate((float) centerX, centerY + EYE_HEIGHT_MODEL * pixelsPerBlock, 50.0F);
        GlStateManager.scale(-glScale, glScale, glScale);
        GlStateManager.rotate(180.0F, 0.0F, 0.0F, 1.0F);

        GlStateManager.rotate(135.0F, 0.0F, 1.0F, 0.0F);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135.0F, 0.0F, 1.0F, 0.0F);

        rm.setPlayerViewY(180.0F);
        rm.setRenderShadow(false);
        try {
            rm.renderEntity(entity, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F, false);
        } catch (Exception ignored) {
            // A broken render layer must never take the whole menu down with it.
        }
        rm.setRenderShadow(oldShadow);
        rm.setPlayerViewY(oldViewY);

        GlStateManager.popMatrix();
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        renderingPortrait = false;

        entity.renderYawOffset = oldRenderYawOffset;
        entity.prevRenderYawOffset = oldPrevRenderYawOffset;
        entity.rotationYaw = oldRotationYaw;
        entity.prevRotationYaw = oldPrevRotationYaw;
        entity.rotationPitch = oldRotationPitch;
        entity.prevRotationPitch = oldPrevRotationPitch;
        entity.rotationYawHead = oldRotationYawHead;
        entity.prevRotationYawHead = oldPrevRotationYawHead;
    }

    /** Clips to a GUI-space rectangle. Scissor is in window pixels, bottom-left origin. */
    private static void beginClip(int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution res = new ScaledResolution(mc);
        int f = res.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, (res.getScaledHeight() - (y + h)) * f, w * f, h * f);
    }

    private static void endClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
