package com.jubitus.travellers.client.gui.mark;
import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.render.marker.WorkerMarkStyle;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.marker.MsgSaveWorkerOwnerVisuals;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.IOException;

public class GuiWorkerMarkEditor extends GuiScreen implements ITravellersGui {

    private final GuiScreen parent;
    private final EntityPlayer player;
    private final EntityTravellerWorker worker;

    private int guiLeft;
    private int guiTop;
    private final int xSize = 252;
    private final int ySize = 180;

    private GuiButton prevStyleButton;
    private GuiButton nextStyleButton;
    private GuiButton saveButton;
    private GuiButton cancelButton;

    private WorkerMarkStyle[] styles;
    private int styleIndex;

    private float hue;
    private float saturation;
    private float value;

    private boolean draggingSV;
    private boolean draggingHue;

    private static final int SV_X = 12;
    private static final int SV_Y = 28;
    private static final int SV_W = 100;
    private static final int SV_H = 100;

    private static final int HUE_X = 118;
    private static final int HUE_Y = 28;
    private static final int HUE_W = 12;
    private static final int HUE_H = 100;

    public GuiWorkerMarkEditor(GuiScreen parent, EntityPlayer player, EntityTravellerWorker worker) {
        this.parent = parent;
        this.player = player;
        this.worker = worker;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;

        this.styles = WorkerMarkStyle.values();
        WorkerMarkStyle currentStyle = WorkerMarkStyle.byId(worker.getOwnerMarkStyleId());
        this.styleIndex = 0;
        for (int i = 0; i < styles.length; i++) {
            if (styles[i] == currentStyle) {
                this.styleIndex = i;
                break;
            }
        }

        int rgb = worker.getOwnerMarkColor();
        float[] hsv = java.awt.Color.RGBtoHSB(
                (rgb >> 16) & 0xFF,
                (rgb >> 8) & 0xFF,
                rgb & 0xFF,
                null
        );
        this.hue = hsv[0];
        this.saturation = hsv[1];
        this.value = hsv[2];

        this.buttonList.clear();
        this.prevStyleButton = this.addButton(new GuiTravellersButton(0, guiLeft + 142, guiTop + 30, 20, 20, "<"));
        this.nextStyleButton = this.addButton(new GuiTravellersButton(1, guiLeft + 218, guiTop + 30, 20, 20, ">"));
        this.cancelButton = this.addButton(new GuiTravellersButton(3, guiLeft + 142, guiTop + 144, 50, 20, I18n.format("gui.cancel")));
        this.saveButton = this.addButton(new GuiTravellersButton(2, guiLeft + 196, guiTop + 144, 50, 20, I18n.format("gui.done")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == prevStyleButton) {
            styleIndex = (styleIndex - 1 + styles.length) % styles.length;
            return;
        }
        if (button == nextStyleButton) {
            styleIndex = (styleIndex + 1) % styles.length;
            return;
        }
        if (button == saveButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgSaveWorkerOwnerVisuals(getCurrentColor(), getCurrentStyle().id)
            );
            mc.displayGuiScreen(parent);
            return;
        }
        if (button == cancelButton) {
            mc.displayGuiScreen(parent);
            return;
        }

        super.actionPerformed(button);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        if (draggingSV || draggingHue) {
            updatePickerFromMouse();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();

        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }

        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;

        if (isPointInRect(guiLeft + 162, guiTop + 30, 56, 20, mouseX, mouseY)) {
            if (wheel > 0) {
                styleIndex = (styleIndex + 1) % styles.length;
            } else {
                styleIndex = (styleIndex - 1 + styles.length) % styles.length;
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0) {
            if (isPointInRect(guiLeft + SV_X, guiTop + SV_Y, SV_W, SV_H, mouseX, mouseY)) {
                draggingSV = true;
                updatePickerFromMouse();
            } else if (isPointInRect(guiLeft + HUE_X, guiTop + HUE_Y, HUE_W, HUE_H, mouseX, mouseY)) {
                draggingHue = true;
                updatePickerFromMouse();
            }
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        draggingSV = false;
        draggingHue = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void updatePickerFromMouse() {
        int mx = Mouse.getX() * this.width / this.mc.displayWidth;
        int my = this.height - Mouse.getY() * this.height / this.mc.displayHeight - 1;

        if (draggingSV) {
            float sx = clamp((mx - (guiLeft + SV_X)) / (float) SV_W, 0.0F, 1.0F);
            float sy = clamp((my - (guiTop + SV_Y)) / (float) SV_H, 0.0F, 1.0F);
            this.saturation = sx;
            this.value = 1.0F - sy;
        }

        if (draggingHue) {
            float hy = clamp((my - (guiTop + HUE_Y)) / (float) HUE_H, 0.0F, 1.0F);
            this.hue = hy;
        }
    }

    private WorkerMarkStyle getCurrentStyle() {
        return styles[styleIndex];
    }

    private int getCurrentColor() {
        return java.awt.Color.HSBtoRGB(hue, saturation, value) & 0xFFFFFF;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        TravellersGuiTheme.drawPanel(guiLeft, guiTop, xSize, ySize);

        TravellersGuiTheme.drawTitle(fontRenderer, I18n.format("travellers.worker.mark.title"), guiLeft + 12, guiTop + 10);

        drawSVBox();
        drawHueBar();
        drawPreview();

        this.drawString(fontRenderer, I18n.format("travellers.worker.mark.style"), guiLeft + 142, guiTop + 18, TravellersGuiTheme.COL_TEXT);
        this.drawCenteredString(fontRenderer, I18n.format(getCurrentStyle().i18nKey), guiLeft + 190, guiTop + 37, TravellersGuiTheme.COL_TEXT);

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (isPointInRect(guiLeft + 142, guiTop + 90, 66, 20, mouseX, mouseY)) {
            drawHoveringText(java.util.Collections.singletonList(I18n.format("travellers.worker.mark.preview.leader_hint")), mouseX, mouseY);
        }
        com.jubitus.travellers.client.notify.NotificationOverlayRenderer.draw(new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()));
    }

    private void drawSVBox() {
        int pureHue = java.awt.Color.HSBtoRGB(hue, 1.0F, 1.0F) | 0xFF000000;

        for (int x = 0; x < SV_W; x++) {
            float s = x / (float) (SV_W - 1);
            int top = mixColor(0xFFFFFFFF, pureHue, s);
            int bottom = mixColor(0xFF000000, 0xFF000000, s);
            drawGradientRect(guiLeft + SV_X + x, guiTop + SV_Y, guiLeft + SV_X + x + 1, guiTop + SV_Y + SV_H, top, bottom);
        }

        int cx = guiLeft + SV_X + (int) (saturation * SV_W);
        int cy = guiTop + SV_Y + (int) ((1.0F - value) * SV_H);
        drawRect(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF000000);
        drawRect(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
    }

    private void drawHueBar() {
        for (int y = 0; y < HUE_H; y++) {
            float h = y / (float) (HUE_H - 1);
            int c = java.awt.Color.HSBtoRGB(h, 1.0F, 1.0F) | 0xFF000000;
            drawRect(guiLeft + HUE_X, guiTop + HUE_Y + y, guiLeft + HUE_X + HUE_W, guiTop + HUE_Y + y + 1, c);
        }

        int hy = guiTop + HUE_Y + (int) (hue * HUE_H);
        drawRect(guiLeft + HUE_X - 1, hy - 1, guiLeft + HUE_X + HUE_W + 1, hy + 1, 0xFFFFFFFF);
    }

    private void drawPreview() {
        int color = getCurrentColor();
        int argb = 0xFF000000 | color;

        drawString(fontRenderer, I18n.format("travellers.worker.mark.preview"), guiLeft + 142, guiTop + 96, TravellersGuiTheme.COL_TEXT);

        TravellersGuiTheme.drawInnerPanel(guiLeft + 142, guiTop + 110, 30, 20);
        drawMarkTexture(getCurrentStyle().followerTexture, guiLeft + 143, guiTop + 111, 28, 18, argb);

        TravellersGuiTheme.drawInnerPanel(guiLeft + 178, guiTop + 110, 30, 20);
        drawMarkTexture(getCurrentStyle().leaderTexture, guiLeft + 179, guiTop + 111, 28, 18, argb);

        drawCenteredString(fontRenderer, I18n.format("travellers.worker.mark.preview.follower"), guiLeft + 157, guiTop + 132, TravellersGuiTheme.COL_TEXT_DIM);
        drawCenteredString(fontRenderer, I18n.format("travellers.worker.mark.preview.leader"), guiLeft + 193, guiTop + 132, TravellersGuiTheme.COL_TEXT_DIM);
    }

    private void drawMarkTexture(ResourceLocation texture, int x, int y, int w, int h, int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;

        mc.getTextureManager().bindTexture(texture);
        net.minecraft.client.renderer.GlStateManager.color(r, g, b, 1.0F);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, w, h, w, h);
        net.minecraft.client.renderer.GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int mixColor(int a, int b, float t) {
        int aA = (a >> 24) & 0xFF;
        int aR = (a >> 16) & 0xFF;
        int aG = (a >> 8) & 0xFF;
        int aB = a & 0xFF;

        int bA = (b >> 24) & 0xFF;
        int bR = (b >> 16) & 0xFF;
        int bG = (b >> 8) & 0xFF;
        int bB = b & 0xFF;

        int rA = (int) (aA + (bA - aA) * t);
        int rR = (int) (aR + (bR - aR) * t);
        int rG = (int) (aG + (bG - aG) * t);
        int rB = (int) (aB + (bB - aB) * t);

        return (rA << 24) | (rR << 16) | (rG << 8) | rB;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static boolean isPointInRect(int x, int y, int w, int h, int mx, int my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}