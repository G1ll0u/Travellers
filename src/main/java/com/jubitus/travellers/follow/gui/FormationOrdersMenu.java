package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.unit.equipment.EquipTargetKind;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.MsgGroupCreate;
import com.jubitus.travellers.follow.net.MsgGroupFollow;
import com.jubitus.travellers.follow.net.MsgGroupMerge;
import com.jubitus.travellers.follow.net.MsgLeaderGoTo;
import com.jubitus.travellers.follow.net.MsgSetLeaderFollow;
import com.jubitus.travellers.gui.GuiContextMenu;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.unit.equipment.net.MsgEquipDestsResponse;
import com.jubitus.travellers.unit.equipment.net.MsgLeaderGetEquip;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import com.jubitus.travellers.worldmap.gui.GuiWorldMapPanel;
import com.jubitus.travellers.worldmap.gui.WorkerMapScreenSupport;
import com.jubitus.travellers.worldmap.gui.WorldMapContentPopulator;
import com.jubitus.travellers.worldmap.gui.WorldMapController;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Formation Hub's right-click orders menu: built from the current selection and what lies under the
 * cursor. It adds no server logic; every order is an existing group / mission message.
 */
final class FormationOrdersMenu {

    /** What the menu may change in the hub's selection. */
    interface Host {
        void selectLeaderByUuid(@Nullable UUID uuid);

        void selectFollowers(List<UUID> uuids);

        /** The resting state: "You" selected, map left where it is. */
        void selectPlayerRow();
    }

    // Stacking among units on the map, higher is drawn on top.
    private static final int RANK_PLAYER = 4, RANK_LEADER = 3, RANK_ON_DUTY = 2, RANK_SELECTED_FOLLOWER = 1, RANK_FOLLOWER = 0;
    private static final long DESTINATION_CROSS_DURATION_MS = 1500L;
    private static final ResourceLocation DESTINATION_CROSS_TEX =
            new ResourceLocation("travellers", "textures/map/destination_cross.png");

    private enum TargetKind { PLAYER, LEADER, FOLLOWER, ON_DUTY, PLACE }

    /** Something under the cursor on the map. */
    private static final class Target {
        final TargetKind kind;
        final BlockPos pos;
        final String name;
        @Nullable final UUID uuid;
        @Nullable final MsgEquipDestsResponse.EquipDestInfo equip;
        final float distSq;
        final int rank;

        Target(TargetKind kind, BlockPos pos, String name, @Nullable UUID uuid,
               @Nullable MsgEquipDestsResponse.EquipDestInfo equip, float distSq, int rank) {
            this.rank = rank;
            this.kind = kind;
            this.pos = pos;
            this.name = name;
            this.uuid = uuid;
            this.equip = equip;
            this.distSq = distSq;
        }
    }

    private final GuiContextMenu menu = new GuiContextMenu();
    private final List<BlockPos> menuTargets = new ArrayList<>();
    private final EntityPlayer player;
    private final FormationSelection selection;
    private final WorkerMapScreenSupport mapData;
    private final Host host;
    private WorldMapController map;
    private List<MsgEquipDestsResponse.EquipDestInfo> equipDests = Collections.emptyList();

    // Where the last click landed: a menu step that opens the next one puts it there.
    private int clickX, clickY;

    private BlockPos destinationCrossPos = null;
    private long destinationCrossExpireMs = 0L;

    FormationOrdersMenu(EntityPlayer player, FormationSelection selection, WorkerMapScreenSupport mapData, Host host) {
        this.player = player;
        this.selection = selection;
        this.mapData = mapData;
        this.host = host;
    }

    /** The map is rebuilt with the screen: call after each initGui. */
    void setMap(WorldMapController map) {
        this.map = map;
    }

    void setEquipDests(@Nullable List<MsgEquipDestsResponse.EquipDestInfo> dests) {
        this.equipDests = dests != null ? dests : Collections.emptyList();
    }

    boolean isOpen() {
        return menu.isOpen();
    }

    void close() {
        menu.close();
        menuTargets.clear();
    }

