package com.jubitus.travellers.utils.net.message.workergo;

import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
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
                Entity e = player.world.getEntityByID(msg.workerId);
                if (!(e instanceof EntityTravellerWorker)) return;
                EntityTravellerWorker w = (EntityTravellerWorker) e;

                // Security: only the owner can use this
                if (!w.isHiredFollower() || w.getFollowOwnerId() == null ||
                        !w.getFollowOwnerId().equals(player.getUniqueID())) {
                    return;
                }

                // Build mixed list: villages + homes
                List<MsgWorkerGoList.Entry> out = new ArrayList<>();

                // Villages
                List<BlockPos> villages = MillVillageIndex.getAllVillageCenters(player.world);
                BlockPos origin = player.getPosition();

                for (BlockPos v : villages) {
                    int dist = EntityTravellerWorker.dist2D(origin, v);
                    String label = MillVillageIndex.nameForApprox(player.world, v);
                    out.add(MsgWorkerGoList.Entry.village(v, label, dist));
                }

                // Homes (full objects give you name + pos)
                TravellerHomeManager mgr = TravellerHomeManager.get(player.world);
                List<TravellerHomeManager.HomeChest> homes = mgr.getHomesOfPlayerFull(player);

                for (TravellerHomeManager.HomeChest h : homes) {
                    BlockPos p = h.pos;
                    int dist = EntityTravellerWorker.dist2D(origin, p);

                    // Use whatever field you have as display name:
                    // If HomeChest has "name", prefer it; else fallback.
                    String label = (h.name != null && !h.name.isEmpty()) ? h.name : "Home";
                    out.add(MsgWorkerGoList.Entry.home(p, label, dist));
                }

                // Sort by distance (like recruit UI)
                out.sort(Comparator.comparingInt(a -> a.distance));

                TravellerFollowNet.CHANNEL.sendTo(new MsgWorkerGoList(w.getEntityId(), out), player);
            });

            return null;
        }
    }
}
