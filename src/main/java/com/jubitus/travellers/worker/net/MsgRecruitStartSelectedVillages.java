package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.config.TravellersConfig;

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

import java.util.ArrayList;
import java.util.List;

public class MsgRecruitStartSelectedVillages implements IMessage {
    private int workerId;
    private List<BlockPos> route;

    public MsgRecruitStartSelectedVillages() {
    }

    public MsgRecruitStartSelectedVillages(int workerId, List<BlockPos> route) {
        this.workerId = workerId;
        this.route = route;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        int n = buf.readInt();
        route = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            route.add(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
        buf.writeInt(route != null ? route.size() : 0);
        if (route != null) {
            for (BlockPos p : route) {
                buf.writeInt(p.getX());
                buf.writeInt(p.getY());
                buf.writeInt(p.getZ());
            }
        }
    }

    public static class Handler implements IMessageHandler<MsgRecruitStartSelectedVillages, IMessage> {
        @Override
        public IMessage onMessage(MsgRecruitStartSelectedVillages msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(player, msg));
            return null;
        }

        private void handle(EntityPlayerMP player, MsgRecruitStartSelectedVillages msg) {
            World world = player.world;
            Entity e = world.getEntityByID(msg.workerId);
            if (!(e instanceof EntityTravellerWorker)) return;

            EntityTravellerWorker worker = (EntityTravellerWorker) e;

            // ownership / state checks
            if (!worker.isHiredFollower()
                    || worker.getFollowOwnerId() == null
                    || !worker.getFollowOwnerId().equals(player.getUniqueID())) return;

            if (!worker.canStartRecruitMission()) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_BUSY);
                return;
            }

            if (msg.route == null || msg.route.isEmpty()) return;

            // reject villages that are too close (server-side authority)
            BlockPos origin = player.getPosition();
            int minD = TravellersConfig.RECRUITMENT.recruitMinVillageDistance;

            for (BlockPos v : msg.route) {
                int d = EntityTravellerWorker.dist2D(origin, v);
                if (d < minD) {
                    NotificationService.notify(player, NotificationDefs.RECRUIT_VILLAGE_TOO_CLOSE, String.valueOf(minD));
                    return;
                }
            }



            // SERVER recompute fee (never trust client)
            BlockPos start = player.getPosition();
            BlockPos end = player.getPosition();

            int dist = EntityTravellerWorker.computeRouteDistance2D(start, msg.route, end);
            int fee = EntityTravellerWorker.computeRecruitFee(dist, msg.route.size());

            int money = TravellerMoney.countPlayerMoney(player);
            if (money < fee) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_ROUTE_NOT_ENOUGH_FEE,
                        MillCommonUtilities.getShortPrice(fee));
                TravellerMoney.syncPlayerInventory(player);
                return;
            }

            if (!TravellerMoney.trySpendPlayerMoney(player, fee)) {
                NotificationService.notify(player, NotificationDefs.RECRUIT_PAYMENT_FAILED);
                return;
            }

            // Start mission with EXACT villages chosen
            com.jubitus.travellers.follow.group.WorkerGroupService.detachForMission(player, worker.getUniqueID());
            worker.startRecruitMission(player, msg.route, null);
        }
    }
}
