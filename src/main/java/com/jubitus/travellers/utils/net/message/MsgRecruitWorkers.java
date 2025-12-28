package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
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

import java.util.List;

public class MsgRecruitWorkers implements IMessage {
    private int workerId;

    public MsgRecruitWorkers() {}
    public MsgRecruitWorkers(int workerId) { this.workerId = workerId; }

    @Override
    public void fromBytes(ByteBuf buf) { workerId = buf.readInt(); }

    @Override
    public void toBytes(ByteBuf buf) { buf.writeInt(workerId); }

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
                player.sendMessage(new TextComponentString(TextFormatting.RED + "You don't control this worker."));
                return;
            }

            // Must be idle (not job, not returning, not recruiting)
            if (!worker.canStartRecruitMission()) {
                player.sendMessage(new TextComponentString(TextFormatting.YELLOW + "I'm busy right now."));
                return;
            }

            // Pick villages (simple: nearest within radius)
            List<BlockPos> villages = worker.pickRecruitVillages(player);
            if (villages.isEmpty()) {
                player.sendMessage(new TextComponentString(TextFormatting.YELLOW + "No villages in range to recruit from."));
                return;
            }

            BlockPos returnPos = worker.getApproximateSurfacePosNear(player);

            int routeDist = EntityTravellerWorker.computeRouteDistance2D(player.getPosition(), villages, returnPos);
            int fee = EntityTravellerWorker.computeRecruitFee(routeDist, villages.size());

            int money = MillCommonUtilities.countMoney(player.inventory);
            if (money < fee) {
                player.sendMessage(new TextComponentString(
                        TextFormatting.RED + "Recruit fee: " + MillCommonUtilities.getShortPrice(fee) +
                                " (you have " + MillCommonUtilities.getShortPrice(money) + ")"
                ));
                return;
            }

// Pay
            MillCommonUtilities.changeMoney(player.inventory, -fee, player);
            player.inventory.markDirty();

            player.sendMessage(new TextComponentString(
                    TextFormatting.GREEN + "Recruit mission started. Fee paid: " + MillCommonUtilities.getShortPrice(fee)
            ));

            worker.startRecruitMission(player, villages, returnPos);
        }
    }
}
