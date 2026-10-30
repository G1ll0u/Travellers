package com.jubitus.travellers.gui;

import com.jubitus.travellers.marker.client.ClientOwnerVisualsCache;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worker.net.MsgWorkerRequestGoList;
import com.jubitus.travellers.worldmap.gui.WorkerMapView;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerGoListReceiver;
import com.jubitus.travellers.worldmap.gui.receiver.IWorkerMapScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;

/**
 * Base of the "map + list" screens (orders, recruiting, relations): the shared {@link MapListLayout},
 * one {@link WorkerMapView}, one {@link ScrollList}, and all the mouse routing between them.
 * A subclass says what the rows are and what the buttons do.
 */
@SideOnly(Side.CLIENT)
public abstract class GuiMapListScreen extends GuiScreen implements ITravellersGui, IWorkerGoListReceiver, IWorkerMapScreen {

    /** First button id free for the screen's own buttons (the map's nav buttons take 0..6). */
    protected static final int FIRST_FREE_ID = 10;

    protected MapListLayout layout;
    protected WorkerMapView map;
    protected final ScrollList list = new ScrollList();
    protected final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();

    // ── What a screen provides ────────────────────────────────────────────────

    protected abstract String title();

    /** Distinct per screen: the map's tile textures are keyed by it. */
    protected abstract String mapKey();

    /** Where the map opens and where Center goes back to. */
    protected abstract BlockPos home();

    protected abstract int rowCount();

    /** Content of one row (its background is drawn already); x is the row's left edge. */
    protected abstract void drawRow(int idx, int x, int top, int rowW, boolean hovered, int mouseX, int mouseY);

    /** Adds the screen's own buttons, placed with {@link #layout}. */
    protected abstract void addControls();

    protected int sideWidth() {
        return 170;
    }

    /** Rows of buttons under the list (the map's nav row is level with the last one). */
    protected int buttonRows() {
        return 1;
    }

    /** Space at the top of the list box the screen draws itself (a count, a cost). */
    protected int listHeaderH() {
        return 0;
    }

    /** Space at the bottom of the list box the screen draws itself (totals). */
    protected int listFooterH() {
        return 0;
    }

    protected boolean isRowSelected(int idx) {
        return false;
    }

    protected void onRowClicked(int idx, int mouseX, int mouseY) {}

    /** Left click on the map; pos is the ground under the cursor. */
    protected void onMapClicked(BlockPos pos) {}

    protected void onPlacesReceived() {}

    /** Entity id the server builds the places list for, or -1 for the player's own. */
    protected int goListWorkerId() {
        return -1;
    }

    protected boolean onAction(GuiButton button) throws IOException {
        return false;
    }

    /** What is drawn in an empty list, or null. */
    @Nullable
    protected String emptyListText() {
        return null;
    }

    /** True while an overlay covers the side column: the list and the map ignore clicks. */
    protected boolean sideLocked() {
        return false;
    }

    protected int ownerColor() {
        return ClientOwnerVisualsCache.getColor();
    }

    /** Drawn over the side column, under the buttons (a dialog panel). */
    protected void drawSideOverlay(int mouseX, int mouseY) {}

    /** Drawn last, over everything (tooltips). */
    protected void drawTop(int mouseX, int mouseY) {}

    protected void onOpen() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public final void initGui() {
        super.initGui();
        layout = new MapListLayout(width, height, sideWidth(), buttonRows());
        buttonList.clear();
        tooltips.clear();
        map = new WorkerMapView(layout.mapX(), layout.mapY(), layout.mapW(), layout.mapH(), mapKey(), home());
        map.addNav(buttonList, 0, this::home, layout.navRowY());
        int header = listHeaderH();
        list.setBounds(layout.listX(), layout.listY() + header, layout.listW(), layout.listH() - header - listFooterH());
        list.resetScroll();
        addControls();
        map.subscribe();
        requestPlaces();
        onOpen();
    }

    /** Asks the server for the places again (their state changed); they arrive in {@link #receiveGoList}. */
    protected void requestPlaces() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestGoList(goListWorkerId()));
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        map.unsubscribe();
    }

    @Override
    public WorkerMapView mapView() {
        return map;
    }

    @Override
    public void receiveGoList(List<MsgWorkerGoList.Entry> entries) {
        map.data.setPlaces(entries);
        onPlacesReceived();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        layout.drawChrome(fontRenderer, title());
        map.draw(mc.player, ownerColor(), sideLocked() ? -1 : mouseX, sideLocked() ? -1 : mouseY);
        drawList(mouseX, mouseY);
        drawSideOverlay(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        map.drawTooltip(this, mouseX, mouseY);
        tooltips.render(mouseX, mouseY, width, height);
        drawTop(mouseX, mouseY);
    }

    private void drawList(int mouseX, int mouseY) {
        int count = rowCount();
        if (count == 0) {
            String empty = emptyListText();
            if (empty != null) {
                TravellersGuiTheme.drawCenteredText(fontRenderer, empty, list.x + list.w / 2,
                        list.y + list.h / 2 - fontRenderer.FONT_HEIGHT / 2, TravellersGuiTheme.COL_TEXT_DIM);
            }
            return;
        }
        list.clamp(count);
        int rowW = list.rowW(count);
        boolean live = !sideLocked();
        for (int i = 0; i < list.rowsToDraw(count); i++) {
            int idx = list.scroll() + i;
            int top = list.rowTop(idx);
            boolean hovered = live && list.isRowHovered(idx, mouseX, mouseY) && mouseX < list.x + rowW;
            TravellersGuiTheme.drawListRow(list.x, top, rowW, list.rowH, hovered, isRowSelected(idx));
            drawRow(idx, list.x, top, rowW, hovered, mouseX, mouseY);
        }
        list.drawScrollbar(count);
    }

    /** One line of text in a row, trimmed to fit, in the standard colours. */
    protected void drawRowLabel(String text, int x, int top, int rowW, boolean hovered) {
        int tx = x + ScrollList.TEXT_PAD;
        fontRenderer.drawString(fontRenderer.trimStringToWidth(text, rowW - ScrollList.TEXT_PAD * 2), tx,
                list.textY(fontRenderer, top), hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || sideLocked()) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (map.isMouseOver(mouseX, mouseY)) map.wheel(mouseX, mouseY, wheel);
        else if (list.isOver(mouseX, mouseY)) list.scrollBy(wheel, rowCount());
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (sideLocked()) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        if (map.mouseDown(mouseX, mouseY, button)) {
            super.mouseClicked(mouseX, mouseY, button);
            return;
        }
        if (button == 0 && list.mouseDown(mouseX, mouseY, rowCount())) return;
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) return;
        if (map.isMouseOver(mouseX, mouseY)) {
            onMapClicked(map.controller.screenToWorld(mouseX, mouseY));
            return;
        }
        int idx = list.rowAt(mouseX, mouseY, rowCount());
        if (idx >= 0) onRowClicked(idx, mouseX, mouseY);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        super.mouseClickMove(mouseX, mouseY, button, time);
        map.mouseDrag(mouseX, mouseY);
        list.mouseDrag(mouseY, rowCount());
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        map.mouseUp(state);
        list.mouseUp();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (map.handleButton(button)) return;
        if (!onAction(button)) super.actionPerformed(button);
    }
}
