package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.worker.RecruitPlan;
import com.jubitus.travellers.worker.RecruitTour;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** Recruit screen → server: send this worker recruiting for these counts; the server replans and charges. */
public class MsgRecruitStart implements IMessage {

    private int workerId;
    private Map<String, Integer> wanted = new LinkedHashMap<>();

    public MsgRecruitStart() {
    }

    public MsgRecruitStart(int workerId, Map<String, Integer> wanted) {
        this.workerId = workerId;
        this.wanted = wanted;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        wanted = MsgRecruitPreviewRequest.readWanted(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        MsgRecruitPreviewRequest.writeWanted(buf, wanted);
    }

    public static class Handler implements IMessageHandler<MsgRecruitStart, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitStart msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(player, msg));
            return null;
        }

        private void handle(EntityPlayerMP player, MsgRecruitStart msg) {
            EntityTravellerWorker worker = WorkerLookup.ownedById(player, msg.workerId);
            if (worker == null) return;
            if (!worker.recruit.canStart()) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_BUSY);
                return;
            }
            if (worker.getEscortLeaderUUID() != null
                    || WorkerGroupService.countFollowers(player.getServer(), player.getUniqueID(), worker.getUniqueID()) == 0) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_NEEDS_GROUP);
                return;
            }
            RecruitPlan plan = RecruitPlan.build(player.getServerWorld(), player.getUniqueID(), player.getPosition(), msg.wanted);
            if (plan.isEmpty()) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_NO_VILLAGES_IN_RANGE);
                return;
            }
            int money = MillMoney.countPlayerMoney(player);
            if (money < plan.total()) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_NOT_ENOUGH_FEE,
                        MillMoney.format(plan.total()), MillMoney.format(money));
                MillMoney.syncPlayerInventory(player);
                return;
            }
            if (!MillMoney.trySpendPlayerMoney(player, plan.total())) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_PAYMENT_FAILED);
                return;
            }
            NotificationService.notify(player, NotificationDefs.RECRUIT_MISSION_STARTED, MillMoney.format(plan.total()));
            worker.purse.add(plan.purse);
            worker.recruit.start(RecruitTour.of(plan, player.getPosition(), player.world.getTotalWorldTime()));
        }
    }
}
