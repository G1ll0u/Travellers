package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;


public class MsgWorkerGoToPos implements IMessage {
    private int workerId;
    private int x, y, z;

    public MsgWorkerGoToPos() {}

    public MsgWorkerGoToPos(int workerId, BlockPos pos) {
        this.workerId = workerId;
        this.x = pos.getX();
        this.y = pos.getY();
        this.z = pos.getZ();
    }

    @Override public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<MsgWorkerGoToPos, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGoToPos msg, MessageContext ctx) {
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

                // A worker busy on a mission can't take a Go order (loaded + virtual share this rule).
                if (com.jubitus.travellers.follow.group.WorkerOrderGuard.isBusy(w)) {
                    com.jubitus.travellers.follow.group.WorkerOrderGuard.notifyRefused(player, w);
                    return;
                }

                BlockPos target = new BlockPos(msg.x, msg.y, msg.z);

                // Option: clamp Y au sol côté serveur
                BlockPos top = player.world.getHeight(new BlockPos(target.getX(), 0, target.getZ()));
                target = new BlockPos(target.getX(), top.getY(), target.getZ());

                com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, w.getUniqueID());
                BlockPos from = w.getPosition();
                w.startGoToDestination(target, w.dimension);
                com.jubitus.travellers.notify.MissionNotifications.goOrdered(player, w.getUniqueID(), from, target,
                        NotificationDefs.GOTO_GOING_TO_POS);
            });

            return null;
        }
    }
}