    void draw(int mouseX, int mouseY) {
        menu.draw(mouseX, mouseY);
    }

    /** While open, every click is consumed: an item runs, anything else only closes the menu. */
    void mouseClicked(int mouseX, int mouseY) {
        clickX = mouseX;
        clickY = mouseY;
        menu.mouseClicked(mouseX, mouseY);
        if (!menu.isOpen()) menuTargets.clear();
    }

    /** Markers drawn highlighted: what a right click would target under the cursor, or the open menu's targets. */
    Set<BlockPos> highlightedTargets(int mouseX, int mouseY, boolean dragSelecting) {
        if (menu.isOpen()) return new HashSet<>(menuTargets);
        if (dragSelecting || !map.isMouseOver(mouseX, mouseY)) return Collections.emptySet();
        List<Target> hits = findTargets(mouseX, mouseY);
        return hits.isEmpty() ? Collections.emptySet() : Collections.singleton(hits.get(0).pos);
    }

    /**
     * Builds the menu for the current selection and what lies under the cursor. A unit the selection
     * has no order for (or a right click with nothing to command) selects that unit and shows its own menu.
     */
    void open(int mouseX, int mouseY, int screenW, int screenH) {
        close();
        List<Target> hits = findTargets(mouseX, mouseY);
        Target unit = !hits.isEmpty() && hits.get(0).kind != TargetKind.PLACE ? hits.get(0) : null;
        Target place = null;
        for (Target t : hits) {
            if (t.kind == TargetKind.PLACE) {
                place = t;
                break;
            }
        }
        unit = asGroupLeader(unit);
        BlockPos ground = map.screenToWorld(mouseX, mouseY);

        buildFor(unit, place, ground);
        menu.open(mouseX, mouseY, screenW, screenH);
    }

    /** Picks the menu for the current selection and the unit / place / ground it is aimed at. */
    private void buildFor(@Nullable Target unit, @Nullable Target place, @Nullable BlockPos ground) {
        FormationLeaderInfo group = selection.group();
        boolean followers = selection.isFollowers() && !selection.followers.isEmpty();

        if (unit != null && unit.kind == TargetKind.ON_DUTY) {
            menu.header(unit.name).disabled(tr("no_orders"), tr("reason.on_duty"));
            menuTargets.add(unit.pos);
        } else if (unit != null && unit.kind == TargetKind.LEADER && group == null && !followers) {
            host.selectLeaderByUuid(unit.uuid);
            buildGroupMenu(selection.group(), unit, null, null);
        } else if (unit != null && unit.kind == TargetKind.FOLLOWER
                && !(followers && selection.followers.contains(unit.uuid))) {
            host.selectFollowers(Collections.singletonList(unit.uuid));
            buildFollowersMenu(unit, null, null);
        } else if (group != null) {
            buildGroupMenu(group, unit, place, ground);
        } else if (followers) {
            buildFollowersMenu(unit, place, ground);
        } else {
            if (place != null) menu.header(place.name);
            menu.disabled(tr("no_orders"), tr("reason.select_first"));
        }
    }

    /**
     * The same menu as a right click on this leader row's marker on the map, for a row of the leader list
     * (a group's leader, or the player's own row).
     */
    void openForLeaderRow(FormationLeaderInfo row, int mouseX, int mouseY, int screenW, int screenH) {
        close();
        Target unit;
        if (row.isPlayer) {
            unit = new Target(TargetKind.PLAYER, player.getPosition(),
                    I18n.format("travellers.worker.formation.you"), null, null, 0f, RANK_PLAYER);
        } else {
            MsgMapWorkersUpdate.Entry live = mapData.workerByUuid(row.uuid);
            BlockPos pos = live != null ? live.pos : player.world.getHeight(new BlockPos(row.posX, 0, row.posZ));
            unit = new Target(TargetKind.LEADER, pos, row.name, row.uuid, null, 0f, RANK_LEADER);
        }
        buildFor(unit, null, null);
        menu.open(mouseX, mouseY, screenW, screenH);
    }

