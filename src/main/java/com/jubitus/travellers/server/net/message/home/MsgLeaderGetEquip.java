package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.mill.EquipTargetKind;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import com.jubitus.travellers.world.mill.MillControlledVillageHelper;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
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

    public MsgLeaderGetEquip() {}

    public MsgLeaderGetEquip(UUID leaderUUID, BlockPos destPos, EquipTargetKind kind) {
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
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuidMost);
        buf.writeLong(uuidLeast);
        buf.writeLong(destPosLong);
        buf.writeByte(kind.ordinal());
    }

    private static boolean isValidEquipTarget(EntityPlayerMP player, BlockPos pos, EquipTargetKind kind) {
        if (pos == null) return false;
        UUID ownerId = player.getUniqueID();
        switch (kind) {
            case CONTROLLED_VILLAGE:
                if (!TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled) return false;
                return MillControlledVillageHelper.getControlledVillageCenters(player.world, player).contains(pos);
            case STABLE:
                for (PlayerZoneRecord r : PlayerZoneManager.get(player.world).getByOwnerAndType(ownerId, ZoneType.STABLE)) {
                    if (pos.equals(r.center)) return true;
                }
                return false;
            case LONE_BARRACK:
            default:
                for (PlayerZoneRecord r : PlayerZoneManager.get(player.world).getByOwnerAndType(ownerId, ZoneType.BARRACK)) {
                    if (pos.equals(r.center)) return true;
                }
                return false;
        }
    }

    public static class Handler implements IMessageHandler<MsgLeaderGetEquip, IMessage> {
        @Override
        public IMessage onMessage(MsgLeaderGetEquip msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                net.minecraft.server.MinecraftServer srv = player.getServer();
                if (srv == null) return;

                UUID leaderUUID = new UUID(msg.uuidMost, msg.uuidLeast);
                UUID ownerId = player.getUniqueID();
                BlockPos destPos = BlockPos.fromLong(msg.destPosLong);

                if (!isValidEquipTarget(player, destPos, msg.kind)) return;

                // Try real entity first
                for (WorldServer ws : srv.worlds) {
                    net.minecraft.entity.Entity e = ws.getEntityFromUuid(leaderUUID);
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker leader = (EntityTravellerWorker) e;
                        if (!leader.isHiredFollower() || !ownerId.equals(leader.getFollowOwnerId())) return;
                        if (com.jubitus.travellers.server.group.WorkerOrderGuard.isBusy(leader)) {
                            com.jubitus.travellers.notify.NotificationService.notify(player,
                                    com.jubitus.travellers.notify.NotificationDefs.ORDER_WORKER_BUSY);
                            return;
                        }
                        leader.startGetEquipMission(destPos, true, msg.kind);
                        return;
                    }
                }

                // Try virtual record
                WorldServer overworld = srv.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                VirtualTravellerRecord r = vwm.getRecord(leaderUUID);
                if (r == null || !ownerId.equals(r.followOwnerUUID)) return;
                if (com.jubitus.travellers.server.group.WorkerOrderGuard.isBusy(r)) {
                    com.jubitus.travellers.notify.NotificationService.notify(player,
                            com.jubitus.travellers.notify.NotificationDefs.ORDER_WORKER_BUSY);
                    return;
                }

                EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
                temp.readFromNBT(r.entityNbt);
                temp.setUniqueId(r.uuid);
                temp.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);
                temp.startGetEquipMission(destPos, true, msg.kind);

                NBTTagCompound updatedNbt = new NBTTagCompound();
                temp.writeToNBT(updatedNbt);
                r.entityNbt = updatedNbt;
                r.workerMode = "GET_EQUIPMENT";
                r.goOrderActive = true;
                r.goal = destPos;
                vwm.markDirty();
            });
            return null;
        }
    }
}
