package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.village.TravellerMoney;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.util.List;

public class MsgRecruitWorkers implements IMessage {
    private int workerId;

    public MsgRecruitWorkers() {
    }

    public MsgRecruitWorkers(int workerId) {
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

    public static class Handler implements IMessageHandler<MsgRecruitWorkers, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitWorkers msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(player, msg.workerId));
            return null;
        }

        private void handle(EntityPlayerMP player, int workerId) {
            World world = player.world;
            Entity e = world.getEntityByID(workerId);
            if (!(e instanceof EntityTravellerWorker)) return;

            EntityTravellerWorker worker = (EntityTravellerWorker) e;

            // Must be hired by this player
            if (!worker.isHiredFollower()
                    || worker.getFollowOwnerId() == null
                    || !worker.getFollowOwnerId().equals(player.getUniqueID())) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_NOT_YOUR_WORKER);
                return;
            }

            // Must be idle (not job, not returning, not recruiting)
            if (!worker.canStartRecruitMission()) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_BUSY);
                return;
            }

            // Pick villages (simple: nearest within radius)
            List<BlockPos> villages = worker.pickRecruitVillages(player);
            if (villages.isEmpty()) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_NO_VILLAGES_IN_RANGE);
                return;
            }

            BlockPos returnPos = worker.getApproximateSurfacePosNear(player);

            int routeDist = EntityTravellerWorker.computeRouteDistance2D(player.getPosition(), villages, returnPos);
            int fee = EntityTravellerWorker.computeRecruitFee(routeDist, villages.size());

            int money = TravellerMoney.countPlayerMoney(player);
            if (money < fee) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_NOT_ENOUGH_FEE,
                        MillCommonUtilities.getShortPrice(fee), MillCommonUtilities.getShortPrice(money));
                TravellerMoney.syncPlayerInventory(player);
                return;
            }

            if (!TravellerMoney.trySpendPlayerMoney(player, fee)) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_PAYMENT_FAILED);
                return;
            }

            NotificationService.notify(player, NotificationDefs.RECRUIT_MISSION_STARTED,
                    MillCommonUtilities.getShortPrice(fee));

            com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, worker.getUniqueID());
            worker.startRecruitMission(player, villages, returnPos);
        }
    }
}
