package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.server.group.WorkerGroupService;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import com.jubitus.travellers.world.zone.ZoneTypeDef;
import com.jubitus.travellers.world.zone.ZoneTypeDefs;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.Collections;
import java.util.UUID;

/**
 * Client -> server: assign a worker (live or virtual) to duty at one of the player's Stables. The
 * assignment message itself sends the worker there (no separate order needed) - see
 * {@code EntityTravellerWorker.startDutyTravel()}'s doc for why Duty can't just copy Home's
 * assign-then-click-Go-Home shape.
 */
public class MsgAssignWorkerDuty implements IMessage {

    private UUID workerUUID;
    private BlockPos center;

    public MsgAssignWorkerDuty() {}

    public MsgAssignWorkerDuty(UUID workerUUID, BlockPos center) {
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

    public static class Handler implements IMessageHandler<MsgAssignWorkerDuty, IMessage> {
        @Override
        public IMessage onMessage(MsgAssignWorkerDuty msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID ownerId = player.getUniqueID();
                WorldServer world = player.getServerWorld();
                MinecraftServer srv = player.getServer();
                if (srv == null) return;

                PlayerZoneRecord zone = null;
                for (PlayerZoneRecord r : PlayerZoneManager.get(world).getByOwnerAndType(ownerId, ZoneType.STABLE)) {
                    if (msg.center.equals(r.center)) { zone = r; break; }
                }
                if (zone == null) return;
                ZoneTypeDef def = ZoneTypeDefs.get(zone.type);
                String dutyKind = def.dutyKind;
                if (dutyKind == null) return;

                int dim = world.provider.getDimension();
                BlockPos zoneCenter = zone.center;
                String zoneName = (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;

                // A palfrenier needs a bed to sleep in - refuse the assignment if the Stable has none
                // free. Reserved immediately so two assignments in the same tick can't take one bed.
                com.jubitus.travellers.world.zone.stable.StableZoneData sdata =
                        (zone.data instanceof com.jubitus.travellers.world.zone.stable.StableZoneData)
                                ? (com.jubitus.travellers.world.zone.stable.StableZoneData) zone.data : null;
                BlockPos bed = zone.pickFreeBed();
                if (bed == null) {
                    NotificationService.notify(player, NotificationDefs.DUTY_NO_BED, zoneName);
                    return;
                }

                Entity e = world.getEntityFromUuid(msg.workerUUID);
                if (e instanceof EntityTravellerWorker) {
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!ownerId.equals(w.getFollowOwnerId())) return;
                    if (com.jubitus.travellers.world.zone.ZoneAssignments.isBoundElsewhere(w, zoneCenter)) return;
                    // A grouped worker (leader or follower) leaves its group automatically on
                    // assignment - there is no separate "unassign first" step in the Duty GUI, and
                    // the two movement systems (follow AI, duty travel) must never share one worker.
                    // A leader takes its whole group down with it (comeBackGroup); a plain follower
                    // just detaches (comeBackMembers). Both leave the worker itself untouched
                    // otherwise, so assignDuty below still applies to the same live entity.
                    if (w.isVisualLeader()) {
                        WorkerGroupService.comeBackGroup(player, w.getUniqueID());
                    } else if (w.hasEscortLeader()) {
                        WorkerGroupService.comeBackMembers(player, Collections.singletonList(w.getUniqueID()));
                    }
                    w.assignDuty(zoneCenter, dim, dutyKind);
                    w.startDutyTravel();
                    if (sdata != null) sdata.bedByWorker.put(w.getUniqueID(), bed);
                    // A Stable bed is also this palfrenier's home: it respawns beside it on death and
                    // it counts as housing, exactly like a Barrack bed. Overrides any prior Barrack
                    // home (Home and Duty are otherwise independent axes; Duty pins the home here).
                    w.setAssignedHome(zoneCenter, dim, zoneName,
                            com.jubitus.travellers.entity.entityai.travel.travelcommon.TravellerHomeManager.HomeType.LONE_BARRACK);
                    TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), player);
                } else {
                    WorldServer overworld = srv.getWorld(0);
                    VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
                    VirtualTravellerRecord rec = vwm.getRecord(msg.workerUUID);
                    if (rec == null || !ownerId.equals(rec.followOwnerUUID)) return;
                    EntityTravellerWorker probe = new EntityTravellerWorker(overworld);
                    probe.readFromNBT(rec.entityNbt);
                    if (com.jubitus.travellers.world.zone.ZoneAssignments.isBoundElsewhere(probe, zoneCenter)) return;
                    boolean isLeader = WorkerGroupService.countFollowers(srv, ownerId, rec.uuid) > 0;
                    if (isLeader) {
                        WorkerGroupService.comeBackGroup(player, rec.uuid);
                    } else if (rec.escortLeaderUUID != null) {
                        WorkerGroupService.comeBackMembers(player, Collections.singletonList(rec.uuid));
                    }

                    EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
                    temp.readFromNBT(rec.entityNbt);
                    temp.setUniqueId(rec.uuid);
                    temp.setPositionAndRotation(rec.posX, rec.posY, rec.posZ, rec.rotYaw, 0);
                    temp.assignDuty(zoneCenter, dim, dutyKind);
                    temp.startDutyTravel();
                    temp.setAssignedHome(zoneCenter, dim, zoneName,
                            com.jubitus.travellers.entity.entityai.travel.travelcommon.TravellerHomeManager.HomeType.LONE_BARRACK);

                    NBTTagCompound updated = new NBTTagCompound();
                    temp.writeToNBT(updated);
                    rec.entityNbt = updated;
                    rec.dutyKind = dutyKind;
                    rec.dutyZoneCenter = zoneCenter;
                    rec.followOrderActive = false;
                    // Mirror the in-transit travel state onto the record itself so the offscreen goal
                    // refresh actually walks it to a far, unloaded Stable (see
                    // VirtualTravellerManager.refreshGoal / virtualizeWorker).
                    rec.workerMode = temp.getMode().name();
                    rec.goingToDuty = true;
                    rec.goingHome = false;
                    rec.personalTravelGoal = zoneCenter;
                    rec.goal = zoneCenter;
                    vwm.markDirty();
                    if (sdata != null) sdata.bedByWorker.put(rec.uuid, bed);
                }

                PlayerZoneManager.get(world).markDirty();
                NotificationService.notify(player, NotificationDefs.DUTY_ASSIGNED, zoneName);
                TravellerFollowNet.CHANNEL.sendTo(MsgWorkerZoneData.buildFor(player), player);
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgAssignWorkerDuty, IMessage> {
        @Override public IMessage onMessage(MsgAssignWorkerDuty msg, MessageContext ctx) { return null; }
    }
}
