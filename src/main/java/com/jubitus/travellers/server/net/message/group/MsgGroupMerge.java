package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgGroupMerge implements IMessage {

    private long srcMost, srcLeast;
    private boolean hasTarget;
    private long tgtMost, tgtLeast;

    public MsgGroupMerge() {}

    public MsgGroupMerge(UUID sourceLeaderUUID, UUID targetLeaderUUID) {
        srcMost = sourceLeaderUUID.getMostSignificantBits();
        srcLeast = sourceLeaderUUID.getLeastSignificantBits();
        hasTarget = targetLeaderUUID != null;
        if (hasTarget) {
            tgtMost = targetLeaderUUID.getMostSignificantBits();
            tgtLeast = targetLeaderUUID.getLeastSignificantBits();
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        srcMost = buf.readLong();
        srcLeast = buf.readLong();
        hasTarget = buf.readBoolean();
        if (hasTarget) {
            tgtMost = buf.readLong();
            tgtLeast = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(srcMost);
        buf.writeLong(srcLeast);
        buf.writeBoolean(hasTarget);
        if (hasTarget) {
            buf.writeLong(tgtMost);
            buf.writeLong(tgtLeast);
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupMerge, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupMerge msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID sourceUUID = new UUID(msg.srcMost, msg.srcLeast);
                UUID targetUUID = msg.hasTarget ? new UUID(msg.tgtMost, msg.tgtLeast) : null;
                WorkerGroupService.merge(player, sourceUUID, targetUUID);
            });
            return null;
        }
    }
}
