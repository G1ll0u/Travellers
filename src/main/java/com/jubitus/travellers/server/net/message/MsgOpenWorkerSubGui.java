package com.jubitus.travellers.server.net.message;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.MsgOpenFormationHubGui;
import com.jubitus.travellers.server.net.message.zone.MsgSyncWorkerHome;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHireState;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHostileList;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
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

    public static MsgSyncWorkerHostileList buildHostileListPacket(EntityPlayerMP player) {
        java.util.List<String> out = new java.util.ArrayList<>();
        MinecraftServer srv = player.getServer();
        if (srv != null) {
            for (UUID id : com.jubitus.travellers.world.data.PlayerEnemyRelations.get(player.world)
                    .getHostiles(player.getUniqueID())) {
                GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(id);
                out.add(gp != null && gp.getName() != null ? gp.getName() : id.toString());
            }
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return new MsgSyncWorkerHostileList(out);
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

                    case BEHAVIOR:
                        if (!isHiredByPlayer(worker, player)) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_BEHAVIOR);
                            return;
                        }
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
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
                        // The hub shows the hire state and the "Lead" follower count itself, so both
                        // have to be authoritative before it opens.
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HUB),
                                player
                        );
                        WorkerGroupService.syncLeaderFollowerCount(player, worker.getUniqueID());
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
                            WorkerFormationScopeResolver.OpenScope openScope =
                                    WorkerFormationScopeResolver.resolveOpenScope(worker, player);
                            WorkerFormationScopeKey initialScope = openScope != null ? openScope.scope
                                    : new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.PLAYER, player.getUniqueID());
                            com.jubitus.travellers.follow.group.WorkerGroupService.sendHubForWorker(
                                    player, worker.getEntityId(), initialScope);
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
