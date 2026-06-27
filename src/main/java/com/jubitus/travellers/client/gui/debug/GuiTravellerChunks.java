package com.jubitus.travellers.client.gui.debug;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.utils.debug.TravellerChunkSnapshot;
import com.jubitus.travellers.server.net.message.chunks.PacketTravellerChunksRequest;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

@SideOnly(Side.CLIENT)
public class GuiTravellerChunks extends net.minecraft.client.gui.GuiScreen {

    private TravellerChunkSnapshot snapshot;
    private int tickCounter = 0;
    private Set<ChunkPos> merchantSet;
    private Set<ChunkPos> normalSet;
    private Set<ChunkPos> virtualWorkerChunks;
    private Set<ChunkPos> virtualTravellerChunks;
    private Set<ChunkPos> virtualRaiderChunks;

    public GuiTravellerChunks(TravellerChunkSnapshot snapshot) {
        updateSnapshot(snapshot);
    }

    public void updateSnapshot(TravellerChunkSnapshot snapshot) {
        this.snapshot = snapshot;
        this.merchantSet = new HashSet<>(snapshot.merchantChunks);
        this.normalSet = new HashSet<>(snapshot.normalChunks);
        this.virtualWorkerChunks = new HashSet<>();
        for (BlockPos pos : snapshot.virtualWorkers) {
            virtualWorkerChunks.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
        this.virtualTravellerChunks = new HashSet<>();
        for (BlockPos pos : snapshot.virtualTravellers) {
            virtualTravellerChunks.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
        this.virtualRaiderChunks = new HashSet<>();
        for (BlockPos pos : snapshot.virtualRaiders) {
            virtualRaiderChunks.add(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        int gridPadding = 40;
        // Legend: 7 lines × 12px + 8px offset = 92px
        int legendReserve = 104;
        int gridSize = Math.min(this.width - gridPadding * 2, this.height - gridPadding * 2 - legendReserve);

        int gridLeft = (this.width - gridSize) / 2;
        int gridTop = (this.height - gridSize) / 2;

        int chunksDiameter = snapshot.radiusChunks * 2 + 1;
        float cellSize = (float) gridSize / (float) chunksDiameter;

        // Background
        drawRect(gridLeft, gridTop, gridLeft + gridSize, gridTop + gridSize, 0xFF202020);

        // Helper to map chunk -> screen rect
        for (int dz = -snapshot.radiusChunks; dz <= snapshot.radiusChunks; dz++) {
            for (int dx = -snapshot.radiusChunks; dx <= snapshot.radiusChunks; dx++) {
                int cx = snapshot.centerX + dx;
                int cz = snapshot.centerZ + dz;

                int cellX = gridLeft + Math.round((dx + snapshot.radiusChunks) * cellSize);
                int cellY = gridTop + Math.round((dz + snapshot.radiusChunks) * cellSize);
                int cellX2 = gridLeft + Math.round((dx + snapshot.radiusChunks + 1) * cellSize);
                int cellY2 = gridTop + Math.round((dz + snapshot.radiusChunks + 1) * cellSize);

                ChunkPos cp = new ChunkPos(cx, cz);

                int color = 0xFF303030; // unloaded

                if (normalSet.contains(cp)) {
                    color = 0xFF555555; // normal loaded
                }
                if (merchantSet.contains(cp)) {
                    color = 0xFF0066CC; // merchant forced
                }
                if (virtualWorkerChunks.contains(cp)) {
                    color = 0xFF884400; // virtual worker (orange-brown)
                }
                if (virtualTravellerChunks.contains(cp)) {
                    color = 0xFFBBAA00; // virtual traveller (yellow)
                }
                if (virtualRaiderChunks.contains(cp)) {
                    color = 0xFF880000; // virtual raider (red) — highest priority
                }

                drawRect(cellX, cellY, cellX2, cellY2, color);
            }
        }

        // Highlight center chunk (player)
        int centerIndex = snapshot.radiusChunks;
        int centerCellLeft = gridLeft + Math.round(centerIndex * cellSize);
        int centerCellTop = gridTop + Math.round(centerIndex * cellSize);
        int centerCellRight = gridLeft + Math.round((centerIndex + 1) * cellSize);
        int centerCellBottom = gridTop + Math.round((centerIndex + 1) * cellSize);

        // Thin white border
        drawHorizontalLine(centerCellLeft, centerCellRight, centerCellTop, 0xFFFFFFFF);
        drawHorizontalLine(centerCellLeft, centerCellRight, centerCellBottom, 0xFFFFFFFF);
        drawVerticalLine(centerCellLeft, centerCellTop, centerCellBottom, 0xFFFFFFFF);
        drawVerticalLine(centerCellRight, centerCellTop, centerCellBottom, 0xFFFFFFFF);

        // Draw village centers as small colored squares
        for (BlockPos village : snapshot.villages) {
            int vcx = village.getX() >> 4;
            int vcz = village.getZ() >> 4;

            int dx = vcx - snapshot.centerX;
            int dz = vcz - snapshot.centerZ;
            if (Math.abs(dx) > snapshot.radiusChunks || Math.abs(dz) > snapshot.radiusChunks) {
                continue;
            }

            int cellX = gridLeft + Math.round((dx + snapshot.radiusChunks) * cellSize);
            int cellY = gridTop + Math.round((dz + snapshot.radiusChunks) * cellSize);
            int cellX2 = gridLeft + Math.round((dx + snapshot.radiusChunks + 1) * cellSize);
            int cellY2 = gridTop + Math.round((dz + snapshot.radiusChunks + 1) * cellSize);

            int margin = Math.max(1, (int) (cellSize * 0.25f));
            int innerLeft = cellX + margin;
            int innerTop = cellY + margin;
            int innerRight = cellX2 - margin;
            int innerBottom = cellY2 - margin;

            // yellow-ish for mill
            drawRect(innerLeft, innerTop, innerRight, innerBottom, 0xFFFFCC00);
        }

        // Legend
        int legendLeft = gridLeft;
        int legendTop = gridTop + gridSize + 8;
        int boxSize = 8;
        int spacingY = 12;

        // Normal loaded
        drawRect(legendLeft, legendTop, legendLeft + boxSize, legendTop + boxSize, 0xFF555555);
        this.fontRenderer.drawString("Loaded chunks (normal)", legendLeft + boxSize + 4, legendTop + 1, 0xFFFFFFFF);

        // Merchant loaded
        legendTop += spacingY;
        drawRect(legendLeft, legendTop, legendLeft + boxSize, legendTop + boxSize, 0xFF0066CC);
        this.fontRenderer.drawString("Chunks loaded by travellers merchants", legendLeft + boxSize + 4, legendTop + 1, 0xFFFFFFFF);

        // Village
        legendTop += spacingY;
        drawRect(legendLeft, legendTop, legendLeft + boxSize, legendTop + boxSize, 0xFFFFCC00);
        this.fontRenderer.drawString("Village Town Halls", legendLeft + boxSize + 4, legendTop + 1, 0xFFFFFFFF);

        // Virtual workers
        legendTop += spacingY;
        drawRect(legendLeft, legendTop, legendLeft + boxSize, legendTop + boxSize, 0xFF884400);
        this.fontRenderer.drawString("Virtual workers (" + snapshot.virtualWorkers.size() + ")",
                legendLeft + boxSize + 4, legendTop + 1, 0xFFFFFFFF);

        // Virtual travellers
        legendTop += spacingY;
        drawRect(legendLeft, legendTop, legendLeft + boxSize, legendTop + boxSize, 0xFFBBAA00);
        this.fontRenderer.drawString("Virtual travellers (" + snapshot.virtualTravellers.size() + ")",
                legendLeft + boxSize + 4, legendTop + 1, 0xFFFFFFFF);

        // Virtual raiders
        legendTop += spacingY;
        drawRect(legendLeft, legendTop, legendLeft + boxSize, legendTop + boxSize, 0xFF880000);
        this.fontRenderer.drawString("Virtual raiders (" + snapshot.virtualRaiders.size() + ")",
                legendLeft + boxSize + 4, legendTop + 1, 0xFFFFFFFF);

        // Center
        legendTop += spacingY;
        this.fontRenderer.drawString("White square = your current chunk", legendLeft, legendTop + 1, 0xFFFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // Close on ESC / inventory
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE || this.mc.gameSettings.keyBindInventory.isActiveAndMatches(keyCode)) {
            this.mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        tickCounter++;
        if (tickCounter >= 40) { // ~1 second at 20 TPS
            tickCounter = 0;

            // Ask server for an updated snapshot, centered on the player
            Travellers.NETWORK.sendToServer(
                    new PacketTravellerChunksRequest(snapshot.radiusBlocks)
            );
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}