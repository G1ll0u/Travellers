package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.gui.TravellersGuiIds;
import com.jubitus.travellers.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.MsgOpenFormationHubGui;
import com.jubitus.travellers.zone.net.MsgSyncWorkerHome;
import com.jubitus.travellers.combat.net.MsgSyncWorkerHostileList;
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
            for (UUID id : com.jubitus.travellers.combat.PlayerEnemyRelations.get(player.world)
                    .getHostiles(player.getUniqueID())) {
                GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(id);
                out.add(gp != null && gp.getName() != null ? gp.getName() : id.toString());
            }
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return new MsgSyncWorkerHostileList(out);
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
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_TRANSPORT);
                            return;
                        }
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        player.openGui(
                                Travellers.INSTANCE,
                                TravellersGuiIds.WORKER,
                                world,
                                worker.getEntityId(),
                                0, 0
                        );
                        return;

                    case INVENTORY:
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
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
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_BEHAVIOR);
                            return;
                        }
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellersNetwork.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.BEHAVIOR),
                                player
                        );
                        return;

                    case RECRUIT:
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_RECRUIT);
                            return;
                        }
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellersNetwork.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.RECRUIT),
                                player
                        );
                        return;

                    case HOME:
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_HOME);
                            return;
                        }

                        TravellersNetwork.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HOME),
                                player
                        );
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHome(worker), player);
                        return;

                    case HUB:
                        // The hub shows the hire state and the "Lead" follower count itself, so both
                        // have to be authoritative before it opens.
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellersNetwork.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HUB),
                                player
                        );
                        WorkerGroupService.syncLeaderFollowerCount(player, worker.getUniqueID());
                        return;

                    case GO:
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_GO);
                            return;
                        }
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellersNetwork.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.GO),
                                player
                        );
                        return;

                    case FORMATION:
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
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
                        if (!WorkerLookup.isOwnedBy(worker, player.getUniqueID())) {
                            NotificationService.notify(player, NotificationDefs.WORKER_NOT_HIRED_VILLAGE_RELATIONS);
                            return;
                        }
                        TravellersNetwork.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellersNetwork.CHANNEL.sendTo(
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

}
