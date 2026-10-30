package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.follow.gui.ZoneTooltip;
import com.jubitus.travellers.village.gui.SettlementTooltip;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The map every worker-related screen embeds: live feed, markers, hover and selection highlight,
 * tooltips, mouse handling and the zoom / Center buttons. A screen only places it and says what is selected.
 */
@SideOnly(Side.CLIENT)
public final class WorkerMapView {

    public final WorkerMapScreenSupport data;
    public final WorldMapController controller;

    private WorldMapNavButtons nav;
    private Set<BlockPos> selected = Collections.emptySet();
    private boolean hoverHighlight = true;
    @Nullable private BlockPos destination;
    @Nullable private Consumer<GuiWorldMapPanel> extraMarkers;

    /** The map's inner rectangle (inside the box the screen draws around it). */
    public WorkerMapView(int x, int y, int w, int h, String textureKey, BlockPos center) {
        this(new WorkerMapScreenSupport(), x, y, w, h, textureKey, center);
    }

    /** Same, over feed data the screen keeps across a re-layout. */
    public WorkerMapView(WorkerMapScreenSupport data, int x, int y, int w, int h, String textureKey, BlockPos center) {
        this.data = data;
        controller = new WorldMapController(net.minecraft.client.Minecraft.getMinecraft(), x, y, w, h, textureKey, center);
    }

    /** Adds the pan / zoom / Center buttons; the zoom row sits at rowTop. */
    public void addNav(List<GuiButton> buttons, int firstId, Supplier<BlockPos> home, int rowTop) {
        nav = new WorldMapNavButtons(controller, buttons, firstId, home, rowTop);
    }

    public boolean handleButton(GuiButton button) {
        return nav != null && nav.handle(button);
    }

    public void setNavVisible(boolean visible) {
        if (nav != null) nav.setVisible(visible);
    }

    public void subscribe() {
        data.subscribe();
    }

    public void unsubscribe() {
        data.unsubscribe();
    }

    /** What the screen has selected (drawn like a hovered marker). */
    public void setSelected(@Nullable Set<BlockPos> positions) {
        selected = positions != null ? positions : Collections.emptySet();
    }

    public void setSelected(@Nullable BlockPos pos) {
        setSelected(pos != null ? Collections.singleton(pos) : null);
    }

    /** Off for a screen that highlights by its own rule (the Formation Hub). */
    public void setHoverHighlight(boolean on) {
        hoverHighlight = on;
    }

    /** A red cross on a free spot the player picked on the map. */
    public void setDestination(@Nullable BlockPos pos) {
        destination = pos;
    }

    /** Screen-specific markers drawn over the standard ones (a planned route, a group's selection). */
    public void setExtraMarkers(@Nullable Consumer<GuiWorldMapPanel> extra) {
        extraMarkers = extra;
    }

    /** Re-places every marker (they glide between server updates) and draws the map. */
    public void draw(EntityPlayer player, int ownerColor, int mouseX, int mouseY) {
        data.populate(controller, player, ownerColor);
        if (extraMarkers != null) extraMarkers.accept(controller.panel);
        if (destination != null) controller.panel.addMarker(WorldMapMarkerFactory.destinationCross(destination));
        Set<BlockPos> lit = selected;
        if (hoverHighlight) {
            GuiWorldMapPanel.Marker hovered = controller.hoveredMarker(mouseX, mouseY);
            if (hovered != null) {
                lit = new HashSet<>(selected);
                lit.add(hovered.pos);
            }
        }
        controller.panel.setHighlighted(lit);
        controller.draw();
    }

    public void drawTooltip(GuiScreen screen, int mouseX, int mouseY) {
        net.minecraft.client.gui.FontRenderer fr = net.minecraft.client.Minecraft.getMinecraft().fontRenderer;
        MsgWorkerGoList.Entry place = placeUnder(mouseX, mouseY);
        if (place != null && place.zone != null) {
            ZoneTooltip.draw(fr, place.zone, mouseX, mouseY, screen.width, screen.height);
        } else if (place != null && place.settlement != null) {
            SettlementTooltip.draw(fr, place.label, place.settlement, mouseX, mouseY, screen.width, screen.height);
        } else {
            controller.drawTooltip(screen, mouseX, mouseY);
        }
    }

    /** The place (settlement, Barrack, Stable) whose own marker is hovered; a screen's extra marker on it keeps its label. */
    @Nullable
    public MsgWorkerGoList.Entry placeUnder(int mouseX, int mouseY) {
        GuiWorldMapPanel.Marker m = controller.hoveredMarker(mouseX, mouseY);
        if (m == null || m.unitId != null) return null;
        for (MsgWorkerGoList.Entry e : data.places) {
            if (m.pos.equals(e.pos) && e.label.equals(m.label) && WorldMapContentPopulator.isShown(e)) return e;
        }
        return null;
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return controller.isMouseOver(mouseX, mouseY);
    }

    public void wheel(int mouseX, int mouseY, int wheel) {
        controller.handleWheel(mouseX, mouseY, wheel);
    }

    /** Right button starts a pan; true when the click was consumed. */
    public boolean mouseDown(int mouseX, int mouseY, int button) {
        return controller.onMouseClick(mouseX, mouseY, button);
    }

    public void mouseDrag(int mouseX, int mouseY) {
        controller.onMouseDrag(mouseX, mouseY);
    }

    public void mouseUp(int state) {
        controller.onMouseRelease(state);
    }
}
