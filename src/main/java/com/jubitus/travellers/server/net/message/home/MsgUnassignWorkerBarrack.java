package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.ZoneAssignments;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
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
                BlockPos home = null;
                Entity e = world.getEntityFromUuid(msg.workerUUID);
                if (e instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!ownerId.equals(w.getFollowOwnerId())) return;
                    home = w.getAssignedHomePos();
                } else {
                    VirtualTravellerRecord rec = VirtualTravellerManager.get(world.getMinecraftServer().getWorld(0))
                            .getRecord(msg.workerUUID);
                    if (rec == null || !ownerId.equals(rec.followOwnerUUID)) return;
                    NBTTagCompound nbt = rec.entityNbt;
                    if (nbt != null && nbt.hasKey("HomeX")) {
                        home = new BlockPos(nbt.getInteger("HomeX"), nbt.getInteger("HomeY"), nbt.getInteger("HomeZ"));
                    }
                }
                if (home != null) ZoneAssignments.release(world.getMinecraftServer(), msg.workerUUID, home);

                TravellerFollowNet.CHANNEL.sendTo(MsgWorkerBarracksData.buildFor(player), player);
            });
            return null;
        }
    }
}
