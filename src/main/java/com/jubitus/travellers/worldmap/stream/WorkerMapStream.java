package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellerFollowNet;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.jubitus.travellers.worldmap.net.MsgMapWorkersUpdate;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class WorkerMapStream {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    // Update interval: 20 ticks = 1 second
    private static final int UPDATE_EVERY_TICKS = 20;
    private static int tickCounter = 0;

    // Hard cap so you never send insane packets even if player zooms out a lot
    private static final int MAX_WORKERS_PER_PACKET = 1024;

    // Per-subscriber view (what the player is currently looking at in the map)
    private static final Map<UUID, ViewState> VIEWS = new ConcurrentHashMap<>();

    private static class ViewState {
        final int dim;
        final int cx;
        final int cz;
        final int range; // blocks (radius)
        final long updatedAtMs;

        ViewState(int dim, int cx, int cz, int range, long updatedAtMs) {
            this.dim = dim;
            this.cx = cx;
            this.cz = cz;
            this.range = range;
            this.updatedAtMs = updatedAtMs;
        }
    }

    private static class WorkerInfo {
        final int entityId;
        final int dim;
        final int x, y, z;
        final String name;
        // A worker is a leader iff at least one worker (loaded OR virtual) escorts it - computed in a
        // post-pass over the assembled list (see onServerTick), not from the loaded-only follower
        // count, so a materialized leader whose followers are still virtual keeps its leader marker.
        boolean isLeader;
        final int ownerColor;
        final UUID uuid;
        final float rotYaw;
        final float health;
        final float maxHealth;
        final boolean inBattle;
        final String statusKey;
        final UUID followTarget; // escort leader UUID, or null
        final boolean followsOwner;
        final String role;

        WorkerInfo(EntityTravellerWorker w, int dim) {
            this.entityId = w.getEntityId();
            this.dim = dim;
            BlockPos p = w.getPosition();
            this.x = p.getX();
            this.y = p.getY();
            this.z = p.getZ();
            this.name = w.getName();
            this.isLeader = false; // set in the post-pass over the assembled list
            this.ownerColor = w.getOwnerMarkColor() & 0xFFFFFF;
            this.uuid = w.getUniqueID();
            this.rotYaw = w.rotationYaw;
            this.health = w.getHealth();
            this.maxHealth = w.getMaxHealth();
            this.inBattle = w.combat.isInCombatRaw();
            this.statusKey = statusKeyFor(w.getMode().name(), w.isGoingHome(), w.isGoOrderActive());
            this.followTarget = w.getEscortLeaderUUID();
            this.followsOwner = this.followTarget == null && w.isFollowOrderActive();
            this.role = w.getDutyKind() != null ? w.getDutyKind() : "";
        }

        WorkerInfo(VirtualTravellerRecord vr, boolean isLeader) {
            this.entityId = -1; // no loaded entity
            this.dim = vr.dimension;
            this.x = (int) vr.posX;
            this.y = (int) vr.posY;
            this.z = (int) vr.posZ;
            this.name = vr.name != null ? vr.name : "";
            this.isLeader = isLeader;
            this.ownerColor = vr.ownerColor;
            this.uuid = vr.uuid;
            this.rotYaw = vr.rotYaw;
            this.health = vr.getHealthResolved();
            this.maxHealth = vr.getCombatProfile().maxHealth;
            this.inBattle = vr.battleId != null;
            this.statusKey = statusKeyFor(vr.workerMode, false, vr.goOrderActive);
            this.followTarget = vr.escortLeaderUUID;
            this.followsOwner = this.followTarget == null && vr.followOrderActive;
            this.role = vr.dutyKind != null ? vr.dutyKind : "";
        }
    }

    /** Maps a worker's mode/flags to a tooltip i18n key ("" = idle/none, shown as the follow line instead). */
    private static String statusKeyFor(String mode, boolean goingHome, boolean goOrder) {
        if (mode == null) mode = "IDLE";
        switch (mode) {
            case "TO_VILLAGE":    return "travellers.map.worker.status.transport";
            case "RETURNING":     return "travellers.map.worker.status.returning";
            case "RECRUITING":    return "travellers.map.worker.status.recruiting";
            case "GET_EQUIPMENT": return "travellers.map.worker.status.get_equipment";
            default: // IDLE
                if (goingHome) return "travellers.map.worker.status.going_home";
                if (goOrder)   return "travellers.map.worker.status.going_to";
                return "";
        }
    }

    private static class WorkerWithDist {
        final WorkerInfo w;
        final long d2;

        WorkerWithDist(WorkerInfo w, long d2) {
            this.w = w;
            this.d2 = d2;
        }
    }

    public static void subscribe(UUID playerId) {
        if (playerId != null) SUBSCRIBERS.add(playerId);
    }

    public static void unsubscribe(UUID playerId) {
        if (playerId != null) {
            SUBSCRIBERS.remove(playerId);
            VIEWS.remove(playerId);
        }
    }

    public static boolean isSubscribed(UUID playerId) {
        return playerId != null && SUBSCRIBERS.contains(playerId);
    }

    /**
     * Exposes the last map view (dim, center, range) reported by this player's WorldMapController,
     * so other map streams (e.g. PopulationMapStream) can reuse it without a second view-report packet.
     * Returns {dim, centerX, centerZ, rangeBlocks}, or null if the player hasn't reported a view yet.
     */
    public static int[] getView(UUID playerId) {
        ViewState v = VIEWS.get(playerId);
        if (v == null) return null;
        return new int[]{v.dim, v.cx, v.cz, v.range};
    }

    /**
     * Called by MsgWorkerMapView (client -> server).
     * The GUI sends the current center/zoom so the server sends only what is visible.
     */
    public static void updateView(UUID playerId, int dim, int centerX, int centerZ, int rangeBlocks) {
        if (playerId == null) return;

        // Sanity clamps (avoid negative / ridiculous values)
        int r = Math.max(64, Math.min(rangeBlocks, 8192));

        VIEWS.put(playerId, new ViewState(dim, centerX, centerZ, r, System.currentTimeMillis()));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        unsubscribe(e.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter % UPDATE_EVERY_TICKS != 0) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // Snapshot subscribers
        List<UUID> targets = new ArrayList<>(SUBSCRIBERS);
        if (targets.isEmpty()) return;

        // Build owner -> workers index ONCE per tick (cheap enough, avoids O(subscribers * workers))
        Map<UUID, List<WorkerInfo>> byOwner = new HashMap<>();
        // Unified leader set: any worker (loaded OR virtual) that at least one worker escorts.
        java.util.Set<UUID> leaderUuids = new java.util.HashSet<>();

        for (WorldServer ws : server.worlds) {
            int dim = ws.provider.getDimension();

            // All loaded worker entities in this world
            List<EntityTravellerWorker> workers = ws.getEntities(EntityTravellerWorker.class, w ->
                    w != null && w.isEntityAlive()
            );

            for (EntityTravellerWorker w : workers) {
                // Robust “is hired” check: use datamanager if correct, else fall back to fields.
                UUID owner = w.getFollowOwnerId();
                if (owner == null) owner = w.followOwnerId; // your field is public
                if (owner == null) continue;

                boolean hired = false;
                try {
                    // If datamanager is correct, prefer it
                    hired = w.isHiredFollower();
                } catch (Throwable ignored) {
                    // fallback below
                }
                if (!hired) {
                    // fallback to your public field if needed
                    hired = w.hiredFollower;
                }
                if (!hired) continue;

                UUID lead = w.getEscortLeaderUUID();
                if (lead != null) leaderUuids.add(lead);
                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(new WorkerInfo(w, dim));
            }
        }

        // Add virtual workers from the overworld VirtualTravellerManager
        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            VirtualTravellerManager vmgr = VirtualTravellerManager.get(overworld);

            for (VirtualTravellerRecord vr : vmgr.getRecordsView()) {
                if (!vr.isHiredFollower || vr.followOwnerUUID == null) continue;
                if (vr.escortLeaderUUID != null) leaderUuids.add(vr.escortLeaderUUID);
                byOwner.computeIfAbsent(vr.followOwnerUUID, k -> new ArrayList<>())
                        .add(new WorkerInfo(vr, false));
            }
        }

        // Post-pass: flag leaders from the unified set (loaded + virtual followers both counted).
        for (List<WorkerInfo> list : byOwner.values()) {
            for (WorkerInfo w : list) {
                if (leaderUuids.contains(w.uuid)) w.isLeader = true;
            }
        }

        // Send updates per subscribed player
        for (UUID playerId : targets) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) continue;

            List<WorkerInfo> mine = byOwner.get(playerId);
            if (mine == null || mine.isEmpty()) {
                TravellerFollowNet.sendToPlayer(player, new MsgMapWorkersUpdate(Collections.emptyList()));
                continue;
            }


            // View filter (defaults if GUI hasn’t sent view yet)
            ViewState view = VIEWS.get(playerId);
            int viewDim = (view != null) ? view.dim : player.dimension;
            int cx = (view != null) ? view.cx : (int) Math.floor(player.posX);
            int cz = (view != null) ? view.cz : (int) Math.floor(player.posZ);
            int range = (view != null) ? view.range : 1024;

            long rangeSq = (long) range * (long) range;

            ArrayList<WorkerWithDist> visible = new ArrayList<>();
            for (WorkerInfo w : mine) {
                if (w.dim != viewDim) continue;

                long dx = (long) w.x - (long) cx;
                long dz = (long) w.z - (long) cz;
                long d2 = dx * dx + dz * dz;

                if (d2 <= rangeSq) {
                    visible.add(new WorkerWithDist(w, d2));
                }
            }

            if (visible.isEmpty()) {
                TravellerFollowNet.sendToPlayer(player, new MsgMapWorkersUpdate(Collections.emptyList()));
                continue;
            }

            // Cap output size (closest first)
            if (visible.size() > MAX_WORKERS_PER_PACKET) {
                visible.sort(Comparator.comparingLong(a -> a.d2));
                visible.subList(MAX_WORKERS_PER_PACKET, visible.size()).clear();
            }

            // Resolve leader names for the "Following: <name>" line (this owner's workers only).
            Map<UUID, String> nameByUuid = new HashMap<>();
            for (WorkerInfo w : mine) {
                if (w.uuid != null && w.name != null && !w.name.isEmpty()) nameByUuid.put(w.uuid, w.name);
            }

            List<MsgMapWorkersUpdate.Entry> out = new ArrayList<>(visible.size());
            for (WorkerWithDist ww : visible) {
                WorkerInfo w = ww.w;
                MsgMapWorkersUpdate.Entry en = new MsgMapWorkersUpdate.Entry();
                en.entityId = w.entityId;
                en.dim = w.dim;
                en.pos = new BlockPos(w.x, w.y, w.z);
                en.name = (w.name != null) ? w.name : "";
                en.isLeader = w.isLeader;
                en.ownerColor = w.ownerColor;
                en.uuid = w.uuid;
                en.health = w.health;
                en.maxHealth = w.maxHealth;
                en.inBattle = w.inBattle;
                en.statusKey = w.statusKey;
                en.followsOwner = w.followsOwner;
                en.role = w.role;
                en.followName = w.followTarget != null ? nameByUuid.getOrDefault(w.followTarget, "") : "";
                out.add(en);
            }

            TravellerFollowNet.sendToPlayer(player, new MsgMapWorkersUpdate(out));
        }
    }
}