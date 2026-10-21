package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgSyncWorkerHireState implements IMessage {

    private int entityId;
    private boolean hired;
    private UUID ownerId;
    private boolean followOrderActive;
    private String homeVillageName = "";
    // -1 = never set
    private int equipRole = -1;

    public MsgSyncWorkerHireState() {
    }

    public MsgSyncWorkerHireState(EntityTravellerWorker worker) {
        this.entityId = worker.getEntityId();
        this.hired = worker.isHiredFollower();
        this.ownerId = worker.getFollowOwnerId();
        this.followOrderActive = worker.isFollowOrderActive();
        BlockPos home = worker.getHomeVillage();
        String name = home != null ? MillSettlements.nameOrNull(worker.world, home) : null;
        this.homeVillageName = name != null ? name : "";
        if (worker.getEquipRole() != null) this.equipRole = worker.getEquipRole().bits;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.hired = buf.readBoolean();

        boolean hasOwner = buf.readBoolean();
        if (hasOwner) {
            long msb = buf.readLong();
            long lsb = buf.readLong();
            this.ownerId = new UUID(msb, lsb);
        } else {
            this.ownerId = null;
        }

        this.followOrderActive = buf.readBoolean();
        this.homeVillageName = ByteBufUtils.readUTF8String(buf);
        this.equipRole = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeBoolean(hired);

        if (ownerId != null) {
            buf.writeBoolean(true);
            buf.writeLong(ownerId.getMostSignificantBits());
            buf.writeLong(ownerId.getLeastSignificantBits());
        } else {
            buf.writeBoolean(false);
        }

        buf.writeBoolean(followOrderActive);
        ByteBufUtils.writeUTF8String(buf, homeVillageName);
        buf.writeByte(equipRole);
    }

    public static class Handler implements IMessageHandler<MsgSyncWorkerHireState, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHireState msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                World world = Minecraft.getMinecraft().world;
                if (world == null) return;

                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // Update the client-side fields so your GUI sees the right state
                if (msg.hired && msg.ownerId != null) {
                    w.followOwnerId = msg.ownerId;
                    w.hiredFollower = true;
                } else {
                    w.followOwnerId = null;
                    w.hiredFollower = false;
                }

                w.setFollowOrderActive(msg.followOrderActive);
                w.clientHomeVillageName = msg.homeVillageName;
                w.setEquipRole(msg.equipRole < 0 ? null
                        : com.jubitus.travellers.unit.equipment.EquipLoadout.of(msg.equipRole));
            });
            return null;
        }
    }

}
