package com.jubitus.travellers.server.net.message.map;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Debug: teleport the sending player to a world position picked on the population map. */
public class MsgTeleportToPos implements IMessage {
    private int x, z;

    public MsgTeleportToPos() {}

    public MsgTeleportToPos(int x, int z) {
        this.x = x;
        this.z = z;
    }

    @Override public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        z = buf.readInt();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<MsgTeleportToPos, IMessage> {
        @Override
        public IMessage onMessage(MsgTeleportToPos msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                BlockPos top = player.world.getHeight(new BlockPos(msg.x, 0, msg.z));
                player.connection.setPlayerLocation(
                        msg.x + 0.5, top.getY(), msg.z + 0.5,
                        player.rotationYaw, player.rotationPitch);
            });

            return null;
        }
    }
}
