package com.jubitus.travellers.server.net.message.workergo;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
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
    private int index;

    public MsgWorkerGoTo() {}
    public MsgWorkerGoTo(int workerId, int index) {
        this.workerId = workerId;
        this.index = index;
    }

    @Override public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        index = buf.readInt();
    }
    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeInt(index);
    }

    public static class Handler implements IMessageHandler<MsgWorkerGoTo, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGoTo msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // Security: only owner can order
                if (!w.isHiredFollower() || w.getFollowOwnerId() == null ||
                        !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    return;
                }

                // Optional: block if job active
                if (w.isJobActive()) {
                    NotificationService.notify(player, NotificationDefs.GOTO_WORKER_BUSY);
                    return;
                }

                // Rebuild the same mixed list server-side and pick index
                // (Beginner-friendly and safe; avoids trusting client data.)
                List<MsgWorkerGoList.Entry> list = new ArrayList<>();

                BlockPos origin = player.getPosition();

                List<BlockPos> villages = MillVillageIndex.getAllVillageCenters(player.world);
                for (BlockPos v : villages) {
                    int dist = EntityTravellerWorker.dist2D(origin, v);
                    String label = MillVillageIndex.nameForApprox(player.world, v);
                    list.add(MsgWorkerGoList.Entry.village(v, label, dist));
                }

                LoneBarrackManager lbMgr = LoneBarrackManager.get(player.world);
                for (LoneBarrackRecord r : lbMgr.getByOwner(player.getUniqueID())) {
                    int dist = EntityTravellerWorker.dist2D(origin, r.center);
                    String label =(r.name != null && !r.name.isEmpty() ? r.name : "Barrack");
                    list.add(MsgWorkerGoList.Entry.home(r.center, label, dist));
                }

                list.sort(Comparator.comparingInt(a -> a.distance));

                if (msg.index < 0 || msg.index >= list.size()) return;

                BlockPos target = list.get(msg.index).pos;

                // Start travel order
                w.startGoToDestination(target, w.dimension);

                NotificationService.notify(player, NotificationDefs.GOTO_GOING_TO, list.get(msg.index).label);

            });

            return null;
        }
    }
}
