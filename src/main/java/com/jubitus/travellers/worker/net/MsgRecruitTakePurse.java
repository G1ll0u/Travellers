package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.WorkerLookup;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Report screen → server: the owner takes what they can carry of a returned recruiter's purse. */
public class MsgRecruitTakePurse implements IMessage {

    private int workerId;

    public MsgRecruitTakePurse() {
    }

    public MsgRecruitTakePurse(int workerId) {
        this.workerId = workerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
    }

    public static class Handler implements IMessageHandler<MsgRecruitTakePurse, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitTakePurse msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                EntityTravellerWorker worker = WorkerLookup.ownedById(player, msg.workerId);
                if (worker != null) worker.recruit.takePurse(player);
            });
            return null;
        }
    }
}
