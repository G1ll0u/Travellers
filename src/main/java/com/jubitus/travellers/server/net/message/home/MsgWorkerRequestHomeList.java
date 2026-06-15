package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.millenaire.common.village.Building;

import java.util.ArrayList;
import java.util.List;

public class MsgWorkerRequestHomeList implements IMessage {
    private int workerId;

    public MsgWorkerRequestHomeList() {
    }

    public MsgWorkerRequestHomeList(int workerId) {
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

    public static class Handler implements IMessageHandler<MsgWorkerRequestHomeList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerRequestHomeList msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;

            player.getServerWorld().addScheduledTask(() -> {
                int responseId;
                if (msg.workerId == -1) {
                    responseId = -1;
                } else {
                    Entity e = player.world.getEntityByID(msg.workerId);
                    if (!(e instanceof EntityTravellerWorker)) return;
                    EntityTravellerWorker w = (EntityTravellerWorker) e;
                    if (!w.isHiredFollower() || w.getFollowOwnerId() == null ||
                            !w.getFollowOwnerId().equals(player.getUniqueID())) {
                        return;
                    }
                    responseId = w.getEntityId();
                }

                List<TravellerHomeManager.HomeChest> homes = new ArrayList<>();
                int dim = player.world.provider.getDimension();

                // Millénaire barracks from controlled villages
                if (TravellersModConfig.controlledVillageFeatureEnabled) {
                    for (Building b : MillControlledVillageHelper.getBarracksInControlledVillages(player.world, player)) {
                        BlockPos bPos = MillControlledVillageHelper.toBlockPos(b.getPos());
                        String bName = b.location != null && b.location.getIBuildingPlan() != null
                                ? b.location.getIBuildingPlan().getNativeName() : "Barracks";
                        homes.add(new TravellerHomeManager.HomeChest(bPos, dim, player.getUniqueID(), bName,
                                TravellerHomeManager.HomeType.BARRACKS));
                    }
                }

                // Lone barracks created by this player
                LoneBarrackManager lbMgr = LoneBarrackManager.get(player.world);
                for (LoneBarrackRecord r : lbMgr.getByOwner(player.getUniqueID())) {
                    homes.add(new TravellerHomeManager.HomeChest(r.center, dim, player.getUniqueID(),
                            r.name, TravellerHomeManager.HomeType.LONE_BARRACK));
                }

                TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerHomeList(responseId, homes), player);
            });

            return null;
        }
    }
}
