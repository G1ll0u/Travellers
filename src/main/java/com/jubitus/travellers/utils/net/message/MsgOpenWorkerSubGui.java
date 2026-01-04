package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
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

    public enum Type {
        TRANSPORT,
        ORDERS,
        RECRUIT,
        INVENTORY,
        BEHAVIOR, // NEW
        HUB
    }


    private int entityId;
    private Type type;


    public MsgOpenWorkerSubGui() {}

    public MsgOpenWorkerSubGui(int entityId, Type type) {
        this.entityId = entityId;
        this.type = type;
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
                        // Only allow transport when hired by *this* player
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You must hire this worker to use Transport."
                            ));
                            return;
                        }

                        guiId = TravellersGuiIds.WORKER;

                        // Optional but recommended: sync hire state before GUI builds
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgSyncWorkerHireState(worker),
                                player
                        );
                        break;


                    case ORDERS:
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only give orders to a worker you've hired."
                            ));
                            return;
                        }

                        guiId = TravellersGuiIds.WORKER_ORDERS;

                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgSyncWorkerHireState(worker),
                                player
                        );
                        break;


                    case INVENTORY:
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only access the pack of a worker you've hired."
                            ));
                            return;
                        }
                        guiId = TravellersGuiIds.WORKER_INV;
                        break;

                    case BEHAVIOR:
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only manage behavior of a worker you've hired."
                            ));
                            return;
                        }

                        guiId = TravellersGuiIds.WORKER_BEHAVIOR;

                        TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHireState(worker), player);
                        TravellerFollowNet.CHANNEL.sendTo(buildHostileListPacket(worker), player);
                        break;


                    case RECRUIT:
                        if (!isHiredByPlayer(worker, player)) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only recruit with a worker you've hired."
                            ));
                            return;
                        }

                        guiId = TravellersGuiIds.WORKER_RECRUIT;

                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgSyncWorkerHireState(worker),
                                player
                        );
                        break;


                    case HUB:
                        guiId = TravellersGuiIds.WORKER_HUB;
                        break;



                    default:
                        return;
                }


                player.openGui(
                        Travellers.INSTANCE,
                        guiId,
                        world,
                        worker.getEntityId(),
                        0, 0
                );
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
    static MsgSyncWorkerHostileList buildHostileListPacket(EntityTravellerWorker worker) {
        EntityTravellerWorker leader = worker.getGroupLeaderOrSelf();

        java.util.List<String> out = new java.util.ArrayList<>();
        MinecraftServer srv = worker.world.getMinecraftServer();
        if (srv != null) {
            for (UUID id : leader.getEffectiveHostilePlayers()) {
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

}