    /** A grouped follower not part of the loose-followers selection stands for its group's leader. */
    @Nullable
    private Target asGroupLeader(@Nullable Target unit) {
        if (unit == null || unit.kind != TargetKind.FOLLOWER || selection.followers.contains(unit.uuid)) return unit;
        MsgMapWorkersUpdate.Entry member = mapData.workerByUuid(unit.uuid);
        if (member == null || member.followLeaderUuid == null) return unit;
        MsgMapWorkersUpdate.Entry leader = mapData.workerByUuid(member.followLeaderUuid);
        if (leader == null || selection.leaderByUuid(leader.uuid) == null) return unit;
        return new Target(TargetKind.LEADER, leader.pos, leader.name, leader.uuid, null, unit.distSq, RANK_LEADER);
    }

    /** Briefly marks where an order was just sent. */
    void drawDestinationCross() {
        if (destinationCrossPos == null) return;
        long now = System.currentTimeMillis();
        if (now >= destinationCrossExpireMs) {
            destinationCrossPos = null;
            return;
        }
        int sx = Math.round(map.worldToScreenX(destinationCrossPos.getX()));
        int sy = Math.round(map.worldToScreenY(destinationCrossPos.getZ()));
        if (!map.isMouseOver(sx, sy)) return;

        float elapsed = (now - (destinationCrossExpireMs - DESTINATION_CROSS_DURATION_MS)) / (float) DESTINATION_CROSS_DURATION_MS;
        float alpha = elapsed < 0.6f ? 1.0f : 1.0f - (elapsed - 0.6f) / 0.4f;
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, alpha);
        Minecraft.getMinecraft().getTextureManager().bindTexture(DESTINATION_CROSS_TEX);
        Gui.drawModalRectWithCustomSizedTexture(sx - 4, sy - 4, 0, 0, 8, 8, 8, 8);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    /**
     * Everything under the cursor, each within the click radius of its drawn size: units first (the one
     * drawn on top first, then the nearest), then places (nearest first).
     */
    private List<Target> findTargets(int mouseX, int mouseY) {
        List<Target> units = new ArrayList<>();
        List<Target> places = new ArrayList<>();
        float workerR = map.hitRadiusPx(GuiWorldMapPanel.Marker.Size.WORKER);
        float selectedR = map.hitRadiusPx(GuiWorldMapPanel.Marker.Size.WORKER_HIGHLIGHTED);
        float playerR = map.hitRadiusPx(GuiWorldMapPanel.Marker.Size.PLAYER);

        float d = map.screenDistSq(WorldMapContentPopulator.playerDrawX(player), WorldMapContentPopulator.playerDrawZ(player), mouseX, mouseY);
        if (d <= playerR * playerR) {
            units.add(new Target(TargetKind.PLAYER, player.getPosition(),
                    I18n.format("travellers.worker.formation.you"), null, null, d, RANK_PLAYER));
        }
        Set<UUID> selectedFollowers = selection.isFollowers() ? new HashSet<>(selection.followers) : Collections.emptySet();
        FormationLeaderInfo group = selection.group();
        for (MsgMapWorkersUpdate.Entry e : mapData.workers) {
            if (e.pos == null || e.uuid == null) continue;
            boolean selected = selectedFollowers.contains(e.uuid) || (group != null && e.uuid.equals(group.uuid));
            float r = selected ? selectedR : workerR;
            d = map.screenDistSq(mapData.drawX(e), mapData.drawZ(e), mouseX, mouseY);
            if (d > r * r) continue;
            TargetKind kind = WorldMapContentPopulator.isOnDuty(e) ? TargetKind.ON_DUTY
                    : e.isLeader ? TargetKind.LEADER : TargetKind.FOLLOWER;
            int rank = kind == TargetKind.LEADER ? RANK_LEADER
                    : kind == TargetKind.ON_DUTY ? RANK_ON_DUTY
                    : selected ? RANK_SELECTED_FOLLOWER : RANK_FOLLOWER;
            units.add(new Target(kind, e.pos, e.name, e.uuid, null, d, rank));
        }
        float villageR = map.hitRadiusPx(GuiWorldMapPanel.Marker.Size.VILLAGE);
        Set<BlockPos> placed = new HashSet<>();
        for (MsgWorkerGoList.Entry e : mapData.places) {
            if (e.pos == null) continue;
            boolean village = e.kind == MsgWorkerGoList.Kind.VILLAGE || e.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE;
            float r = village ? villageR : workerR;
            d = map.screenDistSq(e.pos, mouseX, mouseY);
            if (d > r * r) continue;
            places.add(new Target(TargetKind.PLACE, e.pos, e.label, null, equipDestAt(e.pos), d, 0));
            placed.add(e.pos);
        }
        for (MsgEquipDestsResponse.EquipDestInfo e : equipDests) {
            if (e.pos == null || placed.contains(e.pos)) continue;
            d = map.screenDistSq(e.pos, mouseX, mouseY);
            if (d <= workerR * workerR) places.add(new Target(TargetKind.PLACE, e.pos, e.name, null, e, d, 0));
        }
        units.sort(Comparator.comparingInt((Target t) -> -t.rank).thenComparingDouble(t -> t.distSq));
        places.sort(Comparator.comparingDouble(t -> t.distSq));
        units.addAll(places);
        return units;
    }

