package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.client.gui.TravellersGuiIds;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgOpenWorkerSubGui implements IMessage {

    public enum Type {
        TRANSPORT,
        ORDERS,
        INVENTORY // NEW
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
        this.type = Type.values()[buf.readByte()];
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
                        guiId = TravellersGuiIds.WORKER;
                        break;

                    case ORDERS:
                        guiId = TravellersGuiIds.WORKER_ORDERS;

                        // *** NEW: make sure client knows hire/follow state BEFORE Orders GUI builds ***
                        TravellerFollowNet.CHANNEL.sendTo(
                                new MsgSyncWorkerHireState(worker),
                                player
                        );
                        break;

                    case INVENTORY:
                        if (!worker.isHiredFollower()
                                || !player.getUniqueID().equals(worker.getFollowOwnerId())) {
                            player.sendMessage(new TextComponentString(
                                    TextFormatting.RED + "You can only access the pack of a worker you've hired."
                            ));
                            return;
                        }
                        guiId = TravellersGuiIds.WORKER_INV;

                        // Optional: also sync here if you want inventory GUI to know follow state
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

}
