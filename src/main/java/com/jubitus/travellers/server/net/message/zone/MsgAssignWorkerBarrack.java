package com.jubitus.travellers.server.net.message.zone;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneRoles;
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

/** Client -> server: assign a worker (live or virtual) to one of the player's lone barracks. */
public class MsgAssignWorkerBarrack implements IMessage {

    private UUID workerUUID;
    private BlockPos center;

    public MsgAssignWorkerBarrack() {}

    public MsgAssignWorkerBarrack(UUID workerUUID, BlockPos center) {
        this.workerUUID = workerUUID;
        this.center = center;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerUUID = new UUID(buf.readLong(), buf.readLong());
        center = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerUUID.getMostSignificantBits());
        buf.writeLong(workerUUID.getLeastSignificantBits());
        buf.writeLong(center.toLong());
    }

    public static class Handler implements IMessageHandler<MsgAssignWorkerBarrack, IMessage> {
        @Override
        public IMessage onMessage(MsgAssignWorkerBarrack msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID ownerId = player.getUniqueID();
                WorldServer world = player.getServerWorld();

                // Validate ownership of the target barrack
                PlayerZoneManager lbMgr = PlayerZoneManager.get(world);
                PlayerZoneRecord barrack = null;
                for (PlayerZoneRecord r : lbMgr.getByOwner(ownerId)) {
                    if (msg.center.equals(r.center)) { barrack = r; break; }
                }
                if (barrack == null) return;

                ZoneAssignments.AssignResult res = ZoneAssignments.assign(player, msg.workerUUID, barrack);
                if (res == ZoneAssignments.AssignResult.NO_BED) {
                    MsgWorkerBarracksData snap = MsgWorkerBarracksData.buildFor(player);
                    snap.notification = "travellers.worker.barracks.full";
                    TravellerFollowNet.CHANNEL.sendTo(snap, player);
                    return;
                }
                if (res == ZoneAssignments.AssignResult.BUSY) {
                    com.jubitus.travellers.notify.NotificationService.notify(player,
                            com.jubitus.travellers.notify.NotificationDefs.ORDER_WORKER_BUSY);
                }

                TravellerFollowNet.CHANNEL.sendTo(MsgWorkerBarracksData.buildFor(player), player);
            });
            return null;
        }
    }
}
