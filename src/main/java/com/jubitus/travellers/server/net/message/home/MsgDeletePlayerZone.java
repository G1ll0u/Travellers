package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneAssignments;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client -> server: delete one of the player's zones (any type) by its center. */
public class MsgDeletePlayerZone implements IMessage {

    private int x, y, z;

    public MsgDeletePlayerZone() {}

    public MsgDeletePlayerZone(BlockPos center) {
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

    public static class Handler implements IMessageHandler<MsgDeletePlayerZone, IMessage> {
        @Override
        public IMessage onMessage(MsgDeletePlayerZone msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                BlockPos center = new BlockPos(msg.x, msg.y, msg.z);
                PlayerZoneManager mgr = PlayerZoneManager.get(player.world);
                // Ownership validated inside: only removes records owned by the requesting player
                for (PlayerZoneRecord r : mgr.getAll()) {
                    if (player.getUniqueID().equals(r.ownerUUID) && center.equals(r.center)) {
                        ZoneAssignments.onZoneDeleted((WorldServer) player.world, r);
                        mgr.removeByOwnerAndCenter(player.getUniqueID(), center);
                        return;
                    }
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgDeletePlayerZone, IMessage> {
        @Override
        public IMessage onMessage(MsgDeletePlayerZone msg, MessageContext ctx) { return null; }
    }
}
