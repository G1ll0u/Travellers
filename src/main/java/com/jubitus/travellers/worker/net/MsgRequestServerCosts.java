package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.net.TravellerFollowNet;
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
                                TravellersConfig.WORKER.workerHireCostDeniers,
                                TravellersConfig.RECRUITMENT.recruitFeeFlat,
                                TravellersConfig.RECRUITMENT.recruitFeePerBlock,
                                TravellersConfig.RECRUITMENT.recruitFeePerVillage
                        ),
                        player
                );

            });
            return null;
        }
    }
}
