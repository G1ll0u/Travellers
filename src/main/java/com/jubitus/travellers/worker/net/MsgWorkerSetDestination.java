package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.worker.inventory.ContainerWorker;
import com.jubitus.travellers.unit.DeliveryJob;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.village.MillMoney;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.millenaire.common.utilities.MillCommonUtilities;

public class MsgWorkerSetDestination implements IMessage {
    private boolean donate;
    private int entityId;
    private BlockPos targetCenter;
    private String label; // for chat

    public MsgWorkerSetDestination() {
    }


    public MsgWorkerSetDestination(int entityId, BlockPos targetCenter, String label, boolean donate) {
        this.entityId = entityId;
        this.targetCenter = targetCenter;
        this.label = label;
        this.donate = donate;
    }


    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        this.targetCenter = new BlockPos(x, y, z);
        this.label = ByteBufUtils.readUTF8String(buf);
        this.donate = buf.readBoolean(); // NEW
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeInt(targetCenter.getX());
        buf.writeInt(targetCenter.getY());
        buf.writeInt(targetCenter.getZ());
        ByteBufUtils.writeUTF8String(buf, label);
        buf.writeBoolean(donate); // NEW
    }

    // ---------- server handler ----------

    public static class Handler implements IMessageHandler<MsgWorkerSetDestination, IMessage> {

        @Override
        public IMessage onMessage(MsgWorkerSetDestination msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private void handle(MsgWorkerSetDestination msg, EntityPlayerMP player) {
            World world = player.world;
            Entity e = world.getEntityByID(msg.entityId);
            if (!(e instanceof EntityTravellerWorker)) return;

            EntityTravellerWorker worker = (EntityTravellerWorker) e;

            if (worker.getDistanceSq(player) > 64.0D) {
                return; // too far
            }
            if (com.jubitus.travellers.follow.group.WorkerOrderGuard.isBusy(worker)) {
                com.jubitus.travellers.follow.group.WorkerOrderGuard.notifyRefused(player, worker);
                return;
            }

            BlockPos villageCenter = msg.targetCenter;

            // Distance worker -> village
            BlockPos workerPos = worker.getPosition();
            double dx = villageCenter.getX() + 0.5D - workerPos.getX();
            double dz = villageCenter.getZ() + 0.5D - workerPos.getZ();
            int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

            int usedSlots = worker.getUsedTravelSlots();
            int cost = DeliveryJob.computeTravelCost(distance, usedSlots);

            if (cost > 0) {
                int playerMoney = MillMoney.countPlayerMoney(player);

                if (playerMoney < cost) {
                    NotificationService.notify(player, NotificationDefs.GOTO_NOT_ENOUGH_MONEY,
                            MillMoney.format(cost));
                    MillMoney.syncPlayerInventory(player);
                    return;
                }

                if (!MillMoney.trySpendPlayerMoney(player, cost)) {
                    NotificationService.notify(player, NotificationDefs.GOTO_PAYMENT_FAILED);
                    return;
                }
            }
            if (player.openContainer instanceof ContainerWorker) {
                ((ContainerWorker) player.openContainer).markJobStarted();
            }
            com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, worker.getUniqueID());
            worker.delivery.start(player, villageCenter, msg.donate);

            String costStr = MillMoney.format(cost);

            if (cost <= 0) {
                NotificationService.notify(player, NotificationDefs.GOTO_DELIVERY_STARTED_FREE, msg.label);
            } else if (msg.donate) {
                NotificationService.notify(player, NotificationDefs.GOTO_DELIVERY_STARTED_DONATE, msg.label, costStr);
            } else {
                NotificationService.notify(player, NotificationDefs.GOTO_DELIVERY_STARTED_PAID, msg.label, costStr);
            }
        }

    }
}
