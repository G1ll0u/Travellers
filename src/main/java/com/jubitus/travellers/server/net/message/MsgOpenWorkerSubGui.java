package com.jubitus.travellers.server.net.message;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSavedData;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeResolver;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.MsgOpenClientWorkerGui;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.formations.MsgOpenWorkerFormationGui;
import com.jubitus.travellers.server.net.message.home.MsgSyncWorkerHome;
import com.jubitus.travellers.server.net.message.home.MsgWorkerHomeList;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHireState;
import com.jubitus.travellers.server.net.message.workerplayer.MsgSyncWorkerHostileList;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You must hire this worker to use Transport."
                            ));
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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only access the pack of a worker you've hired."
                            ));
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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only give orders to a worker you've hired."
                            ));
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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only manage behavior of a worker you've hired."
                            ));
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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only recruit with a worker you've hired."
                            ));
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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only manage home for a worker you've hired."
                            ));
                            return;
                        }

                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HOME),
                                player
                        );
                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgWorkerHomeList(
                                        worker.getEntityId(),
                                        TravellerHomeManager.get(world).getHomesOfPlayerFull(player)
                                ),
                                player
                        );
                        return;

                    case HUB:
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenClientWorkerGui(worker.getEntityId(), MsgOpenClientWorkerGui.Type.HUB),
                                player
                        );
                        return;

                    case GO:
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only order a worker you've hired."
                            ));
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
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only manage formation of a worker you've hired."
                            ));
                            return;
                        }

                        WorkerFormationScopeResolver.OpenScope openScope =
                                WorkerFormationScopeResolver.resolveOpenScope(worker, player);

                        if (openScope == null) {
                            return;
                        }

                        WorkerFormationSettings formationSettings =
                                WorkerFormationSavedData.get(world).getSettings(openScope.scope);

                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgOpenWorkerFormationGui(worker.getEntityId(), openScope, formationSettings),
                                player
                        );
                        return;

                    case VILLAGE_RELATIONS:
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only manage village relations of a worker you've hired."
                            ));
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
