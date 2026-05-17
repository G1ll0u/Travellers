package com.jubitus.travellers.server.net.message.recruit;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.flooz.TravellerMoney;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
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
                player.sendMessage(new TextComponentString(TextFormatting.YELLOW + "I'm busy right now."));
                return;
            }

            if (msg.route == null || msg.route.isEmpty()) return;

            // NEW: reject villages that are too close (server-side authority)
// NEW: reject villages that are too close (server-side authority)
            BlockPos origin = player.getPosition();
            int minD = TravellersModConfig.recruitMinVillageDistance;

            for (BlockPos v : msg.route) {
                int d = EntityTravellerWorker.dist2D(origin, v);
                if (d < minD) {
                    player.sendMessage(new TextComponentString(
                            TextFormatting.RED + "That village is too close to recruit from. Minimum distance: " + minD + "m"
                    ));
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
                player.sendMessage(new TextComponentString(
                        TextFormatting.RED + "Recruit fee: " + MillCommonUtilities.getShortPrice(fee)
                ));
                TravellerMoney.syncPlayerInventory(player);
                return;
            }

            if (!TravellerMoney.trySpendPlayerMoney(player, fee)) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.RED + "Recruit fee payment failed. Try again."
                ));
                return;
            }

            // Start mission with EXACT villages chosen
            worker.startRecruitMission(player, msg.route, null);
        }
    }
}
