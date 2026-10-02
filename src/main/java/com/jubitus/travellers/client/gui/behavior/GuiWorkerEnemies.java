package com.jubitus.travellers.client.gui.behavior;

import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Edits the player's account-wide declared-enemy list (see PlayerEnemyRelations). */
@SideOnly(Side.CLIENT)
public class GuiWorkerEnemies extends GuiScreen implements ITravellersGui {
    private final GuiScreen parent;
    private final EntityPlayer player;

    private final List<String> names = new ArrayList<>();

    private GuiTextField nameField;
    private GuiButton addButton;
    private GuiButton removeButton;
    private GuiButton doneButton;

    // list placement like Recruit
    private int listX, listY, listW, listH;
    private final int rowH = 12;

    // optional: click a name to fill field
    private int hoveredRow = -1;

    public GuiWorkerEnemies(GuiScreen parent, EntityPlayer player) {
        this.parent = parent;
        this.player = player;
    }

    public void receiveHostileNames(List<String> names) {
        this.names.clear();
        if (names != null) this.names.addAll(names);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int cx = this.width / 2;
        int cy = this.height / 2;

        this.drawCenteredString(
                this.fontRenderer,
                I18n.format("travellers.worker.enemies.title", "Add/remove player enemies"),
                cx,
                cy - 40,
                0xf9cbae
        );
        this.drawCenteredString(
                this.fontRenderer,
                I18n.format("travellers.worker.enemies.hint", "(Click a name on the right to fill it)"),
                cx,
                cy - 28,
                0xA0A0A0
        );

        this.nameField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        // compute hover here (if you need it)
        this.hoveredRow = getRowIndexAt(mouseX, mouseY);

        drawEnemyList(mouseX, mouseY);
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

        // click row to copy name into field
        if (mouseButton == 0) {
            int idx = getRowIndexAt(mouseX, mouseY);
            if (idx >= 0 && idx < names.size()) {
                this.nameField.setText(names.get(idx));
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        String name = nameField.getText().trim();

        if (button == addButton) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgSetWorkerHostile(name, true));
            nameField.setText("");
            return;
        }
        if (button == removeButton) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgSetWorkerHostile(name, false));
            nameField.setText("");
            return;
        }
        if (button == doneButton) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void initGui() {
        this.buttonList.clear();

        int cx = this.width / 2;
        int cy = this.height / 2;

        this.nameField = new GuiTextField(0, this.fontRenderer, cx - 100, cy - 10, 200, 20);
        this.nameField.setFocused(true);
        this.nameField.setMaxStringLength(16);

        this.addButton = this.addButton(new GuiTravellersButton(
                1,
                cx - 100,
                cy + 20,
                98,
                20,
                I18n.format("travellers.gui.add", "Add")
        ));
        this.removeButton = this.addButton(new GuiTravellersButton(
                2,
                cx + 2,
                cy + 20,
                98,
                20,
                I18n.format("travellers.gui.remove", "Remove")
        ));
        this.doneButton = this.addButton(new GuiTravellersButton(
                3,
                cx - 50,
                cy + 50,
                100,
                20,
                I18n.format("travellers.gui.done", "Done")
        ));

        // Right-side list (same idea as GuiWorkerRecruit)
        this.listW = 140;
        this.listH = 120;
        this.listX = cx + 120;        // push to the right of your center controls
        this.listY = cy - 60;         // vertically aligned

        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestPlayerHostileList());
    }

    @Override
    public void updateScreen() {
        this.nameField.updateCursorCounter();

        boolean hasText = !this.nameField.getText().trim().isEmpty();
        this.addButton.enabled = hasText;
        this.removeButton.enabled = hasText;
    }

    private int getRowIndexAt(int mouseX, int mouseY) {
        if (mouseX < listX || mouseX >= listX + listW) return -1;
        if (mouseY < listY || mouseY >= listY + listH) return -1;
        return (mouseY - listY) / rowH;
    }

    private void drawEnemyList(int mouseX, int mouseY) {
        TravellersGuiTheme.drawInnerPanel(listX - 2, listY - 2, listW + 4, listH + 4);
        TravellersGuiTheme.drawTitle(this.fontRenderer,
                I18n.format("travellers.worker.enemies.count", names.size()),
                listX, listY - 10);

        int maxRows = Math.min(listH / rowH, names.size());
        for (int i = 0; i < maxRows; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;

            TravellersGuiTheme.drawListRow(listX, rowTop, listW, rowH, hovered, false);

            String name = names.get(i);

            final float scale = 0.85F;
            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);

            int maxUnscaledWidth = (int) ((this.listW - 6) / scale);
            String label = this.fontRenderer.trimStringToWidth(name, maxUnscaledWidth);

            int textColor = hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            this.fontRenderer.drawString(label, 0, 0, textColor);
            GlStateManager.popMatrix();
        }
    }
    @Override
    public boolean doesGuiPauseGame() { return false; }
}
