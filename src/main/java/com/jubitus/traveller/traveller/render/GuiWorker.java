package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.entityAI.EntityTravellerWorker;
import com.jubitus.traveller.traveller.utils.debug.ContainerWorker;
import com.jubitus.traveller.traveller.utils.debug.MsgWorkerRequestVillages;
import com.jubitus.traveller.traveller.utils.debug.MsgWorkerSetDestination;
import com.jubitus.traveller.traveller.utils.debug.TravellerFollowNet;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.*;

@SideOnly(Side.CLIENT)
public class GuiWorker extends GuiContainer {
    // list box area (outside GUI, to the right)
    private int listX, listY, listW, listH;
    private int rowH = 12;

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    private int selectedIndex = -1;

    private int entryHeight = 14;
    private int visibleEntries = 6;
    private final java.util.List<VillageRow> villages = new java.util.ArrayList<>();
    private int selectedVillageIdx = -1;



    // buttons
    private GuiButton sendButton;
    private static final ResourceLocation TEX =
            new ResourceLocation("travellers", "textures/gui/worker.png");
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

        // How far left you want to shove the central GUI (tweak to taste)
        int shiftLeft = 40;

        // Move the vanilla GUI left, but don't let it go off-screen
        this.guiLeft = Math.max(4, this.guiLeft - shiftLeft);
        this.guiTop  = (this.height - this.ySize) / 2;

        // Recalculate list area to the right of the shifted GUI
        this.listW = 120;
        this.listH = 120;
        this.listX = this.guiLeft + this.xSize + 8;
        this.listY = this.guiTop + 18;

        // Clamp list so it still doesn't go off the right edge
        int maxRight = this.width - 8;
        if (this.listX + this.listW > maxRight) {
            this.listX = maxRight - this.listW;
        }

        // Recreate / move the send button
        this.buttonList.clear();
        this.sendButton = this.addButton(new GuiButton(
                0,
                listX,
                listY + listH + 4,
                80,
                20,
                I18n.format("travellers.worker.send", "Send")
        ));
        this.sendButton.enabled = false;

        villages.clear();
        selectedIndex = -1;

        TravellerFollowNet.CHANNEL.sendToServer(
                new MsgWorkerRequestVillages(worker.getEntityId())
        );
    }



    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == sendButton) {
            if (selectedIndex >= 0 && selectedIndex < villages.size()) {
                VillageRow row = villages.get(selectedIndex);
                TravellerFollowNet.CHANNEL.sendToServer(new MsgWorkerSetDestination(
                        worker.getEntityId(),
                        row.center,
                        row.display       // used in chat on server
                ));
                mc.player.closeScreen();
            }
        } else {
            super.actionPerformed(button);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (mouseButton == 0 && !villages.isEmpty()) {
            if (mouseX >= listX && mouseX < listX + listW &&
                    mouseY >= listY && mouseY < listY + listH) {

                int idx = (mouseY - listY) / rowH;
                if (idx >= 0 && idx < villages.size()) {
                    selectedIndex = idx;
                    if (sendButton != null) {
                        sendButton.enabled = true;
                    }
                }
            }
        }
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
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // Title
        this.fontRenderer.drawString(
                I18n.format("travellers.worker.title", "Worker"),
                8, 6, 0x404040);

        // Player inventory title
        this.fontRenderer.drawString(
                this.player.inventory.getDisplayName().getUnformattedText(),
                8, this.ySize - 96 + 2,
                0x404040
        );

        // NOTE:
        // Do NOT draw village list here anymore!
        // That is now handled entirely by drawVillageList() in drawScreen().
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

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);
        this.renderHoveredToolTip(mouseX, mouseY);
        drawVillageList(mouseX, mouseY);
    }

    private void drawVillageList(int mouseX, int mouseY) {
        if (villages.isEmpty()) {
            this.fontRenderer.drawString(
                    "No villages in range.",
                    listX, listY,
                    0xFFFFFF);
            return;
        }

        // Background panel
        drawRect(listX - 2, listY - 2,
                listX + listW + 2, listY + listH + 2,
                0x80000000);

        int maxRows = listH / rowH;
        int rows = Math.min(maxRows, villages.size());

        // Font scale for smaller text
        final float scale = 0.85F;

        for (int i = 0; i < rows; i++) {
            int rowTop = listY + i * rowH;
            int rowBottom = rowTop + rowH;

            boolean hovered = mouseX >= listX && mouseX < listX + listW
                    && mouseY >= rowTop && mouseY < rowBottom;

            int globalIdx = i; // still no scrolling, one-to-one

            int bg =
                    (globalIdx == selectedIndex) ? 0x40FFFFFF :
                            hovered ? 0x20FFFFFF : 0x20000000;

            drawRect(listX, rowTop, listX + listW, rowBottom, bg);

            VillageRow row = villages.get(globalIdx);

            // Build shortened label that fits our width with scaling applied
            String label = makeRowLabel(row, scale);

            int color = hovered ? 0xFFFFA0 : 0xE0E0E0;

            // Draw with smaller font
            GlStateManager.pushMatrix();
            GlStateManager.translate(listX + 3, rowTop + 2, 0.0F);
            GlStateManager.scale(scale, scale, 1.0F);
            this.fontRenderer.drawString(label, 0, 0, color);
            GlStateManager.popMatrix();
        }
    }

