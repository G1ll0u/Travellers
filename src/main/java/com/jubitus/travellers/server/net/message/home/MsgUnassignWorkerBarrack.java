package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** Client -> server: clear a worker's (live or virtual) barrack assignment. */
public class MsgUnassignWorkerBarrack implements IMessage {

    private UUID workerUUID;

    public MsgUnassignWorkerBarrack() {}

    public MsgUnassignWorkerBarrack(UUID workerUUID) {
        this.workerUUID = workerUUID;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerUUID = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerUUID.getMostSignificantBits());
        buf.writeLong(workerUUID.getLeastSignificantBits());
    }

    public static class Handler implements IMessageHandler<MsgUnassignWorkerBarrack, IMessage> {
        @Override
        public IMessage onMessage(MsgUnassignWorkerBarrack msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID ownerId = player.getUniqueID();
                WorldServer world = player.getServerWorld();
                int dim = world.provider.getDimension();

                Entity e = world.getEntityFromUuid(msg.workerUUID);
                if (e instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!ownerId.equals(w.getFollowOwnerId())) return;
                    w.setAssignedHome(null, dim, "", TravellerHomeManager.HomeType.LONE_BARRACK);
                    TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
                } else {
                    VirtualTravellerManager vwm = VirtualTravellerManager.get(world);
                    VirtualTravellerRecord rec = vwm.getRecord(msg.workerUUID);
                    if (rec == null || !ownerId.equals(rec.followOwnerUUID)) return;
                    EntityTravellerWorker temp = new EntityTravellerWorker(world);
                    temp.readFromNBT(rec.entityNbt);
                    temp.setUniqueId(rec.uuid);
                    temp.setAssignedHome(null, dim, "", TravellerHomeManager.HomeType.LONE_BARRACK);
                    NBTTagCompound updated = new NBTTagCompound();
                    temp.writeToNBT(updated);
                    rec.entityNbt = updated;
                    vwm.markDirty();
                }

                TravellerFollowNet.CHANNEL.sendTo(MsgWorkerBarracksData.buildFor(player), player);
            });
            return null;
        }
    }
}
