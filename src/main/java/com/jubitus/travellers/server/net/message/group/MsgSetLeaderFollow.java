package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** "Resume marching" (follow = true) or "Halt here" (false) for a leader and its group, loaded or virtual. */
public class MsgSetLeaderFollow implements IMessage {

    private long uuidMost, uuidLeast;
    private boolean follow;

    public MsgSetLeaderFollow() {}

    public MsgSetLeaderFollow(UUID leaderUUID, boolean follow) {
        this.uuidMost = leaderUUID.getMostSignificantBits();
        this.uuidLeast = leaderUUID.getLeastSignificantBits();
        this.follow = follow;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        uuidMost = buf.readLong();
        uuidLeast = buf.readLong();
        follow = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuidMost);
        buf.writeLong(uuidLeast);
        buf.writeBoolean(follow);
    }

    public static class Handler implements IMessageHandler<MsgSetLeaderFollow, IMessage> {
        @Override
        public IMessage onMessage(MsgSetLeaderFollow msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    WorkerGroupService.setLeaderFollow(player, new UUID(msg.uuidMost, msg.uuidLeast), msg.follow));
            return null;
        }
    }
}