    /** The selectable worker (not on duty) drawn on top at this spot, or null; leaders are skipped when asked. */
    @Nullable
    MsgMapWorkersUpdate.Entry selectableWorkerAt(int mouseX, int mouseY, boolean followersOnly) {
        for (Target t : findTargets(mouseX, mouseY)) {
            if (t.kind == TargetKind.FOLLOWER || (t.kind == TargetKind.LEADER && !followersOnly)) return mapData.workerByUuid(t.uuid);
        }
        return null;
    }

    @Nullable
    private MsgEquipDestsResponse.EquipDestInfo equipDestAt(BlockPos pos) {
        for (MsgEquipDestsResponse.EquipDestInfo e : equipDests) {
            if (pos.equals(e.pos)) return e;
        }
        return null;
    }

    // ── Menus ─────────────────────────────────────────────────────────────────

    /** Orders for one worker group: toward itself, a unit, a place, or bare ground. */
    private void buildGroupMenu(@Nullable FormationLeaderInfo group, @Nullable Target unit,
                                @Nullable Target place, @Nullable BlockPos ground) {
        if (group == null || group.uuid == null) return;
        UUID src = group.uuid;
        String busy = group.busy ? tr("reason.busy") : null;
        boolean goOffered = false;
        boolean self = unit != null && src.equals(unit.uuid);

        if (self) {
            // Halt whenever it is moving (following or sent somewhere); resume whenever it isn't following.
            menu.header(group.name);
            if (group.followActive || group.goActive) {
                menu.itemUnless(busy, tr("halt"), () -> setLeaderFollow(group, false));
            }
            if (!group.followActive) {
                menu.itemUnless(busy, tr("resume"), () -> setLeaderFollow(group, true));
            }
            menuTargets.add(unit.pos);
        } else if (unit != null && unit.kind == TargetKind.PLAYER) {
            menu.header(unit.name);
            addGo(src, unit.pos, busy);
            menu.itemUnless(busy, tr("follow_me"), () -> TravellersNetwork.CHANNEL.sendToServer(new MsgGroupFollow(src, null)));
            menu.itemUnless(busy, tr("merge_escort"), () -> TravellersNetwork.CHANNEL.sendToServer(new MsgGroupMerge(src, null)));
            goOffered = true;
            menuTargets.add(unit.pos);
        } else if (unit != null && unit.kind == TargetKind.LEADER) {
            FormationLeaderInfo target = selection.leaderByUuid(unit.uuid);
            String why = busy != null ? busy : target != null && target.busy ? tr("reason.target_busy") : null;
            UUID dst = unit.uuid;
            menu.header(unit.name);
            addGo(src, unit.pos, busy);
            menu.itemUnless(why, tr("follow_group"), () -> TravellersNetwork.CHANNEL.sendToServer(new MsgGroupFollow(src, dst)));
            menu.itemUnless(why, tr("merge_group"), () -> TravellersNetwork.CHANNEL.sendToServer(new MsgGroupMerge(src, dst)));
            goOffered = true;
            menuTargets.add(unit.pos);
        }

        if (place != null) {
            menu.header(place.name);
            if (!goOffered) addGo(src, place.pos, busy);
            goOffered = true;
            if (place.equip != null) {
                MsgEquipDestsResponse.EquipDestInfo dest = place.equip;
                if (dest.kind == EquipTargetKind.STABLE) {
                    addStayChoice(src, dest, EquipTargetKind.STABLE, "take_horses", busy);
                    addStayChoice(src, dest, EquipTargetKind.STABLE_RETURN, "return_horses", busy);
                } else {
                    addStayChoice(src, dest, dest.kind, "equip", busy);
                    addStayChoice(src, dest, dest.kind.toDump(), "dump", busy);
                }
            }
            menuTargets.add(place.pos);
        }

        if (!goOffered && !self && ground != null) {
            addGo(src, ground, busy);
            menuTargets.add(ground);
        }
    }

