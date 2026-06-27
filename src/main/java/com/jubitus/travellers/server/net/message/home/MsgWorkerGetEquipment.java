package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
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

import java.nio.ByteBuffer;
import java.util.UUID;

public class MsgWorkerGetEquipment implements IMessage {

    private UUID workerUUID;
    private boolean stayThere;
    private boolean useWholeVillage;
    private BlockPos homePos;

    public MsgWorkerGetEquipment() {}

    public MsgWorkerGetEquipment(UUID workerUUID, boolean stayThere, boolean useWholeVillage, BlockPos homePos) {
        this.workerUUID = workerUUID;
        this.stayThere = stayThere;
        this.useWholeVillage = useWholeVillage;
        this.homePos = homePos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        workerUUID = new UUID(msb, lsb);
        byte flags = buf.readByte();
        stayThere = (flags & 1) != 0;
        useWholeVillage = (flags & 2) != 0;
        homePos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerUUID.getMostSignificantBits());
        buf.writeLong(workerUUID.getLeastSignificantBits());
        byte flags = (byte) ((stayThere ? 1 : 0) | (useWholeVillage ? 2 : 0));
        buf.writeByte(flags);
        buf.writeLong(homePos.toLong());
    }

    private static boolean isValidEquipTarget(EntityPlayerMP player, BlockPos pos, boolean useWholeVillage) {
        if (pos == null) return false;
        java.util.UUID ownerId = player.getUniqueID();
        if (useWholeVillage) {
            if (!com.jubitus.travellers.TravellersModConfig.controlledVillageFeatureEnabled) return false;
            return com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper
                    .getControlledVillageCenters(player.world, player).contains(pos);
        }
        for (com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord r :
                com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager.get(player.world).getByOwner(ownerId)) {
            if (pos.equals(r.center)) return true;
        }
        return false;
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
                if (!isValidEquipTarget(player, msg.homePos, msg.useWholeVillage)) return;

                // Try real entity first
                for (WorldServer ws : srv.worlds) {
                    net.minecraft.entity.Entity e = ws.getEntityFromUuid(msg.workerUUID);
                    if (e instanceof EntityTravellerWorker) {
                        EntityTravellerWorker worker = (EntityTravellerWorker) e;
                        if (!worker.isHiredFollower() || !ownerId.equals(worker.getFollowOwnerId())) return;
                        worker.startGetEquipMission(msg.homePos, msg.stayThere, msg.useWholeVillage);
                        return;
                    }
                }

                // Try virtual record
                WorldServer overworld = srv.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                VirtualTravellerRecord r = vwm.getRecord(msg.workerUUID);
                if (r == null || !ownerId.equals(r.followOwnerUUID)) return;

                EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
                temp.readFromNBT(r.entityNbt);
                temp.setUniqueId(r.uuid);
                temp.setPositionAndRotation(r.posX, r.posY, r.posZ, r.rotYaw, 0);
                temp.startGetEquipMission(msg.homePos, msg.stayThere, msg.useWholeVillage);

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
