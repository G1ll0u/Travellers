package com.jubitus.travellers.server.net.message.villages;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.mill.MillSettlements;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

//

public class MsgWorkerRequestVillages implements IMessage {

    private int workerId;

    public MsgWorkerRequestVillages() {
    }

    public MsgWorkerRequestVillages(int workerId) {
        this.workerId = workerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.workerId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
    }

    // ---------- SERVER HANDLER ----------
    public static class Handler implements IMessageHandler<MsgWorkerRequestVillages, IMessage> {

        @Override
        public IMessage onMessage(MsgWorkerRequestVillages msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private void handle(MsgWorkerRequestVillages msg, EntityPlayerMP player) {
            WorldServer ws = player.getServerWorld();
            Entity e = ws.getEntityByID(msg.workerId);
            if (!(e instanceof EntityTravellerWorker)) {
                return;
            }

            EntityTravellerWorker worker = (EntityTravellerWorker) e;

            // Optional safety: only if player is close enough to the worker
            if (worker.getDistanceSq(player) > 64.0D) {
                return;
            }

            BlockPos workerPos = worker.getPosition();
            int maxRadius = TravellersConfig.WORKER.workerVillageDetectRadius;
            if (maxRadius <= 0) {
                maxRadius = 2000;
            }

            List<BlockPos> centers = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<Integer> distances = new ArrayList<>();

            for (MillSettlements.Settlement vi : MillSettlements.villages(ws)) {
                BlockPos center = vi.center;

                double dx = center.getX() + 0.5D - workerPos.getX();
                double dz = center.getZ() + 0.5D - workerPos.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

                if (dist > maxRadius) {
                    continue;
                }

                String name = vi.displayName();

                String display = String.format("%s (%dm)", name, dist);

                centers.add(center);
                labels.add(display);
                distances.add(dist);
            }

            // Sort by distance client-side? Could also sort here; for now, let's sort here so GUI doesn't care
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < centers.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt(i -> distances.get(i)));

            List<BlockPos> sortedCenters = new ArrayList<>(centers.size());
            List<String> sortedLabels = new ArrayList<>(centers.size());
            List<Integer> sortedDistances = new ArrayList<>(centers.size());
            for (int i : order) {
                sortedCenters.add(centers.get(i));
                sortedLabels.add(labels.get(i));
                sortedDistances.add(distances.get(i));
            }

            MsgWorkerVillageList reply = new MsgWorkerVillageList(
                    worker.getEntityId(),
                    sortedCenters,
                    sortedLabels,
                    sortedDistances
            );

            TravellerFollowNet.sendToPlayer(player, reply);
        }
    }

    // client-side handler is a no-op (we never receive this on client)
    public static class NoopHandler implements IMessageHandler<MsgWorkerRequestVillages, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerRequestVillages msg, MessageContext ctx) {
            return null;
        }
    }
}

