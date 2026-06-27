package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgDeleteLoneBarrack implements IMessage {

    private int x, y, z;

    public MsgDeleteLoneBarrack() {}

    public MsgDeleteLoneBarrack(BlockPos center) {
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<MsgDeleteLoneBarrack, IMessage> {
        @Override
        public IMessage onMessage(MsgDeleteLoneBarrack msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                BlockPos center = new BlockPos(msg.x, msg.y, msg.z);
                LoneBarrackManager mgr = LoneBarrackManager.get(player.world);
                // Ownership validated inside: only removes records owned by the requesting player
                for (LoneBarrackRecord r : mgr.getAll()) {
                    if (player.getUniqueID().equals(r.ownerUUID) && center.equals(r.center)) {
                        mgr.removeByOwnerAndCenter(player.getUniqueID(), center);
                        return;
                    }
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgDeleteLoneBarrack, IMessage> {
        @Override
        public IMessage onMessage(MsgDeleteLoneBarrack msg, MessageContext ctx) { return null; }
    }
}
