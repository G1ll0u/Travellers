package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;

public class MsgWorkerDeleteHome implements IMessage {

    private BlockPos pos;

    public MsgWorkerDeleteHome() {}

    public MsgWorkerDeleteHome(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
    }

    public static class Handler implements IMessageHandler<MsgWorkerDeleteHome, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerDeleteHome msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                TravellerHomeManager mgr = TravellerHomeManager.get(player.world);
                // Only allow deleting CHEST type homes (not barracks)
                TravellerHomeManager.HomeChest hc = mgr.findHome(
                        player.getUniqueID(), player.world.provider.getDimension(), msg.pos);
                if (hc == null || hc.type == TravellerHomeManager.HomeType.BARRACKS) {
                    return;
                }
                boolean removed = mgr.removeHomeChest(player, msg.pos);
                if (removed) {
                    // Refresh home list for client
                    java.util.List<TravellerHomeManager.HomeChest> homes =
                            new ArrayList<>(mgr.getHomesOfPlayerFull(player));
                    TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerHomeList(-1, homes), player);
                }
            });
            return null;
        }
    }
}
