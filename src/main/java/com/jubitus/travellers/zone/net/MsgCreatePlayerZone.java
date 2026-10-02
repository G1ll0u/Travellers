package com.jubitus.travellers.zone.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.zone.BlockRoleRequirement;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.zone.ZoneTypeDef;
import com.jubitus.travellers.zone.ZoneTypeDefs;
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

    private static final double MAX_PLAYER_DISTANCE = 32.0D;
    private static final int MAX_NAME_LENGTH = 32;

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
        int t = buf.readByte() & 0xFF;
        type = t < ZoneType.values().length ? ZoneType.values()[t] : null;
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

                // Never trust the client: bounded box, next to the player, fully loaded (the role scan
                // below must never load or generate a chunk).
                int maxHalf = TravellersConfig.WORKER.zoneMaxHalfWidth;
                int maxHeight = TravellersConfig.WORKER.zoneMaxHalfHeight;
                if (msg.type == null
                        || msg.halfWidth < 1 || msg.halfWidth > maxHalf
                        || msg.halfDepth < 1 || msg.halfDepth > maxHalf
                        || msg.halfHeight < 1 || msg.halfHeight > maxHeight
                        || player.getDistanceSq(center) > MAX_PLAYER_DISTANCE * MAX_PLAYER_DISTANCE
                        || !player.world.isAreaLoaded(
                                center.add(-msg.halfWidth, -msg.halfHeight, -msg.halfDepth),
                                center.add(msg.halfWidth, msg.halfHeight, msg.halfDepth))) {
                    TravellersNetwork.CHANNEL.sendTo(
                        new MsgZoneActionResult(false, "travellers.lone_barrack.error.invalid"), player);
                    return;
                }
                if (msg.name != null && msg.name.length() > MAX_NAME_LENGTH) {
                    msg.name = msg.name.substring(0, MAX_NAME_LENGTH);
                }

                if (mgr.overlapsExisting(center, msg.halfWidth, msg.halfDepth)) {
                    TravellersNetwork.CHANNEL.sendTo(
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
                        TravellersNetwork.CHANNEL.sendTo(
                            new MsgZoneActionResult(false, req.missingErrorKey), player);
                        return;
                    }
                    record.setRolePositions(req.role, found);
                }

                mgr.add(record);

                TravellersNetwork.CHANNEL.sendTo(
                    new MsgZoneActionResult(true, "travellers.lone_barrack.created"), player);
            });
            return null;
        }
    }
}
