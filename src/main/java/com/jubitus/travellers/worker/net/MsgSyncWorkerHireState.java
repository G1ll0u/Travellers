package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
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
    private int hireGroupSize = 1;
    private int hirePrice = -1;
    private boolean hireNeedsChief;
    private int freeBarrackBeds;
    private boolean hasBed;

    public MsgSyncWorkerHireState() {
    }

    /** {@code viewer} is the player this is sent to: the hire hints are about their chiefdom and their Barracks. */
    public MsgSyncWorkerHireState(EntityTravellerWorker worker, EntityPlayer viewer) {
        this.entityId = worker.getEntityId();
        this.hired = worker.isHiredFollower();
        this.ownerId = worker.getFollowOwnerId();
        this.followOrderActive = worker.isFollowOrderActive();
        BlockPos home = worker.getHomeVillage();
        String name = home != null ? MillSettlements.nameOrNull(worker.world, home) : null;
        this.homeVillageName = name != null ? name : "";
        if (worker.getEquipRole() != null) this.equipRole = worker.getEquipRole().bits;
        // Every zone assignment comes with a bed (ZoneAssignments).
        this.hasBed = worker.getZoneRef() != null;
        if (!hired && worker.world instanceof net.minecraft.world.WorldServer) {
            com.jubitus.travellers.worker.SpawnGroup group = com.jubitus.travellers.worker.SpawnGroup
                    .of((net.minecraft.world.WorldServer) worker.world, worker);
            this.hireGroupSize = group.size();
            this.hirePrice = group.hirePrice();
            this.hireNeedsChief = TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled
                    && TravellersConfig.CONTROLLED_VILLAGE.requireControlledVillageToHire
                    && MillSettlements.controlledBy(worker.world, viewer).isEmpty();
            WorldServer overworld = worker.world.getMinecraftServer().getWorld(0);
            for (PlayerZoneRecord zone : PlayerZoneManager.get(overworld)
                    .getByOwnerAndType(viewer.getUniqueID(), ZoneType.BARRACK)) {
                this.freeBarrackBeds += zone.countFreeBeds();
            }
        }
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
        this.hireGroupSize = buf.readInt();
        this.hirePrice = buf.readInt();
        this.hireNeedsChief = buf.readBoolean();
        this.freeBarrackBeds = buf.readInt();
        this.hasBed = buf.readBoolean();
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
        buf.writeInt(hireGroupSize);
        buf.writeInt(hirePrice);
        buf.writeBoolean(hireNeedsChief);
        buf.writeInt(freeBarrackBeds);
        buf.writeBoolean(hasBed);
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
                w.clientHireGroupSize = msg.hireGroupSize;
                w.clientHirePrice = msg.hirePrice;
                w.clientHireNeedsChief = msg.hireNeedsChief;
                w.clientFreeBarrackBeds = msg.freeBarrackBeds;
                w.clientHasBed = msg.hasBed;
                w.setEquipRole(msg.equipRole < 0 ? null
                        : com.jubitus.travellers.unit.equipment.EquipLoadout.of(msg.equipRole));
            });
            return null;
        }
    }

}
