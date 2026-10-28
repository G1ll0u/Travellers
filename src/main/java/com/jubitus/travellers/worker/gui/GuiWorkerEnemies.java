package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.combat.net.MsgRequestPlayerHostileList;
import com.jubitus.travellers.combat.net.MsgSetWorkerHostile;
import com.jubitus.travellers.combat.net.MsgSyncWorkerHostileList.Entry;
import com.jubitus.travellers.gui.FramedPanel;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.net.TravellersNetwork;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Lists every other known player and toggles each between friendly and enemy (see PlayerEnemyRelations). */
@SideOnly(Side.CLIENT)
public class GuiWorkerEnemies extends GuiScreen implements ITravellersGui {

    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> !e.enemy)
            .thenComparing(e -> !e.online)
            .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER);

    private final GuiScreen parent;
    private final EntityPlayer player;

    private final List<Entry> players = new ArrayList<>();
    @Nullable private UUID selected;
    private boolean received;

    private GuiButton toggleButton;
    private GuiButton backButton;

    private FramedPanel panel;
    private int listBoxY, listBoxH;
    private final ScrollList list = new ScrollList();

    public GuiWorkerEnemies(GuiScreen parent, EntityPlayer player) {
        this.parent = parent;
        this.player = player;
    }

    public void receivePlayers(List<Entry> entries) {
        received = true;
        players.clear();
        if (entries != null) players.addAll(entries);
        players.sort(ORDER);
        if (selectedIndex() < 0) selected = null;
        list.clamp(players.size());
        refreshToggle();
    }

    private int selectedIndex() {
        if (selected == null) return -1;
        for (int i = 0; i < players.size(); i++) if (players.get(i).id.equals(selected)) return i;
        return -1;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        panel = new FramedPanel(width, height, 280, 190);

        int bh = MapListLayout.BTN_H, gap = MapListLayout.BTN_GAP;
        int hintH = 2 * fontRenderer.FONT_HEIGHT + 4;
        listBoxY = panel.bodyY + hintH;
        listBoxH = panel.bodyH - hintH - bh - gap;
        list.setBounds(panel.bodyX + 1, listBoxY + 1, panel.bodyW - 2, listBoxH - 2);

        int half = (panel.bodyW - gap) / 2;
        int rowY = panel.bodyY + panel.bodyH - bh;
        backButton = addButton(new GuiTravellersButton(1, panel.bodyX, rowY, half, bh,
                I18n.format("travellers.gui.back", "Back")));
        toggleButton = addButton(new GuiTravellersButton(2, panel.bodyX + panel.bodyW - half, rowY, half, bh, ""));
        refreshToggle();

        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestPlayerHostileList());
    }

    private void refreshToggle() {
        if (toggleButton == null) return;
        int idx = selectedIndex();
        toggleButton.enabled = idx >= 0;
        toggleButton.displayString = I18n.format(idx >= 0 && players.get(idx).enemy
                ? "travellers.worker.enemies.make_friendly" : "travellers.worker.enemies.make_enemy");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        panel.draw(fontRenderer, I18n.format("travellers.worker.enemies.title", "Player relations"));

        List<String> hint = fontRenderer.listFormattedStringToWidth(
                I18n.format("travellers.worker.enemies.hint"), panel.bodyW);
        for (int i = 0; i < Math.min(2, hint.size()); i++) {
            fontRenderer.drawString(hint.get(i), panel.bodyX, panel.bodyY + i * fontRenderer.FONT_HEIGHT,
                    TravellersGuiTheme.COL_TEXT_DIM);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        drawPlayerList(mouseX, mouseY);
    }

    private void drawPlayerList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(panel.bodyX, listBoxY, panel.bodyW, listBoxH);
        if (players.isEmpty()) {
            if (received) {
                fontRenderer.drawString(I18n.format("travellers.worker.enemies.none"), list.x + ScrollList.TEXT_PAD,
                        list.y + ScrollList.TEXT_PAD, TravellersGuiTheme.COL_TEXT_DIM);
            }
            return;
        }

        list.clamp(players.size());
        int rowW = list.rowW(players.size());
        int selIdx = selectedIndex();
        for (int i = 0; i < list.rowsToDraw(players.size()); i++) {
            int idx = list.scroll() + i;
            int rowTop = list.rowTop(idx);
            Entry e = players.get(idx);
            boolean hovered = list.isRowHovered(idx, mouseX, mouseY) && mouseX < list.x + rowW;
            TravellersGuiTheme.drawListRow(list.x, rowTop, rowW, list.rowH, hovered, idx == selIdx);

            String tag = I18n.format(e.enemy ? "travellers.worker.enemies.tag.enemy" : "travellers.worker.enemies.tag.friendly");
            int tagW = fontRenderer.getStringWidth(tag);
            int nameColour = e.enemy ? TravellersGuiTheme.COL_CANNOT
                    : !e.online ? TravellersGuiTheme.COL_TEXT_DIM
                    : hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            int textY = list.textY(fontRenderer, rowTop);
            fontRenderer.drawString(fontRenderer.trimStringToWidth(e.name, rowW - tagW - ScrollList.TEXT_PAD * 3),
                    list.x + ScrollList.TEXT_PAD, textY, nameColour);
            fontRenderer.drawString(tag, list.x + rowW - tagW - ScrollList.TEXT_PAD, textY,
                    e.enemy ? TravellersGuiTheme.COL_CANNOT : TravellersGuiTheme.COL_TEXT_DIM);
        }
        list.drawScrollbar(players.size());
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (list.isOver(mouseX, mouseY)) list.scrollBy(wheel, players.size());
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || list.mouseDown(mouseX, mouseY, players.size())) return;

        int idx = list.rowAt(mouseX, mouseY, players.size());
        if (idx >= 0) {
            selected = players.get(idx).id;
            refreshToggle();
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        super.mouseClickMove(mouseX, mouseY, button, time);
        list.mouseDrag(mouseY, players.size());
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        list.mouseUp();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == toggleButton) {
            int idx = selectedIndex();
            if (idx < 0) return;
            Entry old = players.get(idx);
            // Optimistic: the server's refreshed list confirms (and re-sorts) a moment later.
            players.set(idx, new Entry(old.id, old.name, !old.enemy, old.online));
            refreshToggle();
            TravellersNetwork.CHANNEL.sendToServer(new MsgSetWorkerHostile(old.id, !old.enemy));
        } else if (button == backButton) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
