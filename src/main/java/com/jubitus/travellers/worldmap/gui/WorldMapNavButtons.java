package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.gui.GuiTravellersButton;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.function.Supplier;

/** The map's one button, Center, under the map; panning is right-drag and zoom the mouse wheel. */
@SideOnly(Side.CLIENT)
public final class WorldMapNavButtons {

    private static final int CENTER_W = 58;

    private final WorldMapController map;
    private final Supplier<BlockPos> recenterTarget;
    private final GuiButton recenter;

    public WorldMapNavButtons(WorldMapController map, List<GuiButton> buttonList, int firstId,
                              Supplier<BlockPos> recenterTarget) {
        this(map, buttonList, firstId, recenterTarget, map.mapY + map.mapH + 6);
    }

    /** Center right-aligned under the map, at an explicit Y. */
    public WorldMapNavButtons(WorldMapController map, List<GuiButton> buttonList, int firstId,
                              Supplier<BlockPos> recenterTarget, int rowTop) {
        this(map, buttonList, firstId, recenterTarget, map.mapX + map.mapW - CENTER_W, rowTop, CENTER_W);
    }

    /** Center filling [rowLeft, rowLeft + rowW). */
    public WorldMapNavButtons(WorldMapController map, List<GuiButton> buttonList, int firstId,
                              Supplier<BlockPos> recenterTarget, int rowLeft, int rowTop, int rowW) {
        this.map = map;
        this.recenterTarget = recenterTarget;
        recenter = new GuiTravellersButton(firstId, rowLeft, rowTop, rowW, 20,
                I18n.format("travellers.map.center", "Center"));
        buttonList.add(recenter);
    }

    /** Runs the button's action; false when the button is not this one. */
    public boolean handle(GuiButton button) {
        if (button != recenter) return false;
        map.setCenter(recenterTarget.get());
        return true;
    }

    public void setVisible(boolean visible) {
        recenter.visible = visible;
    }
}
