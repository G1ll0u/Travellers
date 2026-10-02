package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgSyncServerCosts implements IMessage {
    private int workerHireCostDeniers;

    private int recruitFeeFlat;
    private double recruitFeePerBlock;
    private int recruitFeePerVillage;

    public MsgSyncServerCosts() {}

    public MsgSyncServerCosts(int hire, int flat, double perBlock, int perVillage) {
        this.workerHireCostDeniers = hire;
        this.recruitFeeFlat = flat;
        this.recruitFeePerBlock = perBlock;
        this.recruitFeePerVillage = perVillage;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerHireCostDeniers = buf.readInt();

        recruitFeeFlat = buf.readInt();
        recruitFeePerBlock = buf.readDouble();
        recruitFeePerVillage = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerHireCostDeniers);

        buf.writeInt(recruitFeeFlat);
        buf.writeDouble(recruitFeePerBlock);
        buf.writeInt(recruitFeePerVillage);
    }

    public static class Handler implements IMessageHandler<MsgSyncServerCosts, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncServerCosts msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {

                TravellersDerivedConfig.workerHireCostDeniersServerCache = msg.workerHireCostDeniers;

                TravellersDerivedConfig.recruitFeeFlatServerCache = msg.recruitFeeFlat;
                TravellersDerivedConfig.recruitFeePerBlockServerCache = msg.recruitFeePerBlock;
                TravellersDerivedConfig.recruitFeePerVillageServerCache = msg.recruitFeePerVillage;
            });
            return null;
        }
    }
}

