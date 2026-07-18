package com.jubitus.travellers.server.net.message;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.server.net.MsgOpenClientWorkerGui;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.formations.FormationLeaderInfo;
import com.jubitus.travellers.server.net.message.formations.MsgOpenFormationHubGui;
import com.jubitus.travellers.server.net.message.formations.MsgOpenWorkerFormationGui;
import com.jubitus.travellers.server.net.message.home.MsgSyncWorkerHome;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHireState;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHostileList;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MsgOpenWorkerSubGui implements IMessage {

    private int entityId;
    private Type type;
    public MsgOpenWorkerSubGui() {
    }


    public MsgOpenWorkerSubGui(int entityId, Type type) {
        this.entityId = entityId;
        this.type = type;
    }

    public static MsgSyncWorkerHostileList buildHostileListPacket(EntityTravellerWorker worker) {
        EntityTravellerWorker leader = worker.getGroupLeaderOrSelf();

        java.util.List<String> out = new java.util.ArrayList<>();
        MinecraftServer srv = worker.world.getMinecraftServer();
        if (srv != null) {
            for (UUID id : worker.getHostility().getHostilesForGui()) {
                GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(id);
                out.add(gp != null && gp.getName() != null ? gp.getName() : id.toString());
            }
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return new MsgSyncWorkerHostileList(worker.getEntityId(), out);
    }

    private static boolean isHiredByPlayer(EntityTravellerWorker worker, EntityPlayerMP player) {
        return worker.isHiredFollower()
                && worker.getFollowOwnerId() != null
                && player.getUniqueID().equals(worker.getFollowOwnerId());
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        int idx = buf.readUnsignedByte();
        Type[] vals = Type.values();
        this.type = (idx >= 0 && idx < vals.length) ? vals[idx] : Type.TRANSPORT;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeByte((byte) type.ordinal());
    }

    public enum Type {
        TRANSPORT,
        ORDERS,
        RECRUIT,
        INVENTORY,
        BEHAVIOR,
        HUB,
        GO,
        HOME,
        FORMATION,
        VILLAGE_RELATIONS
    }

    public static class Handler implements IMessageHandler<MsgOpenWorkerSubGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenWorkerSubGui msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker worker = (EntityTravellerWorker) e;

                int guiId;

                switch (msg.type) {

                    case TRANSPORT:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_TRANSPORT);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        player.openGui(
                                Travellers.INSTANCE,
                                TravellersGuiIds.WORKER,
                                world,
                                worker.getEntityId(),
                                0, 0
                        );
                        return;

                    case INVENTORY:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_INVENTORY);
                            return;
                        }
                        player.openGui(
                                Travellers.INSTANCE,
                                TravellersGuiIds.WORKER_INV,
                                world,
                                worker.getEntityId(),
                                0, 0
                        );
                        return;

                    case ORDERS:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_ORDERS);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.ORDERS),
                                player
                        );
                        return;

                    case BEHAVIOR:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_BEHAVIOR);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(buildHostileListPacket(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.BEHAVIOR),
                                player
                        );
                        return;

                    case RECRUIT:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_RECRUIT);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.RECRUIT),
                                player
                        );
                        return;

                    case HOME:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_HOME);
                            return;
                        }

                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HOME),
                                player
                        );
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(worker), player);
                        return;

                    case HUB:
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HUB),
                                player
                        );
                        return;

                    case GO:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_GO);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.GO),
                                player
                        );
                        return;

                    case FORMATION:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_FORMATION);
                            return;
                        }

                        {
                            MinecraftServer srv = player.getServer();
                            if (srv == null) return;

                            WorldServer overworld = srv.worlds[0];
                            VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                            UUID ownerId = player.getUniqueID();
                            WorkerFormationSavedData fsd = WorkerFormationSavedData.get(world);
                            Map<String, WorkerFormationSettings> settingsByScope = new HashMap<>();

                            List<FormationLeaderInfo> leaders = new ArrayList<>();

                            int realDirectCount = WorkerFormationScopeResolver.countDirectFollowersForPlayer(srv, ownerId);
                            int virtualDirectCount = 0;
                            for (VirtualTravellerRecord vr : vwm.getRecordsView()) {
                                if (ownerId.equals(vr.followOwnerUUID) && vr.escortLeaderUUID == null && vr.isHiredFollower)
                                    virtualDirectCount++;
                            }
                            WorkerFormationScopeKey playerScope = new WorkerFormationScopeKey(
                                    WorkerFormationScopeKey.Type.PLAYER, ownerId);
                            leaders.add(new FormationLeaderInfo(null, player.getName(),
                                    realDirectCount + virtualDirectCount,
                                    player.posX, player.posZ, true, false));
                            settingsByScope.put(playerScope.asMapKey(), fsd.getSettings(playerScope));

                            for (WorldServer ws : srv.worlds) {
                                List<EntityTravellerWorker> realLeaders = ws.getEntities(EntityTravellerWorker.class, w ->
                                        w != null && !w.isDead && w.isHiredFollower()
                                                && ownerId.equals(w.getFollowOwnerId())
                                                && w.getLeaderFollowerCount() > 0);
                                for (EntityTravellerWorker ldr : realLeaders) {
                                    WorkerFormationScopeKey ldrScope = new WorkerFormationScopeKey(
                                            WorkerFormationScopeKey.Type.WORKER, ldr.getUniqueID());
                                    int fc = ldr.getLeaderFollowerCount();
                                    for (VirtualTravellerRecord vr : vwm.getRecordsView()) {
                                        if (ownerId.equals(vr.followOwnerUUID) && ldr.getUniqueID().equals(vr.escortLeaderUUID))
                                            fc++;
                                    }
                                    FormationLeaderInfo info = new FormationLeaderInfo(ldr.getUniqueID(), ldr.getName(), fc,
                                            ldr.posX, ldr.posZ, false, false);
                                    info.cultureId = ldr.getCultureId();
                                    info.textureIndex = ldr.getTextureIndex();
                                    info.ownerColor = ldr.getOwnerMarkColor();
                                    info.entityId = ldr.getEntityId();
                                    leaders.add(info);
                                    settingsByScope.put(ldrScope.asMapKey(), fsd.getSettings(ldrScope));
                                }
                            }

                            List<FormationLeaderInfo> virtualLeaders = vwm.buildLeaderList(ownerId, player.posX, player.posZ, overworld);
                            for (FormationLeaderInfo vl : virtualLeaders) {
                                boolean alreadyAdded = leaders.stream().anyMatch(l -> l.uuid != null && l.uuid.equals(vl.uuid));
                                if (!alreadyAdded) {
                                    leaders.add(vl);
                                    WorkerFormationScopeKey vScope = new WorkerFormationScopeKey(
                                            WorkerFormationScopeKey.Type.WORKER, vl.uuid);
                                    settingsByScope.put(vScope.asMapKey(), fsd.getSettings(vScope));
                                }
                            }

                            leaders.sort((a, b) -> {
                                if (a.isPlayer) return -1;
                                if (b.isPlayer) return 1;
                                double da = Math.sqrt(Math.pow(a.posX - player.posX, 2) + Math.pow(a.posZ - player.posZ, 2));
                                double db = Math.sqrt(Math.pow(b.posX - player.posX, 2) + Math.pow(b.posZ - player.posZ, 2));
                                return Double.compare(da, db);
                            });

                            WorkerFormationScopeResolver.OpenScope openScope =
                                    WorkerFormationScopeResolver.resolveOpenScope(worker, player);
                            WorkerFormationScopeKey initialScope = openScope != null ? openScope.scope : playerScope;

                            TravellerFollowNet.CHANNEL.sendTo(
                                    new MsgOpenFormationHubGui(worker.getEntityId(), initialScope, leaders, settingsByScope),
                                    player
                            );
                        }
                        return;

                    case VILLAGE_RELATIONS:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_VILLAGE_RELATIONS);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.VILLAGE_RELATIONS),
                                player
                        );
                        return;

                    default:
                        return;
                }

            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenWorkerSubGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenWorkerSubGui msg, MessageContext ctx) {
            // client side: do nothing
            return null;
        }
    }

}
