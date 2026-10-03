package com.jubitus.travellers.follow.gui;

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

    static final int TARGET_RADIUS_PX = 7;
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

        Target(TargetKind kind, BlockPos pos, String name, @Nullable UUID uuid,
               @Nullable MsgEquipDestsResponse.EquipDestInfo equip, float distSq) {
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
        BlockPos ground = map.screenToWorld(mouseX, mouseY);

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
        menu.open(mouseX, mouseY, screenW, screenH);
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

    /** Everything within a few pixels of the cursor: units first (nearest first), then places. */
    private List<Target> findTargets(int mouseX, int mouseY) {
        List<Target> units = new ArrayList<>();
        List<Target> places = new ArrayList<>();
        float r2 = TARGET_RADIUS_PX * TARGET_RADIUS_PX;

        float d = map.screenDistSq(player.getPosition(), mouseX, mouseY);
        if (d <= r2) {
            units.add(new Target(TargetKind.PLAYER, player.getPosition(),
                    I18n.format("travellers.worker.formation.you"), null, null, d));
        }
        for (MsgMapWorkersUpdate.Entry e : mapData.workers) {
            if (e.pos == null || e.uuid == null) continue;
            d = map.screenDistSq(e.pos, mouseX, mouseY);
            if (d > r2) continue;
            TargetKind kind = WorldMapContentPopulator.isOnDuty(e) ? TargetKind.ON_DUTY
                    : e.isLeader ? TargetKind.LEADER : TargetKind.FOLLOWER;
            units.add(new Target(kind, e.pos, e.name, e.uuid, null, d));
        }
        Set<BlockPos> placed = new HashSet<>();
        for (MsgWorkerGoList.Entry e : mapData.places) {
            if (e.pos == null) continue;
            d = map.screenDistSq(e.pos, mouseX, mouseY);
            if (d > r2) continue;
            places.add(new Target(TargetKind.PLACE, e.pos, e.label, null, equipDestAt(e.pos), d));
            placed.add(e.pos);
        }
        for (MsgEquipDestsResponse.EquipDestInfo e : equipDests) {
            if (e.pos == null || placed.contains(e.pos)) continue;
            d = map.screenDistSq(e.pos, mouseX, mouseY);
            if (d <= r2) places.add(new Target(TargetKind.PLACE, e.pos, e.name, null, e, d));
        }
        Comparator<Target> nearest = Comparator.comparingDouble(t -> t.distSq);
        units.sort(nearest);
        places.sort(nearest);
        units.addAll(places);
        return units;
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
                menu.itemUnless(busy, tr("equip_return"), () -> sendEquip(src, dest, false));
                menu.itemUnless(busy, tr("equip_stay"), () -> sendEquip(src, dest, true));
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

    private void setLeaderFollow(FormationLeaderInfo group, boolean follow) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgSetLeaderFollow(group.uuid, follow));
        // shown at once; the next list refresh corrects a refusal
        group.followActive = follow;
        group.goActive = false;
    }

    private void addGo(UUID leader, BlockPos pos, @Nullable String busy) {
        menu.itemUnless(busy, tr("go"), () -> {
            TravellersNetwork.CHANNEL.sendToServer(new MsgLeaderGoTo(leader, pos));
            showDestination(pos);
        });
    }

    private void sendEquip(UUID leader, MsgEquipDestsResponse.EquipDestInfo dest, boolean stay) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgLeaderGetEquip(leader, dest.pos, dest.kind, stay));
        showDestination(dest.pos);
    }

    private void sendFollowers(MsgGroupCreate.Action action, List<UUID> sel, @Nullable UUID leader) {
        TravellersNetwork.CHANNEL.sendToServer(new MsgGroupCreate(action, sel, leader));
        host.selectPlayerRow();
    }

    private void createGroup(List<UUID> sel, @Nullable BlockPos goTo) {
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
