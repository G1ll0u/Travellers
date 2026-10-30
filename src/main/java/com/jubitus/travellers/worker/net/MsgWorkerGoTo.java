package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.village.MillSettlements;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MsgWorkerGoTo implements IMessage {
    private int workerId;
    private BlockPos place;

    public MsgWorkerGoTo() {}
    public MsgWorkerGoTo(int workerId, BlockPos place) {
        this.workerId = workerId;
        this.place = place;
    }

    @Override public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        place = BlockPos.fromLong(buf.readLong());
    }
    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeLong(place.toLong());
    }

    public static class Handler implements IMessageHandler<MsgWorkerGoTo, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGoTo msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                EntityTravellerWorker w = WorkerLookup.ownedById(player, msg.workerId);
                if (w == null) return;

                // A worker busy on a mission can't take a Go order (loaded + virtual share this rule).
                if (com.jubitus.travellers.follow.group.WorkerOrderGuard.isBusy(w)) {
                    com.jubitus.travellers.follow.group.WorkerOrderGuard.notifyRefused(player, w);
                    return;
                }

                MsgWorkerGoList.Entry chosen = null;
                for (MsgWorkerGoList.Entry e : MsgWorkerRequestGoList.placesFor(player)) {
                    if (e.pos.equals(msg.place)) chosen = e;
                }
                if (chosen == null) return;
                BlockPos target = chosen.pos;

                // Start travel order
                com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, w.getUniqueID());
                BlockPos from = w.getPosition();
                w.trips.startGoTo(target, w.dimension);
                com.jubitus.travellers.notify.MissionNotifications.goOrdered(player, w.getUniqueID(), from, target,
                        NotificationDefs.GOTO_GOING_TO, chosen.label);

            });

            return null;
        }
    }
}
