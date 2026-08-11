package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.server.group.WorkerGroupService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgLeaderGoTo implements IMessage {

    private long uuidMost, uuidLeast;
    private int x, y, z;

    public MsgLeaderGoTo() {}

    public MsgLeaderGoTo(UUID leaderUUID, BlockPos pos) {
        uuidMost = leaderUUID.getMostSignificantBits();
        uuidLeast = leaderUUID.getLeastSignificantBits();
        x = pos.getX();
        y = pos.getY();
        z = pos.getZ();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        uuidMost = buf.readLong();
        uuidLeast = buf.readLong();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuidMost);
        buf.writeLong(uuidLeast);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<MsgLeaderGoTo, IMessage> {
        @Override
        public IMessage onMessage(MsgLeaderGoTo msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID leaderUUID = new UUID(msg.uuidMost, msg.uuidLeast);

                BlockPos target = new BlockPos(msg.x, msg.y, msg.z);
                BlockPos top = player.world.getHeight(new BlockPos(target.getX(), 0, target.getZ()));
                target = new BlockPos(target.getX(), top.getY(), target.getZ());

                WorkerGroupService.leaderGoTo(player, leaderUUID, target);
            });
            return null;
        }
    }
}
