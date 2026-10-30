package com.jubitus.travellers.village.gui;

import com.jubitus.travellers.gui.GuiMapListScreen;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.MapListLayout;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.net.MsgSetVillageEnemy;
import com.jubitus.travellers.village.net.MsgWorkerRequestVillageRelations;
import com.jubitus.travellers.worker.gui.GuiWorkerBehavior;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Edits the player's account-wide village relations. {@code worker}, when present, is only
 * used for map-centering/home-marker richness — never required for correctness. */
@SideOnly(Side.CLIENT)
public class GuiWorkerVillageRelations extends GuiMapListScreen {

    private final EntityPlayer player;
    @Nullable private final EntityTravellerWorker worker;
    @Nullable private final GuiScreen returnTo;

    private GuiButton toggleEnemy;
    private GuiButton back;

    private List<BlockPos> centers = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private List<Integer> distances = new ArrayList<>();
    private List<Boolean> enemies = new ArrayList<>();
    private int selected = -1;

    public GuiWorkerVillageRelations(EntityPlayer player, EntityTravellerWorker worker) {
        this(player, worker, null);
    }

    public GuiWorkerVillageRelations(EntityPlayer player, @Nullable EntityTravellerWorker worker, @Nullable GuiScreen returnTo) {
        this.player = player;
        this.worker = worker;
        this.returnTo = returnTo;
    }

    @Override protected String title() { return I18n.format("travellers.worker.village_relations.title", "Village relations"); }
    @Override protected String mapKey() { return "travellers_village_relations_map"; }
    @Override protected BlockPos home() { return worker != null ? worker.getPosition() : player.getPosition(); }
    @Override protected int goListWorkerId() { return worker != null ? worker.getEntityId() : -1; }
    @Override protected int rowCount() { return centers.size(); }
    @Override protected boolean isRowSelected(int idx) { return idx == selected; }

    @Override
    protected String emptyListText() {
        return I18n.format("travellers.worker.village_relations.none_known", "No known villages nearby yet.");
    }

    @Override
    protected void addControls() {
        int bw = layout.sideButtonW(2);
        back = addButton(new GuiTravellersButton(FIRST_FREE_ID, layout.sideButtonX(0, 2), layout.sideRowY(0), bw,
                MapListLayout.BTN_H, I18n.format("travellers.gui.back", "Back")));
        toggleEnemy = addButton(new GuiTravellersButton(FIRST_FREE_ID + 1, layout.sideButtonX(1, 2), layout.sideRowY(0), bw,
                MapListLayout.BTN_H, I18n.format("travellers.worker.village_relations.toggle_enemy", "Toggle enemy")));
        toggleEnemy.enabled = selected >= 0 && selected < centers.size();
    }

    @Override
    protected void onOpen() {
        TravellersNetwork.CHANNEL.sendToServer(new MsgWorkerRequestVillageRelations());
    }

    public void receiveVillageList(List<BlockPos> centers, List<String> labels,
                                   List<Integer> distances, List<Boolean> enemies) {
        this.centers   = centers  != null ? centers  : new ArrayList<>();
        this.labels    = labels   != null ? labels   : new ArrayList<>();
        this.distances = distances != null ? distances : new ArrayList<>();
        this.enemies   = enemies  != null ? enemies  : new ArrayList<>();

        if (selected >= this.centers.size()) selected = -1;
        list.clamp(this.centers.size());
        refreshSelection();
    }

    private boolean isEnemy(int idx) {
        return idx < enemies.size() && enemies.get(idx);
    }

    private void refreshSelection() {
        if (toggleEnemy != null) toggleEnemy.enabled = selected >= 0 && selected < centers.size();
        map.setSelected(selected >= 0 && selected < centers.size() ? centers.get(selected) : null);
    }

    @Override
    protected void drawRow(int idx, int x, int top, int rowW, boolean hovered, int mouseX, int mouseY) {
        int color = isEnemy(idx) ? TravellersGuiTheme.COL_CANNOT
                : (hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT);
        String label = idx < labels.size() ? labels.get(idx) : "";
        fontRenderer.drawString(fontRenderer.trimStringToWidth(label, rowW - 6), x + 3, list.textY(fontRenderer, top), color);
    }

    @Override
    protected void onRowClicked(int idx, int mouseX, int mouseY) {
        selected = idx;
        map.controller.setCenter(centers.get(idx));
        refreshSelection();
    }

    @Override
    protected boolean onAction(GuiButton button) throws IOException {
        if (button == toggleEnemy) {
            toggleAt(selected);
        } else if (button == back) {
            goBack();
        } else {
            return false;
        }
        return true;
    }

    private void toggleAt(int index) {
        if (index < 0 || index >= centers.size()) return;
        boolean newEnemy = !enemies.get(index);
        enemies.set(index, newEnemy);
        TravellersNetwork.CHANNEL.sendToServer(new MsgSetVillageEnemy(centers.get(index), newEnemy));
        // Handled after the change on the server, so the map's enemy disc follows.
        requestPlaces();
    }

    private void goBack() {
        if (returnTo != null) {
            mc.displayGuiScreen(returnTo);
        } else if (worker != null) {
            // Opened from a worker's hub (server-side, so there is no parent screen to hold).
            TravellersNetwork.CHANNEL.sendToServer(new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB));
        } else {
            mc.displayGuiScreen(new GuiWorkerBehavior(player, null, null));
        }
    }
}
