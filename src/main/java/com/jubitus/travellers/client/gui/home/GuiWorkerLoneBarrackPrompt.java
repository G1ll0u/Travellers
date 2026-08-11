package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.formation.GuiWorkerFormationSlider;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgCreateLoneBarrack;
import com.jubitus.travellers.server.net.message.home.MsgOpenLoneBarrackPrompt;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerLoneBarrackPrompt extends GuiScreen implements ITravellersGui {

    private final EntityPlayer player;
    private final BlockPos center;
    private final List<MsgOpenLoneBarrackPrompt.ExistingBarrack> existingBarracks;

    private int mapX, mapY, mapW, mapH;
    private int panelX, panelY, panelW, panelH;

    private WorldMapController mapController;
    private GuiTextField nameField;

    private GuiWorkerFormationSlider halfWidthSlider;
    private GuiWorkerFormationSlider halfDepthSlider;
    private GuiWorkerFormationSlider halfHeightSlider;

    private GuiButton validateBtn, cancelBtn;
    private int validateBtnY;

    private String errorMessage = null;

    // Block scan cache — updated only when slider values change
    private int chestsFound = -1;
    private int bedsFound = -1;
    private int lastScannedHw = -1;
    private int lastScannedHd = -1;
    private int lastScannedHh = -1;

    public GuiWorkerLoneBarrackPrompt(EntityPlayer player, BlockPos center) {
        this(player, center, Collections.emptyList());
    }

    public GuiWorkerLoneBarrackPrompt(EntityPlayer player, BlockPos center,
                                      List<MsgOpenLoneBarrackPrompt.ExistingBarrack> existingBarracks) {
        this.player = player;
        this.center = center;
        this.existingBarracks = existingBarracks != null ? existingBarracks : Collections.emptyList();
    }

    @Override
    public void initGui() {
        super.initGui();
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        mapW = 200;
        mapH = 200;
        panelW = 160;
        panelH = 230;

        int totalW = mapW + 10 + panelW;
        int startX = (this.width - totalW) / 2;
        mapX = startX;
        mapY = (this.height - mapH) / 2;
        panelX = startX + mapW + 10;
        panelY = mapY;

        this.buttonList.clear();

        int bx = panelX + 4;
        int bw = panelW - 8;
        int bh = 16;

        // Name field
        nameField = new GuiTextField(0, this.fontRenderer, bx, panelY + 28, bw, 14);
        nameField.setMaxStringLength(32);
        nameField.setFocused(true);

        // Sliders — each takes 24px (label 12 + bar 12)
        int defaultHalfWidth  = clamp(TravellersConfig.WORKER.workerLoneBarrackHalfWidth,  4, 32);
        int defaultHalfDepth  = clamp(TravellersConfig.WORKER.workerLoneBarrackHalfDepth,  4, 32);
        int defaultHalfHeight = clamp(TravellersConfig.WORKER.workerLoneBarrackHalfHeight, 2, 64);

        int sliderRow1 = panelY + 60;
        int sliderRow2 = sliderRow1 + 30;
        int sliderRow3 = sliderRow2 + 30;

        halfWidthSlider = new GuiWorkerFormationSlider(
            bx, sliderRow1, bw, 20,
            "travellers.lone_barrack.prompt.half_width",
            4, 16, 1, defaultHalfWidth,
            v -> String.valueOf((int) v));

        halfDepthSlider = new GuiWorkerFormationSlider(
            bx, sliderRow2, bw, 20,
            "travellers.lone_barrack.prompt.half_depth",
            4, 16, 1, defaultHalfDepth,
            v -> String.valueOf((int) v));

        halfHeightSlider = new GuiWorkerFormationSlider(
            bx, sliderRow3, bw, 20,
            "travellers.lone_barrack.prompt.half_height",
            2, 32, 1, defaultHalfHeight,
            v -> String.valueOf((int) v));

        // Validate / cancel
        validateBtnY = panelY + panelH - 46;
        validateBtn = addButton(new GuiButton(1, bx, validateBtnY, bw, bh,
            I18n.format("travellers.lone_barrack.prompt.validate")));
        cancelBtn = addButton(new GuiButton(2, bx, validateBtnY + bh + 4, bw, bh,
            I18n.format("travellers.gui.cancel")));

        mapController = new WorldMapController(
            Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
            "lone_barrack_prompt_map", center);
        mapController.panel.addMarker(WorldMapMarkerFactory.player(center, player.getName(), 0xFFFFFF, player.rotationYaw));

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        org.lwjgl.input.Keyboard.enableRepeatEvents(false);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        nameField.updateCursorCounter();
        validateBtn.enabled = !nameField.getText().trim().isEmpty();
        if (halfWidthSlider != null && halfDepthSlider != null && halfHeightSlider != null) {
            int hw = (int) halfWidthSlider.getValue();
            int hd = (int) halfDepthSlider.getValue();
            int hh = (int) halfHeightSlider.getValue();
            if (hw != lastScannedHw || hd != lastScannedHd || hh != lastScannedHh) {
                rescanBlocks(hw, hd, hh);
                lastScannedHw = hw;
                lastScannedHd = hd;
                lastScannedHh = hh;
            }
        }
    }

    private void rescanBlocks(int hw, int hd, int hh) {
        if (mc.world == null) return;
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int chests = 0, beds = 0;
        for (int x = cx - hw; x <= cx + hw; x++) {
            for (int y = cy - hh; y <= cy + hh; y++) {
                for (int z = cz - hd; z <= cz + hd; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    IBlockState st = mc.world.getBlockState(p);
                    Block b = st.getBlock();
                    if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                        chests++;
                    } else if (b instanceof BlockBed) {
                        try {
                            if (st.getValue(BlockBed.PART) == BlockBed.EnumPartType.HEAD) beds++;
                        } catch (Throwable ignored) {
                            beds++;
                        }
                    }
                }
            }
        }
        chestsFound = chests;
        bedsFound = beds;
    }

    public void setError(String message) {
        this.errorMessage = message;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == cancelBtn) {
            mc.player.closeScreen();
            return;
        }
        if (button == validateBtn) {
            String name = nameField.getText().trim();
            if (name.isEmpty()) return;
            errorMessage = null;
            int hw = (int) halfWidthSlider.getValue();
            int hd = (int) halfDepthSlider.getValue();
            int hh = (int) halfHeightSlider.getValue();
            TravellerFollowNet.CHANNEL.sendToServer(new MsgCreateLoneBarrack(name, center, hw, hd, hh));
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null) mapController.onMouseClick(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
        if (halfWidthSlider != null)  halfWidthSlider.mousePressed(mouseX, mouseY, mouseButton);
        if (halfDepthSlider != null)  halfDepthSlider.mousePressed(mouseX, mouseY, mouseButton);
        if (halfHeightSlider != null) halfHeightSlider.mousePressed(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapController != null) mapController.onMouseDrag(mouseX, mouseY);
        if (halfWidthSlider != null)  halfWidthSlider.mouseDragged(mouseX, mouseY);
        if (halfDepthSlider != null)  halfDepthSlider.mouseDragged(mouseX, mouseY);
        if (halfHeightSlider != null) halfHeightSlider.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mapController != null) mapController.onMouseRelease(state);
        if (halfWidthSlider != null)  halfWidthSlider.mouseReleased(state);
        if (halfDepthSlider != null)  halfDepthSlider.mouseReleased(state);
        if (halfHeightSlider != null) halfHeightSlider.mouseReleased(state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel != 0 && mapController != null && mapController.isMouseOver(mouseX, mouseY)) {
            mapController.handleWheel(mouseX, mouseY, wheel);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            mc.player.closeScreen();
            return;
        }
        if ((keyCode == org.lwjgl.input.Keyboard.KEY_RETURN
                || keyCode == org.lwjgl.input.Keyboard.KEY_NUMPADENTER)
                && validateBtn.enabled) {
            actionPerformed(validateBtn);
            return;
        }
        if (nameField.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // Draw map
        if (mapController != null) {
            TravellersGuiTheme.drawMapFrame(mapX, mapY, mapW, mapH);
            mapController.panel.clearMarkers();
            mapController.panel.addMarker(WorldMapMarkerFactory.player(center, player.getName(), 0xFFFFFF, player.rotationYaw));
            mapController.draw();
            drawFootprintOverlays();
        }

        // Draw panel
        TravellersGuiTheme.drawPanel(panelX - 2, panelY - 2, panelW + 4, panelH + 4);

        // Title
        TravellersGuiTheme.drawTitle(this.fontRenderer,
            I18n.format("travellers.lone_barrack.prompt.title"),
            panelX + 4, panelY + 6);

        // Name label + field
        this.fontRenderer.drawString(
            I18n.format("travellers.lone_barrack.prompt.name"),
            panelX + 4, panelY + 18, TravellersGuiTheme.COL_TEXT_DIM);
        nameField.drawTextBox();

        // Sliders
        if (halfWidthSlider != null)  halfWidthSlider.draw(mc, mouseX, mouseY);
        if (halfDepthSlider != null)  halfDepthSlider.draw(mc, mouseX, mouseY);
        if (halfHeightSlider != null) halfHeightSlider.draw(mc, mouseX, mouseY);

        // Block counts
        if (chestsFound >= 0) {
            int countY = validateBtnY - 34;
            int colChests = chestsFound >= 1 ? 0xFF55FF55 : 0xFFFF5555;
            int colBeds   = bedsFound   >= 1 ? 0xFF55FF55 : 0xFFFF5555;
            this.fontRenderer.drawString(
                I18n.format("travellers.lone_barrack.prompt.chests", chestsFound, 1),
                panelX + 4, countY, colChests);
            this.fontRenderer.drawString(
                I18n.format("travellers.lone_barrack.prompt.beds", bedsFound, 1),
                panelX + 4, countY + 11, colBeds);
        }

        // Error message
        if (errorMessage != null && !errorMessage.isEmpty()) {
            int errY = validateBtnY - 20;
            int maxW = panelW - 8;
            // Word-wrap error in at most 2 lines
            java.util.List<String> lines = this.fontRenderer.listFormattedStringToWidth(errorMessage, maxW);
            for (int i = 0; i < Math.min(lines.size(), 3); i++) {
                this.fontRenderer.drawString(lines.get(i), panelX + 4, errY + i * 10, 0xFFFF4444);
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        com.jubitus.travellers.client.notify.NotificationOverlayRenderer.draw(new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()));
    }

    /** Draws existing barracks (semi-transparent red) and the new barrack footprint (orange) on the map. */
    private void drawFootprintOverlays() {
        if (mapController == null) return;
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx  = mapController.panel.getCenterXf();
        float cz  = mapController.panel.getCenterZf();

        // Existing barracks — red
        for (MsgOpenLoneBarrackPrompt.ExistingBarrack eb : existingBarracks) {
            drawRect(eb.cx, eb.cz, eb.halfWidth, eb.halfDepth, bpp, cx, cz, 0xAAFF3333);
        }

        // New barrack — orange
        int hw = halfWidthSlider  != null ? (int) halfWidthSlider.getValue()  : 5;
        int hd = halfDepthSlider  != null ? (int) halfDepthSlider.getValue()  : 5;
        drawRect(center.getX(), center.getZ(), hw, hd, bpp, cx, cz, 0xCCFFAA00);
    }

    private void drawRect(int worldCX, int worldCZ, int hw, int hd,
                          float bpp, float viewCX, float viewCZ, int color) {
        int screenCX = mapX + mapW / 2 + Math.round((worldCX - viewCX) / bpp);
        int screenCZ = mapY + mapH / 2 + Math.round((worldCZ - viewCZ) / bpp);
        int screenHW = Math.max(1, Math.round(hw / bpp));
        int screenHD = Math.max(1, Math.round(hd / bpp));

        // Unclipped rectangle corners
        int rx1 = screenCX - screenHW;
        int rz1 = screenCZ - screenHD;
        int rx2 = screenCX + screenHW;
        int rz2 = screenCZ + screenHD;

        int mx1 = mapX, mz1 = mapY, mx2 = mapX + mapW, mz2 = mapY + mapH;

        // Only draw each side if it falls (at least partially) within the map.
        // Top edge (rz1)
        if (rz1 >= mz1 && rz1 < mz2) {
            int sx1 = clamp(rx1, mx1, mx2), sx2 = clamp(rx2, mx1, mx2);
            if (sx1 < sx2) Gui.drawRect(sx1, rz1, sx2, rz1 + 1, color);
        }
        // Bottom edge (rz2)
        if (rz2 > mz1 && rz2 <= mz2) {
            int sx1 = clamp(rx1, mx1, mx2), sx2 = clamp(rx2, mx1, mx2);
            if (sx1 < sx2) Gui.drawRect(sx1, rz2 - 1, sx2, rz2, color);
        }
        // Left edge (rx1)
        if (rx1 >= mx1 && rx1 < mx2) {
            int sz1 = clamp(rz1, mz1, mz2), sz2 = clamp(rz2, mz1, mz2);
            if (sz1 < sz2) Gui.drawRect(rx1, sz1, rx1 + 1, sz2, color);
        }
        // Right edge (rx2)
        if (rx2 > mx1 && rx2 <= mx2) {
            int sz1 = clamp(rz1, mz1, mz2), sz2 = clamp(rz2, mz1, mz2);
            if (sz1 < sz2) Gui.drawRect(rx2 - 1, sz1, rx2, sz2, color);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
