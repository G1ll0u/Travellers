package com.jubitus.travellers.client.gui.home;

import com.jubitus.travellers.client.gui.debug.ContainerNull;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.utils.net.message.home.MsgWorkerRequestHomeList;
import com.jubitus.travellers.utils.net.message.home.MsgWorkerSetHome;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerHomeList extends GuiContainer {
    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<Row> homes = new ArrayList<>();
    private GuiButton setBtn;
    private GuiButton clearBtn;
    private int listX, listY, listW, listH;
    private final int rowH = 12;
    private Integer selectedIdx = null;

    public GuiWorkerHomeList(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerNull());
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 150;
    }

    @Override
    public void initGui() {
        super.initGui();

        int shiftLeft = 40;
        this.guiLeft = Math.max(4, this.guiLeft - shiftLeft);
        this.guiTop = (this.height - this.ySize) / 2;

        this.listW = 140;
        this.listH = 120;
        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 4;

        this.buttonList.clear();

        int bw = 70, bh = 16, gap = 4;
        int buttonsTop = listY + listH + gap;

        this.setBtn = this.addButton(new GuiButton(0, listX, buttonsTop, bw, bh, "Set home"));
        this.clearBtn = this.addButton(new GuiButton(1, listX + bw + gap, buttonsTop, bw, bh, "Clear"));

        this.setBtn.enabled = false;

        homes.clear();
        selectedIdx = null;
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestHomeList(worker.getEntityId()));
        // list will arrive from server via MsgWorkerHomeList (sent in MsgOpenWorkerSubGui HOME)
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);

        drawHomeList(mouseX, mouseY);
        this.renderHoveredToolTip(mouseX, mouseY);
    }

    private void drawHomeList(int mouseX, int mouseY) {
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x80000000);

        fontRenderer.drawString("Homes", listX, listY - 10, 0xFFFFFF);

        int rows = Math.min(listH / rowH, homes.size());
        for (int i = 0; i < rows; i++) {
            int top = listY + i * rowH;
            int bot = top + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= top && mouseY < bot;
            boolean sel = (selectedIdx != null && selectedIdx == i);

            int bg = sel ? 0x4033FF33 : (hovered ? 0x20FFFFFF : 0x20000000);
            drawRect(listX, top, listX + listW, bot, bg);

            String label = homes.get(i).name;
            if (label == null || label.trim().isEmpty()) label = "(unnamed)";
            fontRenderer.drawString((sel ? "[X] " : "[ ] ") + label, listX + 3, top + 2, 0xE0E0E0);
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        // If you have no texture, you can leave it empty.
        // But at least draw something so it doesn't look broken:
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && !homes.isEmpty()) {
            if (mouseX >= listX && mouseX < listX + listW &&
                    mouseY >= listY && mouseY < listY + listH) {

                int idx = (mouseY - listY) / rowH;
                if (idx >= 0 && idx < homes.size()) {
                    selectedIdx = idx;
                    setBtn.enabled = true;
                }
            }
        }
    }

    public void receiveHomes(List<BlockPos> pos, List<String> names) {
        homes.clear();
        int n = Math.min(pos.size(), names.size());
        for (int i = 0; i < n; i++) homes.add(new Row(pos.get(i), names.get(i)));
        selectedIdx = null;
        setBtn.enabled = false;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 1) {
            selectedIdx = null;
            setBtn.enabled = false;
            return;
        }
        if (button.id == 0) {
            if (selectedIdx == null) return;
            Row r = homes.get(selectedIdx);
            TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerSetHome(worker.getEntityId(), r.pos));
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.ORDERS)
            );
            return;
        }
        super.actionPerformed(button);
    }

    private static class Row {
        final BlockPos pos;
        final String name;

        Row(BlockPos p, String n) {
            pos = p;
            name = n;
        }
    }
}
