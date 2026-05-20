package com.jubitus.travellers.server.map;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerRecord;
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

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
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
        final boolean isLeader;
        final int ownerColor;

        WorkerInfo(EntityTravellerWorker w, int dim) {
            this.entityId = w.getEntityId();
            this.dim = dim;
            BlockPos p = w.getPosition();
            this.x = p.getX();
            this.y = p.getY();
            this.z = p.getZ();
            this.name = w.getName();
            this.isLeader = w.isVisualLeader();
            this.ownerColor = w.getOwnerMarkColor() & 0xFFFFFF;
        }

        WorkerInfo(VirtualWorkerRecord vr, boolean isLeader) {
            this.entityId = -1; // no loaded entity
            this.dim = vr.dimension;
            this.x = (int) vr.posX;
            this.y = (int) vr.posY;
            this.z = (int) vr.posZ;
            this.name = vr.name != null ? vr.name : "";
            this.isLeader = isLeader;
            this.ownerColor = vr.ownerColor;
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

                byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(new WorkerInfo(w, dim));
            }
        }

        // Add virtual workers from the overworld VirtualWorkerManager
        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            VirtualWorkerManager vmgr = VirtualWorkerManager.get(overworld);

            // Collect UUIDs that have virtual followers escorting them (= virtual leaders)
            java.util.Set<UUID> virtualLeaderUUIDs = new java.util.HashSet<>();
            for (VirtualWorkerRecord vr : vmgr.getRecordsView()) {
                if (vr.escortLeaderUUID != null) virtualLeaderUUIDs.add(vr.escortLeaderUUID);
            }

            for (VirtualWorkerRecord vr : vmgr.getRecordsView()) {
                if (!vr.isHiredFollower || vr.followOwnerUUID == null) continue;
                boolean isLeader = virtualLeaderUUIDs.contains(vr.uuid);
                byOwner.computeIfAbsent(vr.followOwnerUUID, k -> new ArrayList<>())
                        .add(new WorkerInfo(vr, isLeader));
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
                out.add(en);
            }

            TravellerFollowNet.sendToPlayer(player, new MsgMapWorkersUpdate(out));
        }
    }
}