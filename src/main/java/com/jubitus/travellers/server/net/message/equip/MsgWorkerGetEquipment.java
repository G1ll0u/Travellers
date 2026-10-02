package com.jubitus.travellers.server.net.message.equip;

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

    public MsgWorkerGetEquipment() {}

    public MsgWorkerGetEquipment(UUID workerUUID, boolean stayThere,
                                  com.jubitus.travellers.unit.equipment.EquipTargetKind kind, BlockPos homePos) {
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
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerUUID.getMostSignificantBits());
        buf.writeLong(workerUUID.getLeastSignificantBits());
        byte flags = (byte) ((stayThere ? 1 : 0) | (kind.ordinal() << 1));
        buf.writeByte(flags);
        buf.writeLong(homePos.toLong());
    }

    private static boolean isValidEquipTarget(EntityPlayerMP player, BlockPos pos,
                                               com.jubitus.travellers.unit.equipment.EquipTargetKind kind) {
        if (pos == null) return false;
        java.util.UUID ownerId = player.getUniqueID();
        switch (kind) {
            case CONTROLLED_VILLAGE:
                if (!com.jubitus.travellers.config.TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled) return false;
                return com.jubitus.travellers.world.mill.MillSettlements
                        .controlledBy(player.world, player).contains(pos);
            case STABLE:
                for (com.jubitus.travellers.world.zone.PlayerZoneRecord r :
                        com.jubitus.travellers.world.zone.PlayerZoneManager.get(player.world)
                                .getByOwnerAndType(ownerId, com.jubitus.travellers.world.zone.ZoneType.STABLE)) {
                    if (pos.equals(r.center)) return true;
                }
                return false;
            case LONE_BARRACK:
            default:
                for (com.jubitus.travellers.world.zone.PlayerZoneRecord r :
                        com.jubitus.travellers.world.zone.PlayerZoneManager.get(player.world)
                                .getByOwnerAndType(ownerId, com.jubitus.travellers.world.zone.ZoneType.BARRACK)) {
                    if (pos.equals(r.center)) return true;
                }
                return false;
        }
    }

    public static class Handler implements IMessageHandler<MsgWorkerGetEquipment, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGetEquipment msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                net.minecraft.server.MinecraftServer srv = player.getServer();
                if (srv == null) return;

                UUID ownerId = player.getUniqueID();

                // Validate the target: must be one of the player's controlled villages (whole-village)
                // or one of the player's lone barracks. Blocks ordinary villages and others' barracks.
                if (!isValidEquipTarget(player, msg.homePos, msg.kind)) return;

                // Try real entity first
                for (WorldServer ws : srv.worlds) {
                    net.minecraft.entity.Entity e = ws.getEntityFromUuid(msg.workerUUID);
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker worker = (EntityTravellerWorker) e;
                        if (!worker.isHiredFollower() || !ownerId.equals(worker.getFollowOwnerId())) return;
                        if (com.jubitus.travellers.follow.group.WorkerOrderGuard.isBusy(worker)) {
                            com.jubitus.travellers.follow.group.WorkerOrderGuard.notifyRefused(player, worker);
                            return;
                        }
                        com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, worker.getUniqueID());
                        worker.startGetEquipMission(msg.homePos, msg.stayThere, msg.kind);
                        return;
                    }
                }

                // Try virtual record
                WorldServer overworld = srv.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                VirtualTravellerRecord r = vwm.getRecord(msg.workerUUID);
                if (r == null || !ownerId.equals(r.followOwnerUUID)) return;
                if (com.jubitus.travellers.follow.group.WorkerOrderGuard.isBusy(r)) {
                    com.jubitus.travellers.follow.group.WorkerOrderGuard.notifyRefused(player, r);
                    return;
                }

                EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
                temp.readFromNBT(r.entityNbt);
                temp.setUniqueId(r.uuid);
                temp.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);
                com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, r.uuid);
                temp.clearEscortLeader();
                temp.startGetEquipMission(msg.homePos, msg.stayThere, msg.kind);

                NBTTagCompound updatedNbt = new NBTTagCompound();
                temp.writeToNBT(updatedNbt);
                r.entityNbt = updatedNbt;
                r.workerMode = "GET_EQUIPMENT";
                r.goOrderActive = true;
                r.goal = msg.homePos;
                vwm.markDirty();
            });
            return null;
        }
    }
}
