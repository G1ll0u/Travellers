package com.jubitus.travellers.utils.net.message.workerplayer;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgRequestServerCosts implements IMessage {
    public MsgRequestServerCosts() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<MsgRequestServerCosts, IMessage> {
        @Override
        public IMessage onMessage(MsgRequestServerCosts msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                TravellerFollowNet.CHANNEL.sendTo(
                        new MsgSyncServerCosts(
                                TravellersModConfig.workerHireCostDeniers,
                                TravellersModConfig.recruitFeeFlat,
                                TravellersModConfig.recruitFeePerBlock,
                                TravellersModConfig.recruitFeePerVillage
                        ),
                        player
                );

            });
            return null;
        }
    }
}
