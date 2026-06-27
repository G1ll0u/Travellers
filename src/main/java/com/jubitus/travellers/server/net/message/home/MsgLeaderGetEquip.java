package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
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
    private boolean useWholeVillage;

    public MsgLeaderGetEquip() {}

    public MsgLeaderGetEquip(UUID leaderUUID, BlockPos destPos, boolean useWholeVillage) {
        this.uuidMost = leaderUUID.getMostSignificantBits();
        this.uuidLeast = leaderUUID.getLeastSignificantBits();
        this.destPosLong = destPos.toLong();
        this.useWholeVillage = useWholeVillage;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        uuidMost = buf.readLong();
        uuidLeast = buf.readLong();
        destPosLong = buf.readLong();
        useWholeVillage = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(uuidMost);
        buf.writeLong(uuidLeast);
        buf.writeLong(destPosLong);
        buf.writeBoolean(useWholeVillage);
    }

    private static boolean isValidEquipTarget(EntityPlayerMP player, BlockPos pos, boolean useWholeVillage) {
        if (pos == null) return false;
        UUID ownerId = player.getUniqueID();
        if (useWholeVillage) {
            if (!TravellersModConfig.controlledVillageFeatureEnabled) return false;
            return MillControlledVillageHelper.getControlledVillageCenters(player.world, player).contains(pos);
        }
        for (LoneBarrackRecord r : LoneBarrackManager.get(player.world).getByOwner(ownerId)) {
            if (pos.equals(r.center)) return true;
        }
        return false;
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

                if (!isValidEquipTarget(player, destPos, msg.useWholeVillage)) return;

                // Try real entity first
                for (WorldServer ws : srv.worlds) {
                    net.minecraft.entity.Entity e = ws.getEntityFromUuid(leaderUUID);
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker leader = (EntityTravellerWorker) e;
                        if (!leader.isHiredFollower() || !ownerId.equals(leader.getFollowOwnerId())) return;
                        leader.startGetEquipMission(destPos, true, msg.useWholeVillage);
                        return;
                    }
                }

                // Try virtual record
                WorldServer overworld = srv.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                VirtualTravellerRecord r = vwm.getRecord(leaderUUID);
                if (r == null || !ownerId.equals(r.followOwnerUUID)) return;

                EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
                temp.readFromNBT(r.entityNbt);
                temp.setUniqueId(r.uuid);
                temp.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);
                temp.startGetEquipMission(destPos, true, msg.useWholeVillage);

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
