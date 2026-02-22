package com.jubitus.travellers.client.gui.recruit;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.jubitus.travellers.utils.net.message.recruit.MsgRecruitStartSelectedVillages;
import com.jubitus.travellers.utils.net.message.villageshomes.MsgWorkerRequestVillages;
import com.jubitus.travellers.utils.net.message.workerplayer.MsgRequestServerCosts;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SideOnly(Side.CLIENT)
public class GuiWorkerRecruit extends GuiContainer {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;
    private final List<VillageRow> villages = new ArrayList<>();
    private final java.util.Set<Integer> selected = new java.util.HashSet<>();
    private GuiButton startButton;
    private GuiButton clearButton;
    private int listX, listY, listW, listH;
    private final int rowH = 12;
    private int lastFee = 0;

    // --- SCROLLING ---
    private int scrollOffset = 0;

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


    public GuiWorkerRecruit(EntityPlayer player, EntityTravellerWorker worker) {
        super(new ContainerWorkerRecruit(player.inventory, worker));
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

        this.startButton = this.addButton(new GuiButton(0, listX, buttonsTop, bw, bh, "Recruit"));
        this.clearButton = this.addButton(new GuiButton(1, listX + bw + gap, buttonsTop, bw, bh, "Clear"));

        this.startButton.enabled = false;

        villages.clear();
        selected.clear();
        scrollOffset = 0;

        recomputeFee();

        // reuse existing request
        TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerRequestVillages(worker.getEntityId()));
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestServerCosts());

    }

    private void recomputeFee() {
        if (selected.isEmpty()) {
            lastFee = 0;
            startButton.enabled = false;
            return;
        }

        java.util.List<Integer> sel = new java.util.ArrayList<>(selected);
        java.util.Collections.sort(sel);

        java.util.List<BlockPos> route = new java.util.ArrayList<>();
        for (int idx : sel) route.add(villages.get(idx).center);

        // start/end at player pos (simple + feels right)
        BlockPos start = player.getPosition();
        BlockPos end = player.getPosition();

        double perBlock = TravellersModConfig.recruitFeePerBlock_serverCache;
        int flat = TravellersModConfig.recruitFeeFlat_serverCache;
        int perVillage = TravellersModConfig.recruitFeePerVillage_serverCache;

// si pas encore reçu -> fallback visuel (ou utilise tes locals, mais ça peut être faux en dédié)
        boolean hasServer = flat >= 0 && perVillage >= 0 && !Double.isNaN(perBlock);

        int dist = EntityTravellerWorker.computeRouteDistance2D(start, route, end);

        if (hasServer) {
            int fee = Math.max(0, flat + (perVillage * route.size()) + (int) Math.ceil(dist * perBlock));
            lastFee = fee;
        } else {
            // fallback (peut être faux sur dédié, mais évite 0)
            lastFee = EntityTravellerWorker.computeRecruitFee(dist, route.size());
        }


        int money = MillCommonUtilities.countMoney(player.inventory);
        startButton.enabled = (money >= lastFee);
    }

    private void handleListWheelScroll(int mouseX, int mouseY, int listSize) {
        if (listSize <= 0) return;

        // Only scroll if the mouse is over the list panel
        if (mouseX < listX || mouseX >= listX + listW || mouseY < listY || mouseY >= listY + listH) {
            return;
        }

        int wheel = org.lwjgl.input.Mouse.getDWheel();
        if (wheel == 0) return;

        int dir = (wheel > 0) ? -1 : 1; // up = -1, down = +1

        scrollOffset += dir;
        if (scrollOffset < 0) scrollOffset = 0;

        int visible = Math.max(1, listH / rowH);
        int maxScroll = Math.max(0, listSize - visible);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }



    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        handleListWheelScroll(mouseX, mouseY, villages.size());

        GlStateManager.disableLighting();
        GlStateManager.disableDepth();

        drawVillageList(mouseX, mouseY);
        this.renderHoveredToolTip(mouseX, mouseY);

    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {

    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && !villages.isEmpty()) {
            if (mouseX >= listX && mouseX < listX + listW &&
                    mouseY >= listY && mouseY < listY + listH) {

                int idx = scrollOffset + (mouseY - listY) / rowH;

                if (idx >= 0 && idx < villages.size()) {
                    int minD = TravellersModConfig.recruitMinVillageDistance;
                    int dist = villages.get(idx).distance;

                    if (dist < minD) {
                        // too close, ignore click
                        player.sendMessage(new TextComponentString(
                                TextFormatting.RED + "Too close to recruit. Minimum distance: " + minD + "m"
                        ));
                        return;
                    }

                    if (selected.contains(idx)) selected.remove(idx);
                    else selected.add(idx);

                    recomputeFee();

                }
            }
        }
    }

    private void drawVillageList(int mouseX, int mouseY) {
        drawRect(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0x80000000);

        int money = MillCommonUtilities.countMoney(player.inventory);
        String feeStr = MillCommonUtilities.getShortPrice(lastFee);
        int col = (money >= lastFee) ? 0x00FF00 : 0xFF5555;
        this.fontRenderer.drawString("Fee: " + feeStr, listX, listY - 10, col);

        clampScroll();

        int rowsToDraw = Math.min(getVisibleRows(), villages.size() - scrollOffset);
        for (int i = 0; i < rowsToDraw; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;

            int globalIdx = scrollOffset + i;

            boolean isSel = selected.contains(globalIdx);

            int bg = isSel ? 0x4033FF33 : (hovered ? 0x20FFFFFF : 0x20000000);
            drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            VillageRow row = villages.get(globalIdx);
            final float scale = 0.85F;

            String label = makeRowLabel(row, scale);

            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString((isSel ? "[X] " : "[ ] ") + label, 0, 0, 0xE0E0E0);
            GlStateManager.popMatrix();
        }

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
        if (fullWidth <= maxWidth) return name + suffix;

        int suffixWidth = this.fontRenderer.getStringWidth(suffix);
        if (suffixWidth >= maxWidth) return this.fontRenderer.trimStringToWidth(suffix, maxWidth);

        int remaining = maxWidth - suffixWidth;

        int nameWidth = this.fontRenderer.getStringWidth(name);
        if (nameWidth <= remaining) return name + suffix;

        String ellipsis = "...";
        int ellWidth = this.fontRenderer.getStringWidth(ellipsis);
        if (ellWidth >= remaining) return suffix;

        int half = (remaining - ellWidth) / 2;

        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            String candidate = prefix.toString() + name.charAt(i);
            if (this.fontRenderer.getStringWidth(candidate) > half) break;
            prefix.append(name.charAt(i));
        }

        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            int w = this.fontRenderer.getStringWidth(prefix + ellipsis + candidate);
            if (w > remaining) break;
            tail.insert(0, name.charAt(i));
        }

        return prefix + ellipsis + tail + suffix;
    }

    // called by MsgWorkerVillageList
    public void receiveVillageList(List<BlockPos> centers, List<String> labels, List<Integer> distances) {
        villages.clear();
        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) {
            villages.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        }
        // already sorted server-side; if not, keep safe:
        villages.sort(java.util.Comparator.comparingInt(v -> v.distance));

        selected.clear();
        recomputeFee();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == clearButton) {
            selected.clear();
            recomputeFee();
            return;
        }

        if (button == startButton) {
            if (selected.isEmpty()) return;

            // AUTO-SORTED: pick selected indices in ascending order
            java.util.List<Integer> sel = new java.util.ArrayList<>(selected);
            java.util.Collections.sort(sel);

            java.util.List<BlockPos> route = new java.util.ArrayList<>();
            for (int idx : sel) {
                if (idx >= 0 && idx < villages.size()) route.add(villages.get(idx).center);
            }

            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgRecruitStartSelectedVillages(worker.getEntityId(), route)
            );
            mc.player.closeScreen();
            return;
        }

        super.actionPerformed(button);
    }

    private static class VillageRow {
        final BlockPos center;
        final String display;
        final int distance;

        VillageRow(BlockPos c, String d, int dist) {
            center = c;
            display = d;
            distance = dist;
        }
    }

}
