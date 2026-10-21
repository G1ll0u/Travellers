package com.jubitus.travellers.unit.equipment.net;

import com.jubitus.travellers.unit.equipment.EquipOrders;
import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.equipment.EquipLoadout;
import com.jubitus.travellers.unit.equipment.EquipTargetKind;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.village.MillSettlements;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgLeaderGetEquip implements IMessage {

    private long uuidMost, uuidLeast;
    private long destPosLong;
    private EquipTargetKind kind;
    // true = the group stays at the destination once equipped, false = it comes back to follow its owner
    private boolean stay;
    private int loadout;

    public MsgLeaderGetEquip() {}

    public MsgLeaderGetEquip(UUID leaderUUID, BlockPos destPos, EquipTargetKind kind, boolean stay, EquipLoadout loadout) {
        this.stay = stay;
        this.loadout = loadout.bits;
        this.uuidMost = leaderUUID.getMostSignificantBits();
        this.uuidLeast = leaderUUID.getLeastSignificantBits();
        this.destPosLong = destPos.toLong();
        this.kind = kind;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        uuidMost = buf.readLong();
        uuidLeast = buf.readLong();
        destPosLong = buf.readLong();
        kind = EquipTargetKind.values()[buf.readByte() & 0xFF];
        stay = buf.readBoolean();
        loadout = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuidMost);
        buf.writeLong(uuidLeast);
        buf.writeLong(destPosLong);
        buf.writeByte(kind.ordinal());
        buf.writeBoolean(stay);
        buf.writeByte(loadout);
    }

    public static class Handler implements IMessageHandler<MsgLeaderGetEquip, IMessage> {
        @Override
        public IMessage onMessage(MsgLeaderGetEquip msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                EquipOrders.start(player, new UUID(msg.uuidMost, msg.uuidLeast), BlockPos.fromLong(msg.destPosLong), msg.stay, msg.kind,
                        EquipLoadout.of(msg.loadout), false);
            });
            return null;
        }
    }
}
