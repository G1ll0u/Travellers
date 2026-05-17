package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgWorkerSetHome implements IMessage {
    private int workerId;
    private BlockPos pos;

    public MsgWorkerSetHome() {
    }

    public MsgWorkerSetHome(int workerId, BlockPos pos) {
        this.workerId = workerId;
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeLong(pos.toLong());
    }

    public static class Handler implements IMessageHandler<MsgWorkerSetHome, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerSetHome msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // security
                if (!w.isHiredFollower() || w.getFollowOwnerId() == null || !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    player.sendMessage(new TextComponentString(TextFormatting.RED + "Not your worker."));
                    return;
                }

                TravellerHomeManager mgr = TravellerHomeManager.get(player.world);
                TravellerHomeManager.HomeChest hc = mgr.findHome(player.getUniqueID(), player.world.provider.getDimension(), msg.pos);
                if (hc == null) {
                    player.sendMessage(new TextComponentString(TextFormatting.RED + "That home no longer exists."));
                    return;
                }

                w.setAssignedHome(hc.pos, hc.dimension, hc.name);

                // push a sync packet so buttons update instantly
                TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);

                player.sendMessage(new TextComponentString(TextFormatting.GREEN + "Home assigned: " + hc.name));
            });
            return null;
        }
    }
}
