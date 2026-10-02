package com.jubitus.travellers.zone.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.net.TravellerFollowNet;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.ZoneAssignments;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.zone.ZoneTypeDef;
import com.jubitus.travellers.zone.ZoneTypeDefs;
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

                String zoneName = (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;
                ZoneAssignments.AssignResult res = ZoneAssignments.assign(player, msg.workerUUID, zone);
                if (res == ZoneAssignments.AssignResult.NO_BED) {
                    NotificationService.notify(player, NotificationDefs.DUTY_NO_BED, zoneName);
                    return;
                }
                if (res == ZoneAssignments.AssignResult.BUSY) {
                    NotificationService.notify(player, NotificationDefs.ORDER_WORKER_BUSY);
                    return;
                }
                if (res != ZoneAssignments.AssignResult.OK) return;

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
