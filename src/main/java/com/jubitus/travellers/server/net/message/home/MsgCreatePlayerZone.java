package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.zone.BlockRoleRequirement;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import com.jubitus.travellers.world.zone.ZoneTypeDef;
import com.jubitus.travellers.world.zone.ZoneTypeDefs;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;

/** Client -> server: create a new player zone of the chosen {@link ZoneType}. Validation is data-driven
 * off {@link ZoneTypeDef#requiredRoles} so a future zone type never needs a new hardcoded check here. */
public class MsgCreatePlayerZone implements IMessage {

    private String name;
    private int x, y, z;
    private int halfWidth;
    private int halfDepth;
    private int halfHeight;
    private ZoneType type;

    public MsgCreatePlayerZone() {}

    public MsgCreatePlayerZone(String name, BlockPos center, int halfWidth, int halfDepth, int halfHeight, ZoneType type) {
        this.name = name;
        this.x = center.getX();
        this.y = center.getY();
        this.z = center.getZ();
        this.halfWidth = halfWidth;
        this.halfDepth = halfDepth;
        this.halfHeight = halfHeight;
        this.type = type;
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
        type = ZoneType.values()[buf.readByte() & 0xFF];
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
        buf.writeByte(type.ordinal());
    }

    public static class Handler implements IMessageHandler<MsgCreatePlayerZone, IMessage> {
        @Override
        public IMessage onMessage(MsgCreatePlayerZone msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                BlockPos center = new BlockPos(msg.x, msg.y, msg.z);
                PlayerZoneManager mgr = PlayerZoneManager.get(player.world);

                if (mgr.overlapsExisting(center, msg.halfWidth, msg.halfDepth)) {
                    TravellerFollowNet.CHANNEL.sendTo(
                        new MsgZoneActionResult(false, "travellers.lone_barrack.error.overlaps"), player);
                    return;
                }

                ZoneTypeDef def = ZoneTypeDefs.get(msg.type);
                PlayerZoneRecord record = new PlayerZoneRecord(
                    player.getUniqueID(), msg.name, center, msg.halfWidth, msg.halfDepth, msg.halfHeight, msg.type);

                for (BlockRoleRequirement req : def.requiredRoles) {
                    List<BlockPos> found = PlayerZoneManager.scanRole(
                        player.world, center, msg.halfWidth, msg.halfDepth, msg.halfHeight, req.scanner);
                    if (found.size() < req.minCount) {
                        TravellerFollowNet.CHANNEL.sendTo(
                            new MsgZoneActionResult(false, req.missingErrorKey), player);
                        return;
                    }
                    record.setRolePositions(req.role, found);
                }

                mgr.add(record);

                TravellerFollowNet.CHANNEL.sendTo(
                    new MsgZoneActionResult(true, "travellers.lone_barrack.created"), player);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgCreatePlayerZone, IMessage> {
        @Override
        public IMessage onMessage(MsgCreatePlayerZone msg, MessageContext ctx) { return null; }
    }
}
