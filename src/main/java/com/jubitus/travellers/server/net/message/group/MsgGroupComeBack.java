package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgGroupComeBack implements IMessage {

    private boolean hasLeader;
    private long uuidMost, uuidLeast;

    public MsgGroupComeBack() {}

    public MsgGroupComeBack(UUID leaderUUID) {
        this.hasLeader = leaderUUID != null;
        if (leaderUUID != null) {
            this.uuidMost = leaderUUID.getMostSignificantBits();
            this.uuidLeast = leaderUUID.getLeastSignificantBits();
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hasLeader = buf.readBoolean();
        if (hasLeader) {
            uuidMost = buf.readLong();
            uuidLeast = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(hasLeader);
        if (hasLeader) {
            buf.writeLong(uuidMost);
            buf.writeLong(uuidLeast);
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupComeBack, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupComeBack msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!msg.hasLeader) return;
                UUID leaderUUID = new UUID(msg.uuidMost, msg.uuidLeast);
                WorkerGroupService.comeBackGroup(player, leaderUUID);
            });
            return null;
        }
    }
}
