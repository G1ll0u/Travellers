package com.jubitus.travellers.worker.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.FramedPanel;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.ScrollList;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.combat.net.MsgRequestPlayerHostileList;
import com.jubitus.travellers.combat.net.MsgSetWorkerHostile;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Edits the player's account-wide declared-enemy list (see PlayerEnemyRelations). */
@SideOnly(Side.CLIENT)
public class GuiWorkerEnemies extends GuiScreen implements ITravellersGui {
    private static final int LIST_W = 150;
    private static final int HEADER_H = 14;

    private final GuiScreen parent;
    private final EntityPlayer player;

    private final List<String> names = new ArrayList<>();

    private GuiTextField nameField;
    private GuiButton addButton;
    private GuiButton removeButton;
    private GuiButton doneButton;

    private FramedPanel panel;
    private int listBoxX;
    private final ScrollList list = new ScrollList();

    public GuiWorkerEnemies(GuiScreen parent, EntityPlayer player) {
        this.parent = parent;
        this.player = player;
    }

    public void receiveHostileNames(List<String> names) {
        this.names.clear();
        if (names != null) this.names.addAll(names);
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        panel = new FramedPanel(width, height, 360, 170);

        int leftW = panel.bodyW - LIST_W - MapListLayout.GAP;
        int bh = MapListLayout.BTN_H, gap = MapListLayout.BTN_GAP;
        listBoxX = panel.bodyX + leftW + MapListLayout.GAP;
        list.setBounds(listBoxX + 1, panel.bodyY + 1 + HEADER_H, LIST_W - 2, panel.bodyH - 2 - HEADER_H);

        // Hint on top, then the name field and its two buttons; Done sits on the bottom edge.
        int fieldY = panel.bodyY + 2 * fontRenderer.FONT_HEIGHT + 8;
        this.nameField = new GuiTextField(0, this.fontRenderer, panel.bodyX, fieldY, leftW, 20);
        this.nameField.setFocused(true);
        this.nameField.setMaxStringLength(16);

        int half = (leftW - gap) / 2;
        int rowY = fieldY + 20 + gap;
        this.addButton = this.addButton(new GuiTravellersButton(1, panel.bodyX, rowY, half, bh,
                I18n.format("travellers.gui.add", "Add")));
        this.removeButton = this.addButton(new GuiTravellersButton(2, panel.bodyX + leftW - half, rowY, half, bh,
                I18n.format("travellers.gui.remove", "Remove")));
        this.doneButton = this.addButton(new GuiTravellersButton(3, panel.bodyX, panel.bodyY + panel.bodyH - bh, leftW, bh,
                I18n.format("travellers.gui.done", "Done")));

        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestPlayerHostileList());
    }

    @Override
    public void updateScreen() {
        this.nameField.updateCursorCounter();

        boolean hasText = !this.nameField.getText().trim().isEmpty();
        this.addButton.enabled = hasText;
        this.removeButton.enabled = hasText;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        panel.draw(fontRenderer, I18n.format("travellers.worker.enemies.title", "Add/remove player enemies"));

        int leftW = panel.bodyW - LIST_W - MapListLayout.GAP;
        fontRenderer.drawString(I18n.format("travellers.worker.enemies.hint", "(that Henchmen will attack at sight)"),
                panel.bodyX, panel.bodyY, TravellersGuiTheme.COL_TEXT_DIM);
        List<String> clicks = fontRenderer.listFormattedStringToWidth(
                I18n.format("travellers.worker.enemies.click_hint"), leftW);
        for (int i = 0; i < clicks.size(); i++) {
            fontRenderer.drawString(clicks.get(i), panel.bodyX, panel.bodyY + (i + 1) * fontRenderer.FONT_HEIGHT + 2,
                    TravellersGuiTheme.COL_TEXT_DIM);
        }

        this.nameField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        drawEnemyList(mouseX, mouseY);
    }

    private void drawEnemyList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listBoxX, panel.bodyY, LIST_W, panel.bodyH);
        fontRenderer.drawString(I18n.format("travellers.worker.enemies.count", names.size()), listBoxX + 4,
                panel.bodyY + 1 + (HEADER_H - fontRenderer.FONT_HEIGHT) / 2 + 1, TravellersGuiTheme.COL_TEXT);
        TravellersGuiTheme.drawSeparator(listBoxX + 1, list.y - 1, LIST_W - 2);

        list.clamp(names.size());
        int rowW = list.rowW(names.size());
        for (int i = 0; i < list.rowsToDraw(names.size()); i++) {
            int idx = list.scroll() + i;
            int rowTop = list.rowTop(idx);
            boolean hovered = list.isRowHovered(idx, mouseX, mouseY) && mouseX < list.x + rowW;
            TravellersGuiTheme.drawListRow(list.x, rowTop, rowW, list.rowH, hovered, false);
            fontRenderer.drawString(fontRenderer.trimStringToWidth(names.get(idx), rowW - ScrollList.TEXT_PAD * 2),
                    list.x + ScrollList.TEXT_PAD, list.textY(fontRenderer, rowTop),
                    hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
        }
        list.drawScrollbar(names.size());
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (list.isOver(mouseX, mouseY)) list.scrollBy(wheel, names.size());
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.nameField.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        this.nameField.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || list.mouseDown(mouseX, mouseY, names.size())) return;

        // click row to copy name into field
        int idx = list.rowAt(mouseX, mouseY, names.size());
        if (idx >= 0) this.nameField.setText(names.get(idx));
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        super.mouseClickMove(mouseX, mouseY, button, time);
        list.mouseDrag(mouseY, names.size());
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        list.mouseUp();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        String name = nameField.getText().trim();

        if (button == addButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgSetWorkerHostile(name, true));
            nameField.setText("");
            return;
        }
        if (button == removeButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgSetWorkerHostile(name, false));
            nameField.setText("");
            return;
        }
        if (button == doneButton) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
