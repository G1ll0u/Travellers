package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravellerHomeManager;
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

                int dim = world.provider.getDimension();
                String name = (barrack.name == null || barrack.name.isEmpty()) ? "Barrack" : barrack.name;
                lbMgr.refreshCoffers(world, ownerId, barrack.center);

                // Capacity check: one worker per bed. The snapshot refreshes beds and gives current assignments.
                MsgWorkerBarracksData snap = MsgWorkerBarracksData.buildFor(player);
                int beds = barrack.getRolePositions(ZoneRoles.BED).size();
                int assigned = 0;
                boolean alreadyHere = false;
                for (MsgWorkerBarracksData.WorkerEntry we : snap.workers) {
                    if (barrack.center.equals(we.assignedCenter)) {
                        assigned++;
                        if (we.uuid.equals(msg.workerUUID)) alreadyHere = true;
                    }
                }
                if (!alreadyHere && assigned >= beds) {
                    snap.notification = "travellers.worker.barracks.full";
                    TravellerFollowNet.CHANNEL.sendTo(snap, player);
                    return;
                }

                // Live worker
                Entity e = world.getEntityFromUuid(msg.workerUUID);
                if (e instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!ownerId.equals(w.getFollowOwnerId())) return;
                    if (ZoneAssignments.isBoundElsewhere(w, barrack.center)) return;
                    w.setAssignedHome(barrack.center, dim, name, TravellerHomeManager.HomeType.LONE_BARRACK);
                    TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
                } else {
                    // Virtual worker
                    VirtualTravellerManager vwm = VirtualTravellerManager.get(world);
                    VirtualTravellerRecord rec = vwm.getRecord(msg.workerUUID);
                    if (rec == null || !ownerId.equals(rec.followOwnerUUID)) return;
                    EntityTravellerWorker temp = new EntityTravellerWorker(world);
                    temp.readFromNBT(rec.entityNbt);
                    temp.setUniqueId(rec.uuid);
                    if (ZoneAssignments.isBoundElsewhere(temp, barrack.center)) return;
                    temp.setAssignedHome(barrack.center, dim, name, TravellerHomeManager.HomeType.LONE_BARRACK);
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