    /** Orders for the selected loose followers. */
    private void buildFollowersMenu(@Nullable Target unit, @Nullable Target place, @Nullable BlockPos ground) {
        List<UUID> sel = new ArrayList<>(selection.followers);
        String needTwo = sel.size() < 2 ? tr("reason.need_two") : null;

        if (unit != null && unit.kind == TargetKind.PLAYER) {
            menu.header(unit.name).item(tr("follow_me"), () -> sendFollowers(MsgGroupCreate.Action.COME_BACK, sel, null));
            menuTargets.add(unit.pos);
            return;
        }
        if (unit != null && unit.kind == TargetKind.LEADER) {
            FormationLeaderInfo target = selection.leaderByUuid(unit.uuid);
            String why = target != null && target.busy ? tr("reason.target_busy") : null;
            UUID dst = unit.uuid;
            menu.header(unit.name).itemUnless(why, tr("join_group"),
                    () -> sendFollowers(MsgGroupCreate.Action.ASSIGN_TO_LEADER, sel, dst));
            menuTargets.add(unit.pos);
            return;
        }
        if (unit != null && unit.kind == TargetKind.FOLLOWER) {
            // right click on the selection itself
            menu.header(I18n.format("travellers.worker.formation.card.followers_selected", sel.size()));
            menu.item(tr("follow_me"), () -> sendFollowers(MsgGroupCreate.Action.COME_BACK, sel, null));
            menu.itemUnless(needTwo, tr("create_group"), () -> createGroup(sel, null));
            menuTargets.add(unit.pos);
            return;
        }
        BlockPos dest = place != null ? place.pos : ground;
        if (place != null) menu.header(place.name);
        if (dest != null) {
            menu.item(tr("create_group_go"), () -> createGroup(sel, dest));
            menuTargets.add(dest);
        }
        menu.itemUnless(needTwo, tr("create_group"), () -> createGroup(sel, null));
    }

    // ── Orders ────────────────────────────────────────────────────────────────

