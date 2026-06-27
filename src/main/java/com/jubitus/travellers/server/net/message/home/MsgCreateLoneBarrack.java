package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;

public class MsgCreateLoneBarrack implements IMessage {

    private static final int MIN_VILLAGE_DIST = 192;
    private static final int MIN_LONE_DIST = 128;

    private String name;
    private int x, y, z;
    private int halfWidth;
    private int halfDepth;
    private int halfHeight;

    public MsgCreateLoneBarrack() {}

    public MsgCreateLoneBarrack(String name, BlockPos center, int halfWidth, int halfDepth, int halfHeight) {
        this.name = name;
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
        this.halfWidth = halfWidth;
        this.halfDepth = halfDepth;
        this.halfHeight = halfHeight;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        halfWidth = buf.readInt();
        halfDepth = buf.readInt();
        halfHeight = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name == null ? "" : name);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeInt(halfWidth);
        buf.writeInt(halfDepth);
        buf.writeInt(halfHeight);
    }

    public static class Handler implements IMessageHandler<MsgCreateLoneBarrack, IMessage> {
        @Override
        public IMessage onMessage(MsgCreateLoneBarrack msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                BlockPos center = new BlockPos(msg.x, msg.y, msg.z);
                LoneBarrackManager mgr = LoneBarrackManager.get(player.world);

                if (mgr.overlapsExisting(center, msg.halfWidth, msg.halfDepth)) {
                    TravellerFollowNet.CHANNEL.sendTo(
                        new MsgLoneBarrackResult(false, "travellers.lone_barrack.error.overlaps"), player);
                    return;
                }

                List<BlockPos> coffers = LoneBarrackManager.findCoffers(player.world, center, msg.halfWidth, msg.halfDepth, msg.halfHeight);
                if (coffers.isEmpty()) {
                    TravellerFollowNet.CHANNEL.sendTo(
                        new MsgLoneBarrackResult(false, "travellers.lone_barrack.error.no_coffers"), player);
                    return;
                }

                List<BlockPos> beds = LoneBarrackManager.findBeds(player.world, center, msg.halfWidth, msg.halfDepth, msg.halfHeight);
                if (beds.isEmpty()) {
                    TravellerFollowNet.CHANNEL.sendTo(
                        new MsgLoneBarrackResult(false, "travellers.lone_barrack.error.no_beds"), player);
                    return;
                }

                LoneBarrackRecord record = new LoneBarrackRecord(
                    player.getUniqueID(), msg.name, center, msg.halfWidth, msg.halfDepth, msg.halfHeight);
                record.cofferPositions.addAll(coffers);
                record.bedPositions.addAll(beds);
                mgr.add(record);

                TravellerFollowNet.CHANNEL.sendTo(
                    new MsgLoneBarrackResult(true, "travellers.lone_barrack.created"), player);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgCreateLoneBarrack, IMessage> {
        @Override
        public IMessage onMessage(MsgCreateLoneBarrack msg, MessageContext ctx) { return null; }
    }
}
