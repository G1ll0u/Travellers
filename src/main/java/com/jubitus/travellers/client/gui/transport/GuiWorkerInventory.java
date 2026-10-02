package com.jubitus.travellers.client.gui.transport;

import com.jubitus.travellers.client.gui.GuiTravellersButton;
import com.jubitus.travellers.client.gui.ITravellersGui;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class GuiWorkerInventory extends GuiContainer implements ITravellersGui {

    // ── Palette de couleurs thématique (Beige / Hub) ──────────────────────────
    private static final int COL_BG       = 0xFF2A2219;
    private static final int COL_BORDER   = 0xFF685747;
    private static final int COL_TEXT     = 0xF2D2B9;
    private static final int COL_TEXT_DIM = 0xFFA08060;
    private static final int COL_PANEL    = 0xFF1E1A14;

    private GuiButton backButton;
    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    public GuiWorkerInventory(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerInventory(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 170;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();
        this.backButton = this.addButton(new GuiTravellersButton(
                0, this.guiLeft + this.xSize - 58, this.guiTop + this.ySize,
                54, 20, I18n.format("travellers.gui.back")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.renderHoveredToolTip(mouseX, mouseY);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString(I18n.format("travellers.worker.inventory.title", "Worker Equipment"), 8, 6, COL_TEXT);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        this.drawDefaultBackground();

        int x = this.guiLeft;
        int y = this.guiTop;

        drawRect(x, y, x + xSize, y + ySize, COL_BG);
        drawRect(x, y, x + xSize, y + 1, COL_BORDER);
        drawRect(x, y + ySize - 1, x + xSize, y + ySize, COL_BORDER);
        drawRect(x, y, x + 1, y + ySize, COL_BORDER);
        drawRect(x + xSize - 1, y, x + xSize, y + ySize, COL_BORDER);

        drawRect(x + 7, y + 83, x + 169, y + 84, COL_BORDER);


        for (int i = 0; i < ContainerWorkerInventory.ARMOR_COUNT; i++) {
            drawSlotBg(x + 8 + i * 18, y + 18, mouseX, mouseY);
        }

        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotBg(x + 8 + col * 18, y + 40 + row * 18, mouseX, mouseY);
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlotBg(x + 8 + col * 18, y + 90 + row * 18, mouseX, mouseY);
            }
        }

        for (int i = 0; i < 9; i++) {
            drawSlotBg(x + 8 + i * 18, y + 148, mouseX, mouseY);
        }
    }
    /**
     * Dessine l'arrière-plan d'une case et applique un éclaircissement si la souris la survole.
     */
    private void drawSlotBg(int x, int y, int mouseX, int mouseY) {
        drawRect(x - 1, y - 1, x + 17, y + 17, COL_BORDER);
        drawRect(x, y, x + 16, y + 16, COL_PANEL);

        if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
            drawRect(x, y, x + 16, y + 16, 0x80FFFFFF);
        }
    }
}