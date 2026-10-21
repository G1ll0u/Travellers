package com.jubitus.travellers.unit.equipment.net;

import com.jubitus.travellers.unit.equipment.EquipOrders;
import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
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

import java.nio.ByteBuffer;
import java.util.UUID;

public class MsgWorkerGetEquipment implements IMessage {

    private UUID workerUUID;
    private boolean stayThere;
    private com.jubitus.travellers.unit.equipment.EquipTargetKind kind;
    private BlockPos homePos;
    private int loadout;

    public MsgWorkerGetEquipment() {}

    public MsgWorkerGetEquipment(UUID workerUUID, boolean stayThere,
                                  com.jubitus.travellers.unit.equipment.EquipTargetKind kind, BlockPos homePos,
                                  com.jubitus.travellers.unit.equipment.EquipLoadout loadout) {
        this.loadout = loadout.bits;
        this.workerUUID = workerUUID;
        this.stayThere = stayThere;
        this.kind = kind;
        this.homePos = homePos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        workerUUID = new UUID(msb, lsb);
        byte flags = buf.readByte();
        stayThere = (flags & 1) != 0;
        kind = com.jubitus.travellers.unit.equipment.EquipTargetKind.values()[(flags >> 1) & 0x0F];
        homePos = BlockPos.fromLong(buf.readLong());
        loadout = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerUUID.getMostSignificantBits());
        buf.writeLong(workerUUID.getLeastSignificantBits());
        byte flags = (byte) ((stayThere ? 1 : 0) | (kind.ordinal() << 1));
        buf.writeByte(flags);
        buf.writeLong(homePos.toLong());
        buf.writeByte(loadout);
    }

    public static class Handler implements IMessageHandler<MsgWorkerGetEquipment, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGetEquipment msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                EquipOrders.start(player, msg.workerUUID, msg.homePos, msg.stayThere, msg.kind,
                        com.jubitus.travellers.unit.equipment.EquipLoadout.of(msg.loadout), true);
            });
            return null;
        }
    }
}
