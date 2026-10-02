package com.jubitus.travellers.server.net.message.zone;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class MsgWorkerGoHome implements IMessage {
    private int workerId;

    public MsgWorkerGoHome() {
    }

    public MsgWorkerGoHome(int workerId) {
        this.workerId = workerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
    }

    public static class Handler implements IMessageHandler<MsgWorkerGoHome, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerGoHome msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                if (!w.isHiredFollower() || w.getFollowOwnerId() == null || !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    NotificationService.notify(player, NotificationDefs.HOME_NOT_YOUR_WORKER);
                    return;
                }

                if (w.getAssignedHomePos() == null) {
                    NotificationService.notify(player, NotificationDefs.HOME_NONE_ASSIGNED);
                    return;
                }

                // A worker busy on a mission can't be sent home until it finishes.
                if (com.jubitus.travellers.server.group.WorkerOrderGuard.isBusy(w)) {
                    com.jubitus.travellers.server.group.WorkerOrderGuard.notifyRefused(player, w);
                    return;
                }

// Hard-disable follow so the Follow AI can’t keep running
                w.setFollowOrderActive(false);
                w.setReturnUsingFollow(false);    // critical with your current AI gates
                w.getNavigator().clearPath();     // stop current follow path immediately


// Now start go-home logic
                w.startGoHome();

            });
            return null;
        }
    }
}
