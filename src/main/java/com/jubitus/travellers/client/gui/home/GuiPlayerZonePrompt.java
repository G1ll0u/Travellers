package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.client.gui.formation.GuiWorkerFormationSlider;
import com.jubitus.travellers.client.gui.worldmap.WorldMapController;
import com.jubitus.travellers.client.gui.worldmap.WorldMapMarkerFactory;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgCreatePlayerZone;
import com.jubitus.travellers.server.net.message.home.MsgOpenZonePrompt;
import com.jubitus.travellers.server.net.message.map.MsgRequestMapDiscovery;
import com.jubitus.travellers.world.zone.BlockRoleRequirement;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.ZoneType;
import com.jubitus.travellers.world.zone.ZoneTypeDef;
import com.jubitus.travellers.world.zone.ZoneTypeDefs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The zone-creation prompt: player picks a type (Barrack/Stable, extensible to more), sizes the box
 * with three sliders, and sees a live per-role block count. Every element's Y is derived by
 * advancing a layout cursor from the element above it (see {@link #relayout()}) rather than fixed
 * pixel offsets from the panel's corners - that fixed-offset scheme was what let a wrapped error
 * message overlap the buttons below it (an error growing to 3 lines from a Y computed for 1 always
 * collided with content that was itself positioned backward from the bottom).
 */
@SideOnly(Side.CLIENT)
public class GuiPlayerZonePrompt extends GuiScreen implements ITravellersGui {

    private static final int TITLE_H = 10;
    private static final int GAP = 4;
    private static final int ROW_H = 11;
    /** Error text is reserved a fixed block sized for the worst case (see the class doc) - it never
     * pushes anything below it, whether zero, one or three lines are actually shown. */
    private static final int ERROR_AREA_LINES = 3;

    private final EntityPlayer player;
    private final BlockPos center;
    private final List<MsgOpenZonePrompt.ExistingBarrack> existingZones;

    private int mapX, mapY, mapW, mapH;
    private int panelX, panelY, panelW, panelH;
    private int descX, descY, descW, descH;

    private WorldMapController mapController;
    private GuiTextField nameField;

    private GuiWorkerFormationSlider halfWidthSlider;
    private GuiWorkerFormationSlider halfDepthSlider;
    private GuiWorkerFormationSlider halfHeightSlider;

    private GuiButton typeToggleBtn;
    private GuiButton validateBtn, cancelBtn;
    private int roleRowsY;
    private int errorAreaY;
    /** Where the fixed upper part of the panel (toggle, name, sliders - none of it depends on the
     * selected type) ends. {@link #relayout()} only ever repositions what comes after this, since
     * {@link GuiWorkerFormationSlider}'s position is fixed at construction. */
    private int afterSlidersCursor;

    private ZoneType selectedType = ZoneType.BARRACK;
    private String errorMessage = null;

    // Block scan cache — updated only when slider values or the selected type change
    private final Map<String, Integer> foundByRole = new LinkedHashMap<>();
    private int lastScannedHw = -1;
    private int lastScannedHd = -1;
    private int lastScannedHh = -1;
    private ZoneType lastScannedType = null;

    public GuiPlayerZonePrompt(EntityPlayer player, BlockPos center) {
        this(player, center, Collections.emptyList());
    }

    public GuiPlayerZonePrompt(EntityPlayer player, BlockPos center,
                                List<MsgOpenZonePrompt.ExistingBarrack> existingZones) {
        this.player = player;
        this.center = center;
        this.existingZones = existingZones != null ? existingZones : Collections.emptyList();
    }

    @Override
    public void initGui() {
        super.initGui();
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        mapW = 200;
        mapH = 200;
        panelW = 160;
        descW = 150;

        int totalW = mapW + 10 + panelW + 10 + descW;
        int startX = (this.width - totalW) / 2;
        mapX = startX;
        mapY = (this.height - mapH) / 2;
        panelX = startX + mapW + 10;
        panelY = mapY;
        descX = panelX + panelW + 10;
        descY = mapY;

        this.buttonList.clear();

        int bx = panelX + 4;
        int bw = panelW - 8;
        int bh = 16;

        // Fixed upper part - identical regardless of the selected type, so these are placed once,
        // at their final position, and never moved (GuiWorkerFormationSlider's position is fixed
        // at construction). Only what follows (role rows, error area, buttons) depends on the type
        // and is (re)placed by relayout().
        int cursor = panelY + TITLE_H + GAP;

        typeToggleBtn = addButton(new GuiTravellersButton(3, bx, cursor, bw, bh, ""));
        cursor += bh + GAP;

        cursor += 10; // name label line
        nameField = new GuiTextField(0, this.fontRenderer, bx, cursor, bw, 14);
        nameField.setMaxStringLength(32);
        nameField.setFocused(true);
        cursor += 14 + GAP + 2;

        int defaultHalfWidth  = clamp(TravellersConfig.WORKER.workerLoneBarrackHalfWidth,  4, 32);
        int defaultHalfDepth  = clamp(TravellersConfig.WORKER.workerLoneBarrackHalfDepth,  4, 32);
        int defaultHalfHeight = clamp(TravellersConfig.WORKER.workerLoneBarrackHalfHeight, 2, 64);

        halfWidthSlider = new GuiWorkerFormationSlider(
            bx, cursor, bw, 20, "travellers.lone_barrack.prompt.half_width",
            4, 16, 1, defaultHalfWidth, v -> String.valueOf((int) v));
        cursor += 30;
        halfDepthSlider = new GuiWorkerFormationSlider(
            bx, cursor, bw, 20, "travellers.lone_barrack.prompt.half_depth",
            4, 16, 1, defaultHalfDepth, v -> String.valueOf((int) v));
        cursor += 30;
        halfHeightSlider = new GuiWorkerFormationSlider(
            bx, cursor, bw, 20, "travellers.lone_barrack.prompt.half_height",
            2, 32, 1, defaultHalfHeight, v -> String.valueOf((int) v));
        cursor += 30 + GAP;

        afterSlidersCursor = cursor;

        validateBtn = addButton(new GuiTravellersButton(1, bx, 0, bw, bh, ""));
        cancelBtn = addButton(new GuiTravellersButton(2, bx, 0, bw, bh, I18n.format("travellers.gui.cancel")));

        setType(selectedType);

        mapController = new WorldMapController(
            Minecraft.getMinecraft(), mapX, mapY, mapW, mapH,
            "lone_barrack_prompt_map", center);
        mapController.panel.addMarker(WorldMapMarkerFactory.player(center, player.getName(), 0xFFFFFF, player.rotationYaw));

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestMapDiscovery());
    }

    /** Switches the zone type being configured: resets the scan cache (a different type has
     * different required roles) and repositions every element below the toggle button. */
    private void setType(ZoneType type) {
        this.selectedType = type;
        this.lastScannedType = null; // force rescan
        this.errorMessage = null;
        ZoneTypeDef def = ZoneTypeDefs.get(type);
        typeToggleBtn.displayString = I18n.format(def.titleKey);
        validateBtn.displayString = I18n.format("travellers.zone.prompt.validate", I18n.format(def.titleKey));
        relayout();
    }

    /** Positions everything below the fixed upper part (see {@link #afterSlidersCursor}) by
     * advancing a cursor - the part of the layout that actually depends on the selected type's
     * role count. See the class doc for why this beats fixed pixel offsets. */
    private void relayout() {
        int bh = 16;
        int cursor = afterSlidersCursor;

        roleRowsY = cursor;
        int roleCount = ZoneTypeDefs.get(selectedType).requiredRoles.size();
        cursor += roleCount * ROW_H + GAP;

        errorAreaY = cursor;
        cursor += ERROR_AREA_LINES * ROW_H + GAP;

        validateBtn.y = cursor;
        cursor += bh + GAP;
        cancelBtn.y = cursor;
        cursor += bh;

        panelH = cursor - panelY + 6;
        descH = panelH;
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
        int hw = (int) halfWidthSlider.getValue();
        int hd = (int) halfDepthSlider.getValue();
        int hh = (int) halfHeightSlider.getValue();
        if (hw != lastScannedHw || hd != lastScannedHd || hh != lastScannedHh || selectedType != lastScannedType) {
            rescanBlocks(hw, hd, hh);
            lastScannedHw = hw;
            lastScannedHd = hd;
            lastScannedHh = hh;
            lastScannedType = selectedType;
        }
    }

    private void rescanBlocks(int hw, int hd, int hh) {
        if (mc.world == null) return;
        foundByRole.clear();
        for (BlockRoleRequirement req : ZoneTypeDefs.get(selectedType).requiredRoles) {
            int count = PlayerZoneManager.scanRole(mc.world, center, hw, hd, hh, req.scanner).size();
            foundByRole.put(req.role, count);
        }
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
        if (button == typeToggleBtn) {
            ZoneType[] values = ZoneType.values();
            int next = (selectedType.ordinal() + 1) % values.length;
            setType(values[next]);
            return;
        }
        if (button == validateBtn) {
            String name = nameField.getText().trim();
            if (name.isEmpty()) return;
            errorMessage = null;
            int hw = (int) halfWidthSlider.getValue();
            int hd = (int) halfDepthSlider.getValue();
            int hh = (int) halfHeightSlider.getValue();
            TravellerFollowNet.CHANNEL.sendToServer(new MsgCreatePlayerZone(name, center, hw, hd, hh, selectedType));
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mapController != null) mapController.onMouseClick(mouseX, mouseY, mouseButton);
        nameField.mouseClicked(mouseX, mouseY, mouseButton);
        halfWidthSlider.mousePressed(mouseX, mouseY, mouseButton);
        halfDepthSlider.mousePressed(mouseX, mouseY, mouseButton);
        halfHeightSlider.mousePressed(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (mapController != null) mapController.onMouseDrag(mouseX, mouseY);
        halfWidthSlider.mouseDragged(mouseX, mouseY);
        halfDepthSlider.mouseDragged(mouseX, mouseY);
        halfHeightSlider.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (mapController != null) mapController.onMouseRelease(state);
        halfWidthSlider.mouseReleased(state);
        halfDepthSlider.mouseReleased(state);
        halfHeightSlider.mouseReleased(state);
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

        // Control panel
        TravellersGuiTheme.drawPanel(panelX - 2, panelY - 2, panelW + 4, panelH + 4);
        TravellersGuiTheme.drawTitle(this.fontRenderer,
            I18n.format("travellers.lone_barrack.prompt.title"), panelX + 4, panelY + 6);

        this.fontRenderer.drawString(
            I18n.format("travellers.lone_barrack.prompt.name"),
            panelX + 4, nameField.y - 10, TravellersGuiTheme.COL_TEXT_DIM);
        nameField.drawTextBox();

        halfWidthSlider.draw(mc, mouseX, mouseY);
        halfDepthSlider.draw(mc, mouseX, mouseY);
        halfHeightSlider.draw(mc, mouseX, mouseY);

        drawRoleRows();

        if (errorMessage != null && !errorMessage.isEmpty()) {
            int maxW = panelW - 8;
            List<String> lines = this.fontRenderer.listFormattedStringToWidth(errorMessage, maxW);
            for (int i = 0; i < Math.min(lines.size(), ERROR_AREA_LINES); i++) {
                this.fontRenderer.drawString(lines.get(i), panelX + 4, errorAreaY + i * ROW_H, 0xFFFF4444);
            }
        }

        // Description panel
        drawDescriptionPanel();

        super.drawScreen(mouseX, mouseY, partialTicks);
        com.jubitus.travellers.client.notify.NotificationOverlayRenderer.draw(new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()));
    }

    private void drawRoleRows() {
        int i = 0;
        for (BlockRoleRequirement req : ZoneTypeDefs.get(selectedType).requiredRoles) {
            int found = foundByRole.getOrDefault(req.role, 0);
            boolean ok = found >= req.minCount;
            String key = "travellers.zone.prompt.role." + req.role;
            String text = I18n.format(key, found, req.minCount);
            this.fontRenderer.drawString(text, panelX + 4, roleRowsY + i * ROW_H,
                    ok ? 0xFF55FF55 : 0xFFFF5555);
            i++;
        }
    }

    /** Reference text explaining every zone type, side by side with whichever one is currently
     * selected - the player is choosing between them via the toggle, so both stay visible rather
     * than only the one currently picked. Reflows to whatever width/scale the panel actually has. */
    private void drawDescriptionPanel() {
        TravellersGuiTheme.drawPanel(descX - 2, descY - 2, descW + 4, descH + 4);
        int maxW = descW - 8;
        int y = descY + 6;
        for (ZoneType type : ZoneType.values()) {
            ZoneTypeDef def = ZoneTypeDefs.get(type);
            boolean current = type == selectedType;
            TravellersGuiTheme.drawTitle(this.fontRenderer, I18n.format(def.titleKey), descX + 4, y);
            y += TITLE_H + 2;
            List<String> lines = this.fontRenderer.listFormattedStringToWidth(I18n.format(def.descriptionKey), maxW);
            for (String line : lines) {
                this.fontRenderer.drawString(line, descX + 4, y,
                        current ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM);
                y += 10;
            }
            y += GAP;
        }
    }

    /** Draws existing zones (semi-transparent red) and the new zone's footprint (orange) on the map. */
    private void drawFootprintOverlays() {
        if (mapController == null) return;
        float bpp = mapController.panel.getZoomBlocksPerPixel();
        float cx  = mapController.panel.getCenterXf();
        float cz  = mapController.panel.getCenterZf();

        for (MsgOpenZonePrompt.ExistingBarrack eb : existingZones) {
            drawRect(eb.cx, eb.cz, eb.halfWidth, eb.halfDepth, bpp, cx, cz, 0xAAFF3333);
        }

        int hw = (int) halfWidthSlider.getValue();
        int hd = (int) halfDepthSlider.getValue();
        drawRect(center.getX(), center.getZ(), hw, hd, bpp, cx, cz, 0xCCFFAA00);
    }

    private void drawRect(int worldCX, int worldCZ, int hw, int hd,
                          float bpp, float viewCX, float viewCZ, int color) {
        int screenCX = mapX + mapW / 2 + Math.round((worldCX - viewCX) / bpp);
        int screenCZ = mapY + mapH / 2 + Math.round((worldCZ - viewCZ) / bpp);
        int screenHW = Math.max(1, Math.round(hw / bpp));
        int screenHD = Math.max(1, Math.round(hd / bpp));

        int rx1 = screenCX - screenHW;
        int rz1 = screenCZ - screenHD;
        int rx2 = screenCX + screenHW;
        int rz2 = screenCZ + screenHD;

        int mx1 = mapX, mz1 = mapY, mx2 = mapX + mapW, mz2 = mapY + mapH;

        if (rz1 >= mz1 && rz1 < mz2) {
            int sx1 = clamp(rx1, mx1, mx2), sx2 = clamp(rx2, mx1, mx2);
            if (sx1 < sx2) Gui.drawRect(sx1, rz1, sx2, rz1 + 1, color);
        }
        if (rz2 > mz1 && rz2 <= mz2) {
            int sx1 = clamp(rx1, mx1, mx2), sx2 = clamp(rx2, mx1, mx2);
            if (sx1 < sx2) Gui.drawRect(sx1, rz2 - 1, sx2, rz2, color);
        }
        if (rx1 >= mx1 && rx1 < mx2) {
            int sz1 = clamp(rz1, mz1, mz2), sz2 = clamp(rz2, mz1, mz2);
            if (sz1 < sz2) Gui.drawRect(rx1, sz1, rx1 + 1, sz2, color);
        }
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
