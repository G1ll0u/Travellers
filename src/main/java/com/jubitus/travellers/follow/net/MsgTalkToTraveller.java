package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumHand;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: "Talk" chosen on the follow choice screen; runs the traveller's plain talk. */
public class MsgTalkToTraveller implements IMessage {

    private static final double MAX_DISTANCE = 8.0D;

    private int entityId;

    public MsgTalkToTraveller() {
    }

    public MsgTalkToTraveller(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgTalkToTraveller, IMessage> {
        @Override
        public IMessage onMessage(MsgTalkToTraveller msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                Entity e = player.world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTraveller) || e instanceof EntityTravellerWorker || e instanceof EntityTravellerRaider) return;
                if (player.getDistanceSq(e) > MAX_DISTANCE * MAX_DISTANCE) return;
                ((EntityTraveller) e).talkTo(player, EnumHand.MAIN_HAND);
            });
            return null;
        }
    }
}