// inside GuiWorker

    private void refreshVillageList() {
        villages.clear();

        World world = worker.world;
        if (world == null) {
            selectedIndex = -1;
            if (sendButton != null) sendButton.enabled = false;
            return;
        }

        BlockPos workerPos = worker.getPosition();
        int maxRadius = TravellersModConfig.workerVillageDetectRadius;
        if (maxRadius <= 0) {
            maxRadius = 2000; // sane fallback
        }

        // 1) Prefer proper village info with names (MillVillageIndex)
        Map<MillVillageIndex.VillageInfo, Integer> pops = MillVillageIndex.countVillagersPerVillage(world);

        if (!pops.isEmpty()) {
            for (Map.Entry<MillVillageIndex.VillageInfo, Integer> e : pops.entrySet()) {
                MillVillageIndex.VillageInfo vi = e.getKey();
                BlockPos center = vi.center;

                // 2D distance is usually what you want for “how far is that village”
                double dx = center.getX() + 0.5D - workerPos.getX();
                double dz = center.getZ() + 0.5D - workerPos.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

                if (dist > maxRadius) {
                    continue;
                }

                String name = vi.name;
                if (name == null || name.isEmpty()) {
                    // Fallback: approximate name from world data
                    name = MillVillageIndex.nameForApprox(world, center);
                }

                // Final display string: e.g. "Norman Village (742m)"
                String display = String.format("%s (%dm)", name, dist);

                villages.add(new VillageRow(center, display, dist));
            }
        }

        // 2) Fallback: if for some reason pops is empty, use raw centers
        if (villages.isEmpty()) {
            java.util.List<BlockPos> centers = MillVillageIndex.getAllVillageCenters(world);
            for (BlockPos center : centers) {
                double dx = center.getX() + 0.5D - workerPos.getX();
                double dz = center.getZ() + 0.5D - workerPos.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

                if (dist > maxRadius) {
                    continue;
                }

                String name = MillVillageIndex.nameForApprox(world, center);
                String display = String.format("%s (%dm)", name, dist);

                villages.add(new VillageRow(center, display, dist));
            }
        }

        // 3) Sort by distance and update selection + button
        villages.sort(java.util.Comparator.comparingInt(v -> v.distance));

        if (villages.isEmpty()) {
            selectedIndex = -1;
            if (sendButton != null) sendButton.enabled = false;
            // Optional debug:
            // System.out.println("[Travellers] Worker GUI: no villages within " + maxRadius + " blocks");
        } else {
            selectedIndex = 0;
            if (sendButton != null) sendButton.enabled = true;
        }
    }
    // Called from the network handler on the CLIENT thread
    public void receiveVillageList(List<BlockPos> centers,
                                   List<String> labels,
                                   List<Integer> distances) {

        this.villages.clear();

        int n = Math.min(centers.size(), Math.min(labels.size(), distances.size()));
        for (int i = 0; i < n; i++) {
            this.villages.add(new VillageRow(centers.get(i), labels.get(i), distances.get(i)));
        }

        // Already sorted by server, but sort here again just in case
        this.villages.sort(java.util.Comparator.comparingInt(v -> v.distance));

        if (villages.isEmpty()) {
            this.selectedIndex = -1;
            if (sendButton != null) sendButton.enabled = false;
        } else {
            this.selectedIndex = 0;
            if (sendButton != null) sendButton.enabled = true;
        }
    }
    /**
     * Builds a visually-safe label that:
     *  - keeps the distance suffix, e.g. " (192m)"
     *  - shortens the name with a middle "..." if needed
     *  - respects the available list width and the font scale
     */
    private String makeRowLabel(VillageRow row, float scale) {
        // Original label currently is something like "Name (192m)"
        String full = row.display;

        // Distance suffix we *must* keep
        String distSuffix = " (" + row.distance + "m)";

        // Try to split: base name vs " (xyzm)"
        String baseName = full;
        int idx = full.lastIndexOf(" (");
        if (idx > 0 && full.endsWith("m)")) {
            baseName = full.substring(0, idx);
        }

        // Our width budget in *unscaled* font units
        int maxUnscaledWidth = (int) ((this.listW - 6) / scale);

        return shortenNameWithSuffix(baseName, distSuffix, maxUnscaledWidth);
    }

    /**
     * Shortens "name" so that name+suffix fits within maxWidth in *font* units.
     * Uses a middle ellipsis, e.g. "Campig...Mines".
     */
    private String shortenNameWithSuffix(String name, String suffix, int maxWidth) {
        int fullWidth = this.fontRenderer.getStringWidth(name + suffix);
        if (fullWidth <= maxWidth) {
            return name + suffix;
        }

        int suffixWidth = this.fontRenderer.getStringWidth(suffix);
        if (suffixWidth >= maxWidth) {
            // No room for the full suffix; last resort: trim only the suffix
            return this.fontRenderer.trimStringToWidth(suffix, maxWidth);
        }

        int remaining = maxWidth - suffixWidth;

        int nameWidth = this.fontRenderer.getStringWidth(name);
        if (nameWidth <= remaining) {
            // Name fits but total didn't earlier; just to be safe
            return name + suffix;
        }

        // Now we genuinely need a middle ellipsis
        String ellipsis = "...";
        int ellWidth = this.fontRenderer.getStringWidth(ellipsis);

        if (ellWidth >= remaining) {
            // No room for name at all; show only suffix
            return suffix;
        }

        // We'll give roughly half width to prefix and half to suffix-part of name
        int half = (remaining - ellWidth) / 2;

        // Build prefix from the left
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            String candidate = prefix.toString() + name.charAt(i);
            if (this.fontRenderer.getStringWidth(candidate) > half) {
                break;
            }
            prefix.append(name.charAt(i));
        }

        // Build tail from the right
        StringBuilder tail = new StringBuilder();
        for (int i = name.length() - 1; i >= 0; i--) {
            String candidate = name.charAt(i) + tail.toString();
            int w = this.fontRenderer.getStringWidth(prefix.toString() + ellipsis + candidate);
            if (w > remaining) {
                break;
            }
            tail.insert(0, name.charAt(i));
        }

        String shortenedName = prefix.toString() + ellipsis + tail.toString();
        return shortenedName + suffix;
    }

}