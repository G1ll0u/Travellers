package com.jubitus.travellers.client.gui.bahavior;

import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.client.gui.TravellersGuiTheme;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSetWorkerHostile;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class GuiWorkerEnemies extends GuiScreen implements ITravellersGui {
    private final GuiScreen parent;
    private final EntityPlayer player;
    private final EntityTravellerWorker worker;

    private GuiTextField nameField;
    private GuiButton addButton;
    private GuiButton removeButton;
    private GuiButton doneButton;

    // list placement like Recruit
    private int listX, listY, listW, listH;
    private final int rowH = 12;

    // optional: click a name to fill field
    private int hoveredRow = -1;

    public GuiWorkerEnemies(GuiScreen parent, EntityPlayer player, EntityTravellerWorker worker) {
        this.parent = parent;
        this.player = player;
        this.worker = worker;
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
        com.jubitus.travellers.client.notify.NotificationOverlayRenderer.draw(new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft()));
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
            java.util.List<String> names = worker.getHostileNamesClient();
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
            TravellerFollowNet.CHANNEL.sendToServer(new MsgSetWorkerHostile(worker.getEntityId(), name, true));
            nameField.setText("");
            return;
        }
        if (button == removeButton) {
            TravellerFollowNet.CHANNEL.sendToServer(new MsgSetWorkerHostile(worker.getEntityId(), name, false));
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

        this.addButton = this.addButton(new GuiButton(
                1,
                cx - 100,
                cy + 20,
                98,
                20,
                I18n.format("travellers.gui.add", "Add")
        ));
        this.removeButton = this.addButton(new GuiButton(
                2,
                cx + 2,
                cy + 20,
                98,
                20,
                I18n.format("travellers.gui.remove", "Remove")
        ));
        this.doneButton = this.addButton(new GuiButton(
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
        java.util.List<String> names = worker.getHostileNamesClient();
        if (names == null) names = java.util.Collections.emptyList();

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
