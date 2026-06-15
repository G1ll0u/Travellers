package com.jubitus.travellers.server.net.message.workergo;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MsgWorkerRequestGoList implements IMessage {
    private int workerId;

    public MsgWorkerRequestGoList() {}
    public MsgWorkerRequestGoList(int workerId) { this.workerId = workerId; }

    @Override public void fromBytes(ByteBuf buf) { workerId = buf.readInt(); }
    @Override public void toBytes(ByteBuf buf) { buf.writeInt(workerId); }

    public static class Handler implements IMessageHandler<MsgWorkerRequestGoList, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerRequestGoList msg, MessageContext ctx) {
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

                List<MsgWorkerGoList.Entry> out = new ArrayList<>();
                List<BlockPos> villages = MillVillageIndex.getAllVillageCenters(player.world);
                BlockPos origin = player.getPosition();

                java.util.Set<BlockPos> controlledVillages = TravellersModConfig.controlledVillageFeatureEnabled
                        ? MillControlledVillageHelper.getControlledVillageCenters(player.world, player)
                        : java.util.Collections.emptySet();

                for (BlockPos v : villages) {
                    int dist = EntityTravellerWorker.dist2D(origin, v);
                    String label = MillVillageIndex.nameForApprox(player.world, v);
                    out.add(controlledVillages.contains(v)
                            ? MsgWorkerGoList.Entry.controlledVillage(v, label, dist)
                            : MsgWorkerGoList.Entry.village(v, label, dist));
                }

                LoneBarrackManager lbMgr = LoneBarrackManager.get(player.world);
                for (LoneBarrackRecord r : lbMgr.getByOwner(player.getUniqueID())) {
                    int dist = EntityTravellerWorker.dist2D(origin, r.center);
                    String label = "[LB] " + (r.name != null && !r.name.isEmpty() ? r.name : "Barrack");
                    out.add(MsgWorkerGoList.Entry.home(r.center, label, dist));
                }

                out.sort(Comparator.comparingInt(a -> a.distance));
                TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerGoList(responseId, out), player);
            });

            return null;
        }
    }
}
