package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/**
 * Toggle a leader's follow state (ON = follow the owner, OFF = anchor in place), keeping its own
 * group. Works whether the leader is loaded or virtual. Delegates to
 * {@link WorkerGroupService#toggleLeaderFollow}.
 */
public class MsgToggleLeaderFollow implements IMessage {

    private long uuidMost, uuidLeast;

    public MsgToggleLeaderFollow() {}

    public MsgToggleLeaderFollow(UUID leaderUUID) {
        this.uuidMost = leaderUUID.getMostSignificantBits();
        this.uuidLeast = leaderUUID.getLeastSignificantBits();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        uuidMost = buf.readLong();
        uuidLeast = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuidMost);
        buf.writeLong(uuidLeast);
    }

    public static class Handler implements IMessageHandler<MsgToggleLeaderFollow, IMessage> {
        @Override
        public IMessage onMessage(MsgToggleLeaderFollow msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    WorkerGroupService.toggleLeaderFollow(player, new UUID(msg.uuidMost, msg.uuidLeast)));
            return null;
        }
    }
}