    void setLeaderFollow(FormationLeaderInfo group, boolean follow) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgSetLeaderFollow(group.uuid, follow));
        // shown at once; the next list refresh corrects a refusal
        group.followActive = follow;
        group.goActive = false;
    }

    void followMe(UUID leader) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgGroupFollow(leader, null));
    }

    /** Lists the player and the other groups to follow; picking one sends the order. */
    void openFollowChooser(FormationLeaderInfo group, int x, int y) {
        openGroupChooser(tr("choose_follow"), group, (src, dst) -> new MsgGroupFollow(src, dst), x, y);
    }

    /** Lists the player and the other groups to merge this one into; picking one sends the merge. */
    void openMergeChooser(FormationLeaderInfo group, int x, int y) {
        openGroupChooser(tr("choose_merge"), group, (src, dst) -> new MsgGroupMerge(src, dst), x, y);
    }

    private interface OrderFactory {
        net.minecraftforge.fml.common.network.simpleimpl.IMessage make(UUID src, @Nullable UUID dst);
    }

    /** The player first (a null destination means "me" for both orders), then every other group. */
    private void openGroupChooser(String header, FormationLeaderInfo group, OrderFactory order, int x, int y) {
        close();
        UUID src = group.uuid;
        menu.header(header);
        menu.item(I18n.format("travellers.worker.formation.you"),
                () -> TravellersNetwork.CHANNEL.sendToServer(order.make(src, null)));
        for (FormationLeaderInfo target : selection.leaders) {
            if (target.isPlayer || target.uuid == null || target.uuid.equals(src)) continue;
            UUID dst = target.uuid;
            String why = target.busy ? tr("reason.target_busy") : null;
            menu.itemUnless(why, target.name + " (" + target.followerCount + ")",
                    () -> TravellersNetwork.CHANNEL.sendToServer(order.make(src, dst)));
        }
        menu.open(x, y, screenW(), screenH());
    }

    /** Lists the groups the selected followers can join; picking one sends them there. */
    void openJoinChooser(List<UUID> sel, int x, int y) {
        close();
        menu.header(tr("choose_join"));
        int count = 0;
        for (FormationLeaderInfo target : selection.leaders) {
            if (target.isPlayer || target.uuid == null) continue;
            UUID dst = target.uuid;
            String why = target.busy ? tr("reason.target_busy") : null;
            menu.itemUnless(why, target.name + " (" + target.followerCount + ")",
                    () -> sendFollowers(MsgGroupCreate.Action.ASSIGN_TO_LEADER, sel, dst));
            count++;
        }
        if (count == 0) menu.disabled(tr("no_other_group"), tr("reason.no_other_group"));
        menu.open(x, y, screenW(), screenH());
    }

    private static int screenW() {
        return new net.minecraft.client.gui.ScaledResolution(Minecraft.getMinecraft()).getScaledWidth();
    }

    private static int screenH() {
        return new net.minecraft.client.gui.ScaledResolution(Minecraft.getMinecraft()).getScaledHeight();
    }

    /** One chest/stable order as a single line; clicking it asks whether the group stays there or comes back. */
    private void addStayChoice(UUID leader, MsgEquipDestsResponse.EquipDestInfo dest, EquipTargetKind kind,
                               String key, @Nullable String busy) {
        menu.itemUnless(busy, tr(key), () -> {
            menu.header(tr("after_mission"));
            menu.item(tr("stay_there"), () -> sendEquip(leader, dest, kind, true));
            menu.item(tr("come_back"), () -> sendEquip(leader, dest, kind, false));
            menu.open(clickX, clickY, screenW(), screenH());
        });
    }

    private void addGo(UUID leader, BlockPos pos, @Nullable String busy) {
        menu.itemUnless(busy, tr("go"), () -> {
            TravellersNetwork.CHANNEL.sendToServer(new MsgLeaderGoTo(leader, pos));
            showDestination(pos);
        });
    }

    private void sendEquip(UUID leader, MsgEquipDestsResponse.EquipDestInfo dest, EquipTargetKind kind, boolean stay) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgLeaderGetEquip(leader, dest.pos, kind, stay));
        showDestination(dest.pos);
    }

    void sendFollowers(MsgGroupCreate.Action action, List<UUID> sel, @Nullable UUID leader) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgGroupCreate(action, sel, leader));
        host.selectPlayerRow();
    }

    void createGroup(List<UUID> sel, @Nullable BlockPos goTo) {
        selection.pendingFromFollowers = new HashSet<>(sel);
        TravellersNetwork.CHANNEL.sendToServer(goTo != null
                ? MsgGroupCreate.createAndGo(sel, goTo)
                : new MsgGroupCreate(MsgGroupCreate.Action.CREATE_GROUP, sel, null));
        if (goTo != null) showDestination(goTo);
        host.selectPlayerRow();
    }

    private void showDestination(BlockPos pos) {
        destinationCrossPos = pos;
        destinationCrossExpireMs = System.currentTimeMillis() + DESTINATION_CROSS_DURATION_MS;
    }

    private static String tr(String key) {
        return I18n.format("travellers.worker.formation.menu." + key);
    }
}
