package com.jubitus.travellers.server.map;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationPreviewLayout;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
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
        final boolean isLeader;
        final int ownerColor;
        final UUID uuid;
        final float rotYaw;
        @javax.annotation.Nullable final UUID escortLeaderUUID;
        @javax.annotation.Nullable final BlockPos goal;

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
            this.uuid = w.getUniqueID();
            this.rotYaw = w.rotationYaw;
            this.escortLeaderUUID = null;
            this.goal = null;
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
            this.escortLeaderUUID = vr.escortLeaderUUID;
            this.goal = vr.goal;
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

    /**
     * Computes formation-aware display positions for escort followers so they appear spread
     * around their leader on the worldmap rather than piled up at the same point.
     * Uses the group's WorkerFormationSettings (style, spacing, width…) and the leader's
     * heading to orient the formation correctly. Leaders are not affected.
     */
    private static Map<UUID, BlockPos> computeFollowerSpread(Map<UUID, List<WorkerInfo>> byOwner,
                                                              WorldServer world) {
        Map<UUID, WorkerInfo> byUUID = new HashMap<>();
        for (List<WorkerInfo> list : byOwner.values()) {
            for (WorkerInfo w : list) {
                if (w.uuid != null) byUUID.put(w.uuid, w);
            }
        }

        Map<UUID, BlockPos> overrides = new HashMap<>();
        WorkerFormationSavedData formationData = WorkerFormationSavedData.get(world);

        // Group followers by escort leader
        Map<UUID, List<WorkerInfo>> followersByLeader = new HashMap<>();
        for (List<WorkerInfo> list : byOwner.values()) {
            for (WorkerInfo w : list) {
                if (w.escortLeaderUUID != null) {
                    followersByLeader.computeIfAbsent(w.escortLeaderUUID, k -> new ArrayList<>()).add(w);
                }
            }
        }

        for (Map.Entry<UUID, List<WorkerInfo>> entry : followersByLeader.entrySet()) {
            UUID leaderUUID = entry.getKey();
            List<WorkerInfo> followers = entry.getValue();
            if (followers.isEmpty()) continue;

            WorkerInfo leader = byUUID.get(leaderUUID);
            if (leader == null) continue;

            // Heading direction: prefer goal direction when the leader is moving, else rotYaw
            double fwdX, fwdZ;
            if (leader.goal != null) {
                double dx = leader.goal.getX() + 0.5 - leader.x;
                double dz = leader.goal.getZ() + 0.5 - leader.z;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 1.0) {
                    fwdX = dx / dist;
                    fwdZ = dz / dist;
                } else {
                    // Reached goal: use stored yaw (Minecraft: yaw=0 → South +Z)
                    double yawRad = Math.toRadians(leader.rotYaw);
                    fwdX = -Math.sin(yawRad);
                    fwdZ = Math.cos(yawRad);
                }
            } else {
                double yawRad = Math.toRadians(leader.rotYaw);
                fwdX = -Math.sin(yawRad);
                fwdZ = Math.cos(yawRad);
            }

            // Right perpendicular (clockwise 90° from forward in XZ)
            double rightX = fwdZ;
            double rightZ = -fwdX;

            // Formation settings for this leader
            WorkerFormationScopeKey scope = new WorkerFormationScopeKey(
                    WorkerFormationScopeKey.Type.WORKER, leaderUUID);
            WorkerFormationSettings settings = formationData.getSettings(scope);

            // Sort followers deterministically so slot assignments are stable across ticks
            followers.sort(Comparator.comparingInt(f -> f.uuid.hashCode()));

            int total = followers.size();
            List<WorkerFormationPreviewLayout.Point> slots =
                    WorkerFormationPreviewLayout.buildLayout(settings, total);

            // Scale up the formation positions for legibility on the worldmap
            final double MAP_SCALE = 4.0;

            for (int i = 0; i < total && i < slots.size(); i++) {
                WorkerFormationPreviewLayout.Point p = slots.get(i);
                // Preview coords are 10× real blocks; p.x = side, p.z = distance behind
                double side = p.x / 10.0 * MAP_SCALE;
                double back = p.z / 10.0 * MAP_SCALE;
                double ox = side * rightX - back * fwdX;
                double oz = side * rightZ - back * fwdZ;
                overrides.put(followers.get(i).uuid,
                        new BlockPos(leader.x + (int) Math.round(ox), leader.y, leader.z + (int) Math.round(oz)));
            }
        }

        return overrides;
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

        // Add virtual workers from the overworld VirtualTravellerManager
        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            VirtualTravellerManager vmgr = VirtualTravellerManager.get(overworld);

            // Collect UUIDs that have virtual followers escorting them (= virtual leaders)
            java.util.Set<UUID> virtualLeaderUUIDs = new java.util.HashSet<>();
            for (VirtualTravellerRecord vr : vmgr.getRecordsView()) {
                if (vr.escortLeaderUUID != null) virtualLeaderUUIDs.add(vr.escortLeaderUUID);
            }

            for (VirtualTravellerRecord vr : vmgr.getRecordsView()) {
                if (!vr.isHiredFollower || vr.followOwnerUUID == null) continue;
                boolean isLeader = virtualLeaderUUIDs.contains(vr.uuid);
                byOwner.computeIfAbsent(vr.followOwnerUUID, k -> new ArrayList<>())
                        .add(new WorkerInfo(vr, isLeader));
            }
        }

        // Compute formation-aware display spread for escort followers
        Map<UUID, BlockPos> followerSpread = overworld != null
                ? computeFollowerSpread(byOwner, overworld)
                : Collections.emptyMap();

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
                BlockPos spreadPos = w.uuid != null ? followerSpread.get(w.uuid) : null;
                en.pos = spreadPos != null ? spreadPos : new BlockPos(w.x, w.y, w.z);
                en.name = (w.name != null) ? w.name : "";
                en.isLeader = w.isLeader;
                en.ownerColor = w.ownerColor;
                en.uuid = w.uuid;
                out.add(en);
            }

            TravellerFollowNet.sendToPlayer(player, new MsgMapWorkersUpdate(out));
        }
    }
}