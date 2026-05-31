package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
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
                TravellerHomeManager mgr = TravellerHomeManager.get(player.world);

                if (msg.workerId == -1) {
                    // leader mode: skip entity validation, return player homes directly
                    List<TravellerHomeManager.HomeChest> homes = new ArrayList<>(mgr.getHomesOfPlayerFull(player));
                    if (TravellersModConfig.controlledVillageFeatureEnabled) {
                        int dim = player.world.provider.getDimension();
                        for (Building b : MillControlledVillageHelper.getBarracksInControlledVillages(player.world, player)) {
                            BlockPos bPos = MillControlledVillageHelper.toBlockPos(b.getPos());
                            String bName = b.location != null && b.location.getIBuildingPlan() != null
                                    ? b.location.getIBuildingPlan().getNativeName() : "Barracks";
                            homes.add(new TravellerHomeManager.HomeChest(bPos, dim, player.getUniqueID(), bName,
                                    TravellerHomeManager.HomeType.BARRACKS));
                        }
                    }
                    TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerHomeList(-1, homes), player);
                    return;
                }

                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // security: only owner can query homes for this worker
                if (!w.isHiredFollower() || w.getFollowOwnerId() == null ||
                        !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    return;
                }

                List<TravellerHomeManager.HomeChest> homes = new ArrayList<>(mgr.getHomesOfPlayerFull(player));

                // Append barracks from controlled villages
                if (TravellersModConfig.controlledVillageFeatureEnabled) {
                    int dim = player.world.provider.getDimension();
                    for (Building b : MillControlledVillageHelper.getBarracksInControlledVillages(player.world, player)) {
                        BlockPos bPos = MillControlledVillageHelper.toBlockPos(b.getPos());
                        String bName = b.location != null && b.location.getIBuildingPlan() != null
                                ? b.location.getIBuildingPlan().getNativeName() : "Barracks";
                        homes.add(new TravellerHomeManager.HomeChest(bPos, dim, player.getUniqueID(), bName,
                                TravellerHomeManager.HomeType.BARRACKS));
                    }
                }

                TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerHomeList(w.getEntityId(), homes), player);
            });

            return null;
        }
    }
}
