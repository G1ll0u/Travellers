package com.jubitus.travellers.client.gui.transport;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.server.net.message.workergo.MsgWorkerSetDestination;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.awt.*;
import java.io.IOException;
import java.util.*;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorker extends GuiContainer {
    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker.png");

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();

    private GuiButton sendButton;
    private GuiButton validateButton;
    private GuiButton donateButton;
    private GuiButton backButton;

    private int lastCost = 0;
    private boolean costValid = false;
    private int validatedVillageIdx = -1;

    private int listX, listY, listW, listH;
    private final int rowH = 12;
    private int selectedIndex = -1;
    private int scrollOffset = 0;

    public GuiWorker(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorker(player.inventory, worker));
        this.player = player;
        this.worker = worker;
        this.xSize = 176;
        this.ySize = 150;
    }

    @Override
    public void initGui() {
        super.initGui();

        this.xSize = 176;
        this.ySize = 150;

        int shiftLeft = 40;
        this.guiLeft = Math.max(4, this.guiLeft - shiftLeft);
        this.guiTop = (this.height - this.ySize) / 2;

        this.listW = 120;
        this.listH = 120;
        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 4;

        int maxRight = this.width - 8;
        if (this.listX + this.listW > maxRight) {
            this.listX = maxRight - this.listW;
        }

        this.buttonList.clear();

        int buttonWidth = 60;
        int buttonHeight = 16;
        int buttonGapX = 4;
        int buttonGapY = 4;
        int buttonsTop = listY + listH + buttonGapY;

        this.validateButton = this.addButton(new GuiButton(
                0,
                listX,
                buttonsTop,
                buttonWidth,
                buttonHeight,
                I18n.format("travellers.worker.validate", "Validate")
        ));

        this.sendButton = this.addButton(new GuiButton(
                1,
                listX + buttonWidth + buttonGapX,
                buttonsTop,
                buttonWidth,
                buttonHeight,
                I18n.format("travellers.worker.send", "Send")
        ));

        this.donateButton = this.addButton(new GuiButton(
                2,
                listX,
                buttonsTop + buttonHeight + buttonGapY,
                buttonWidth,
                buttonHeight,
                I18n.format("travellers.worker.donate", "Donate")
        ));

        this.backButton = this.addButton(new GuiButton(
                3,
                listX + buttonWidth + buttonGapX,
                buttonsTop + buttonHeight + buttonGapY,
                buttonWidth,
                buttonHeight,
                I18n.format("travellers.gui.back", "Back")
        ));

        villages.clear();
        scrollOffset = 0;
        selectedIndex = -1;
        costValid = false;
        validatedVillageIdx = -1;
        lastCost = 0;

        this.sendButton.enabled = false;
        this.donateButton.enabled = false;
        this.validateButton.enabled = true;

        boolean hired = worker.isHiredFollower();
        UUID ownerId = worker.getFollowOwnerId();
        boolean hiredByMe = hired && ownerId != null && ownerId.equals(player.getUniqueID());

        if (hiredByMe) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgWorkerRequestVillages(worker.getEntityId())
            );
        } else {
            this.sendButton.enabled = false;
            this.donateButton.enabled = false;
            this.validateButton.enabled = false;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);

        handleListWheelScroll(mouseX, mouseY, villages.size());

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        drawVillageList(mouseX, mouseY);
        this.renderHoveredToolTip(mouseX, mouseY);
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.title", "Worker"),
                8, 6, 0x404040
        );

        this.fontRenderer.drawString(
                this.player.inventory.getDisplayName().getUnformattedText(),
                8, this.ySize - 96 + 2,
                0x404040
        );
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        this.mc.getTextureManager().bindTexture(TEX);
        int x = this.guiLeft;
        int y = this.guiTop;
        this.drawTexturedModalRect(x, y, 0, 0, this.xSize, this.ySize);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && !villages.isEmpty()) {
            if (mouseX >= listX && mouseX < listX + listW &&
                    mouseY >= listY && mouseY < listY + listH) {

                int idx = scrollOffset + (mouseY - listY) / rowH;
                if (idx >= 0 && idx < villages.size()) {
                    selectedIndex = idx;
                    costValid = false;
                    validatedVillageIdx = -1;
                    lastCost = 0;
                    if (sendButton != null) sendButton.enabled = false;
                    if (donateButton != null) donateButton.enabled = false;
                }
            }
        }
    }

    private void drawVillageList(int mouseX, int mouseY) {
        if (villages.isEmpty()) {
            this.fontRenderer.drawString("No millage in range...", listX, listY, 0xFFFFFF);

            String costLabel;
            int color;

            if (!costValid || selectedIndex < 0 || selectedIndex >= villages.size()
                    || validatedVillageIdx != selectedIndex) {
                costLabel = I18n.format("travellers.worker.cost_unknown");
                color = 0xAAAAAA;
            } else {
                String costStr = MillCommonUtilities.getShortPrice(lastCost);
                costLabel = I18n.format("travellers.worker.cost", costStr);

                int playerMoney = MillCommonUtilities.countMoney(this.player.inventory);
                color = playerMoney >= lastCost ? 0x00FF00 : 0xFF5555;
            }

            this.fontRenderer.drawString(costLabel, listX, listY - 10, color);
            return;
        }

        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x80000000);

        final float scale = 0.85F;

        clampScroll();

        int maxRows = getVisibleRows();
        int rowsToDraw = Math.min(maxRows, villages.size() - scrollOffset);

        for (int i = 0; i < rowsToDraw; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;

            int globalIdx = scrollOffset + i;

            int bg = (globalIdx == selectedIndex) ? 0x40FFFFFF
                    : hovered ? 0x20FFFFFF : 0x20000000;

            drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            VillageRow row = villages.get(globalIdx);
            String label = makeRowLabel(row, scale);
            int color = hovered ? 0xFFFFA0 : 0xE0E0E0;

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString(label, 0, 0, color);
            GlStateManager.popMatrix();
        }

        int usedSlots = getUsedCargoSlotsClient();
        int cost = 0;
        if (selectedIndex >= 0 && selectedIndex < villages.size()) {
            VillageRow selected = villages.get(selectedIndex);
            cost = EntityTravellerWorker.computeTravelCost(selected.distance, usedSlots);
        }

        String costStr = MillCommonUtilities.getShortPrice(cost);
        String costLabel = I18n.format("travellers.worker.cost", costStr);
        this.fontRenderer.drawString(costLabel, listX, listY - 10, 0xFFFFFF);
    }

    private String makeRowLabel(VillageRow row, float scale) {
        String full = row.display;
        String distSuffix = " (" + row.distance + "m)";

        String baseName = full;
        int idx = full.lastIndexOf(" (");
        if (idx > 0 && full.endsWith("m)")) {
            baseName = full.substring(0, idx);
        }

        int maxUnscaledWidth = (int) ((this.listW - 6) / scale);
        return shortenNameWithSuffix(baseName, distSuffix, maxUnscaledWidth);
    }

    private String shortenNameWithSuffix(String name, String suffix, int maxWidth) {
        int fullWidth = this.fontRenderer.getStringWidth(name + suffix);
        if (fullWidth <= maxWidth) {
            return name + suffix;
        }

        int suffixWidth = this.fontRenderer.getStringWidth(suffix);
        if (suffixWidth >= maxWidth) {
            return this.fontRenderer.trimStringToWidth(suffix, maxWidth);
        }

        int remaining = maxWidth - suffixWidth;
        int nameWidth = this.fontRenderer.getStringWidth(name);
        if (nameWidth <= remaining) {
            return name + suffix;
        }

        String ellipsis = "...";
        int ellWidth = this.fontRenderer.getStringWidth(ellipsis);
        if (ellWidth >= remaining) {
            return suffix;
        }

        int half = (remaining - ellWidth) / 2;

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            String candidate = prefix.toString() + name.charAt(i);
            if (this.fontRenderer.getStringWidth(candidate) > half) {
                break;
            }
            prefix.append(name.charAt(i));
        }

        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            int w = this.fontRenderer.getStringWidth(prefix + ellipsis + candidate);
            if (w > remaining) {
                break;
            }
            tail.insert(0, name.charAt(i));
        }

        return prefix + ellipsis + tail + suffix;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == backButton) {
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB)
            );
            return;
        }

        if (button == sendButton) {
            if (sendButton.enabled && selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerSetDestination(
                        worker.getEntityId(),
                        row.center,
                        row.display,
                        false
                ));
                mc.player.closeScreen();
            }
            return;
        }

        if (button == donateButton) {
            if (donateButton.enabled && selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerSetDestination(
                        worker.getEntityId(),
                        row.center,
                        row.display,
                        true
                ));
                mc.player.closeScreen();
            }
            return;
        }

        if (button == validateButton) {
            if (selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                int usedSlots = getUsedCargoSlotsClient();
                int cost = EntityTravellerWorker.computeTravelCost(row.distance, usedSlots);

                this.lastCost = cost;
                this.validatedVillageIdx = selectedIndex;
                this.costValid = true;

                int playerMoney = MillCommonUtilities.countMoney(this.player.inventory);
                boolean canAfford = cost == 0 || playerMoney >= cost;
                this.sendButton.enabled = canAfford;
                this.donateButton.enabled = canAfford;
            } else {
                this.costValid = false;
                this.validatedVillageIdx = -1;
                this.lastCost = 0;
                this.sendButton.enabled = false;
                this.donateButton.enabled = false;
            }
            return;
        }

        super.actionPerformed(button);
    }

    private int getUsedCargoSlotsClient() {
        int used = 0;
        int workerSlots = 18;

        for (int i = 0; i < workerSlots && i < this.inventorySlots.inventorySlots.size(); i++) {
            Slot s = this.inventorySlots.getSlot(i);
            if (s != null && s.getHasStack()) {
                used++;
            }
        }
        return used;
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        this.villages.clear();

        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) {
            this.villages.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        }

        this.villages.sort(Comparator.comparingInt(v -> v.distance));

        if (villages.isEmpty()) {
            this.selectedIndex = -1;
            if (sendButton != null) sendButton.enabled = false;
            if (donateButton != null) donateButton.enabled = false;
        } else {
            this.selectedIndex = 0;
            if (sendButton != null) sendButton.enabled = false;
            if (donateButton != null) donateButton.enabled = false;
        }

        costValid = false;
        validatedVillageIdx = -1;
        lastCost = 0;
        scrollOffset = 0;
        clampScroll();
    }

    private static class VillageRow {
        final BlockPos center;
        final String display;
        final int distance;

        VillageRow(BlockPos center, String display, int distance) {
            this.center = center;
            this.display = display;
            this.distance = distance;
        }
    }

    private boolean isMouseOverList(int mouseX, int mouseY) {
        return mouseX >= listX && mouseX < listX + listW
                && mouseY >= listY && mouseY < listY + listH;
    }

    private int getVisibleRows() {
        return Math.max(1, listH / rowH);
    }

    private int getMaxScroll() {
        return Math.max(0, villages.size() - getVisibleRows());
    }

    private void clampScroll() {
        if (scrollOffset < 0) scrollOffset = 0;
        int max = getMaxScroll();
        if (scrollOffset > max) scrollOffset = max;
    }

    private void handleListWheelScroll(int mouseX, int mouseY, int listSize) {
        if (listSize <= 0) return;
        if (!isMouseOverList(mouseX, mouseY)) return;

        int wheel = org.lwjgl.input.Mouse.getDWheel();
        if (wheel == 0) return;

        int dir = (wheel > 0) ? -1 : 1;
        scrollOffset += dir;
        if (scrollOffset < 0) scrollOffset = 0;

        int visible = Math.max(1, listH / rowH);
        int maxScroll = Math.max(0, listSize - visible);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }
    public Rectangle getJeiListArea() {
        return new Rectangle(
                this.listX - 2,
                this.listY - 12,
                this.listW + 4,
                this.listH + 38
        );
    }
}