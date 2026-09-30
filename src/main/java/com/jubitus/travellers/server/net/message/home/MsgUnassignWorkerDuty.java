package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.ZoneAssignments;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** Client -> server: clear a worker's (live or virtual) duty assignment. This is the sole "recall"
 * mechanic for Duty (see the decision that duty is persistent, GUI-only, no separate order). */
public class MsgUnassignWorkerDuty implements IMessage {

    private UUID workerUUID;

    public MsgUnassignWorkerDuty() {}

    public MsgUnassignWorkerDuty(UUID workerUUID) {
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

    public static class Handler implements IMessageHandler<MsgUnassignWorkerDuty, IMessage> {
        @Override
        public IMessage onMessage(MsgUnassignWorkerDuty msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID ownerId = player.getUniqueID();
                WorldServer world = player.getServerWorld();
                BlockPos dutyCenter = null;
                Entity e = world.getEntityFromUuid(msg.workerUUID);
                if (e instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!ownerId.equals(w.getFollowOwnerId())) return;
                    dutyCenter = w.getDutyZoneCenter();
                } else {
                    VirtualTravellerRecord rec = VirtualTravellerManager.get(world.getMinecraftServer().getWorld(0))
                            .getRecord(msg.workerUUID);
                    if (rec == null || !ownerId.equals(rec.followOwnerUUID)) return;
                    dutyCenter = rec.dutyZoneCenter;
                }
                // The Stable bed is also its home (pinned at assign), so releasing the zone clears both.
                if (dutyCenter != null) ZoneAssignments.release(world.getMinecraftServer(), msg.workerUUID, dutyCenter);

                TravellerFollowNet.CHANNEL.sendTo(MsgWorkerZoneData.buildFor(player), player);
            });
            return null;
        }

    }

    public static class NoopHandler implements IMessageHandler<MsgUnassignWorkerDuty, IMessage> {
        @Override public IMessage onMessage(MsgUnassignWorkerDuty msg, MessageContext ctx) { return null; }
    }
}
