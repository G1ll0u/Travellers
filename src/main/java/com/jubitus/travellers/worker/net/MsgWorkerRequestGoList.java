package com.jubitus.travellers.worker.net;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.village.MillSettlements;
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
                BlockPos origin = player.getPosition();

                java.util.Set<BlockPos> controlledVillages = TravellersConfig.CONTROLLED_VILLAGE.controlledVillageFeatureEnabled
                        ? MillSettlements.controlledBy(player.world, player)
                        : java.util.Collections.emptySet();

                for (MillSettlements.Settlement s : MillSettlements.villages(player.world)) {
                    BlockPos v = s.center;
                    int dist = EntityTravellerWorker.dist2D(origin, v);
                    String label = s.displayName();
                    out.add(controlledVillages.contains(v)
                            ? MsgWorkerGoList.Entry.controlledVillage(v, label, dist)
                            : MsgWorkerGoList.Entry.village(v, label, dist));
                }

                PlayerZoneManager lbMgr = PlayerZoneManager.get(player.world);
                for (PlayerZoneRecord r : lbMgr.getByOwnerAndType(player.getUniqueID(),
                        com.jubitus.travellers.zone.ZoneType.BARRACK)) {
                    int dist = EntityTravellerWorker.dist2D(origin, r.center);
                    String label =(r.name != null && !r.name.isEmpty() ? r.name : "Barrack");
                    out.add(MsgWorkerGoList.Entry.home(r.center, label, dist));
                }
                for (PlayerZoneRecord r : lbMgr.getByOwnerAndType(player.getUniqueID(),
                        com.jubitus.travellers.zone.ZoneType.STABLE)) {
                    int dist = EntityTravellerWorker.dist2D(origin, r.center);
                    String label = (r.name != null && !r.name.isEmpty() ? r.name : "Stable");
                    out.add(MsgWorkerGoList.Entry.stable(r.center, label, dist));
                }

                out.sort(Comparator.comparingInt(a -> a.distance));
                TravellersNetwork.CHANNEL.sendTo(new MsgWorkerGoList(responseId, out), player);
            });

            return null;
        }
    }
}
