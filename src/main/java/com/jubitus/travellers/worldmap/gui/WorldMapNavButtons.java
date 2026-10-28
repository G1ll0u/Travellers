package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/** The four pan arrows on the map edges, plus zoom -/+ and Center under the map. */
@SideOnly(Side.CLIENT)
public final class WorldMapNavButtons {

    private final WorldMapController map;
    private final Supplier<BlockPos> recenterTarget;
    private final GuiButton panW, panE, panN, panS, zoomOut, zoomIn, recenter;

    /** Adds seven buttons to the screen's list, with ids firstId..firstId+6. */
    public WorldMapNavButtons(WorldMapController map, List<GuiButton> buttonList, int firstId,
                              Supplier<BlockPos> recenterTarget) {
        this(map, buttonList, firstId, recenterTarget, map.mapY + map.mapH + 6);
    }

    /** Same, with the zoom / Center row at an explicit Y. */
    public WorldMapNavButtons(WorldMapController map, List<GuiButton> buttonList, int firstId,
                              Supplier<BlockPos> recenterTarget, int rowTop) {
        this.map = map;
        this.recenterTarget = recenterTarget;
        int x = map.mapX, y = map.mapY, w = map.mapW, h = map.mapH;
        int aw = 16, ah = 16;
        int midY = y + h / 2 - ah / 2;
        int midX = x + w / 2 - aw / 2;
        panW = new GuiTravellersButton(firstId, x + 2, midY, aw, ah, "<");
        panE = new GuiTravellersButton(firstId + 1, x + w - aw - 2, midY, aw, ah, ">");
        panN = new GuiTravellersButton(firstId + 2, midX, y + 2, aw, ah, "^");
        panS = new GuiTravellersButton(firstId + 3, midX, y + h - ah - 2, aw, ah, "v");
        int zTop = rowTop;
        zoomOut = new GuiTravellersButton(firstId + 4, x, zTop, 20, 20, "-");
        zoomIn = new GuiTravellersButton(firstId + 5, x + 24, zTop, 20, 20, "+");
        recenter = new GuiTravellersButton(firstId + 6, x + w - 58, zTop, 58, 20,
                I18n.format("travellers.map.center", "Center"));
        buttonList.addAll(all());
    }

    /** Runs the button's action; false when the button is not one of these. */
    public boolean handle(GuiButton button) {
        if (button == panN) map.panStep(0, -1);
        else if (button == panS) map.panStep(0, 1);
        else if (button == panW) map.panStep(-1, 0);
        else if (button == panE) map.panStep(1, 0);
        else if (button == zoomOut) map.zoomStep(-1);
        else if (button == zoomIn) map.zoomStep(1);
        else if (button == recenter) map.setCenter(recenterTarget.get());
        else return false;
        return true;
    }

    public void setVisible(boolean visible) {
        for (GuiButton b : all()) b.visible = visible;
    }

    private List<GuiButton> all() {
        return Arrays.asList(panW, panE, panN, panS, zoomOut, zoomIn, recenter);
    }
}
