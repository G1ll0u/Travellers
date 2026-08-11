package com.jubitus.travellers.server.net.message.relations;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.utils.world.data.TravellerVillageRelations;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.*;

import java.util.*;

/** Client -> server: "send me my account-wide village-enemy relations" (GuiWorkerVillageRelations on open). */
public class MsgWorkerRequestVillageRelations implements IMessage {

    public MsgWorkerRequestVillageRelations() {}

    @Override public void fromBytes(ByteBuf buf) { }
    @Override public void toBytes(ByteBuf buf) { }

    public static class Handler implements IMessageHandler<MsgWorkerRequestVillageRelations, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerRequestVillageRelations msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(player));
            return null;
        }

        private void handle(EntityPlayerMP player) {
            WorldServer ws = player.getServerWorld();

            MsgWorkerVillageRelationsList reply = buildList(ws, player.getPosition(), player.getUniqueID());
            TravellerFollowNet.sendToPlayer(player, reply);
        }

        public static MsgWorkerVillageRelationsList buildList(WorldServer ws, BlockPos fromPos, UUID playerId) {
            int maxRadius = TravellersConfig.WORKER.workerVillageDetectRadius;
            if (maxRadius <= 0) maxRadius = 2000;

            TravellerVillageRelations rel = TravellerVillageRelations.get(ws);
            int dim = ws.provider.getDimension();

            Map<MillVillageIndex.VillageInfo, Integer> pops = MillVillageIndex.countVillagersPerVillage(ws);

            List<BlockPos> centers = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<Integer> distances = new ArrayList<>();
            List<Boolean> enemies = new ArrayList<>();

            for (Map.Entry<MillVillageIndex.VillageInfo, Integer> entry : pops.entrySet()) {
                MillVillageIndex.VillageInfo vi = entry.getKey();
                BlockPos center = vi.center;

                double dx = center.getX() + 0.5D - fromPos.getX();
                double dz = center.getZ() + 0.5D - fromPos.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
                if (dist > maxRadius) continue;

                String name = vi.name;
                if (name == null || name.isEmpty()) {
                    name = MillVillageIndex.nameForApprox(ws, center);
                }

                boolean enemy = rel.isEnemy(playerId, dim, center);

                String display = String.format("%s (%dm)%s", name, dist, enemy ? "  [ENEMY]" : "");

                centers.add(center);
                labels.add(display);
                distances.add(dist);
                enemies.add(enemy);
            }

            // sort by distance
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < centers.size(); i++) order.add(i);
            order.sort(Comparator.comparingInt(i -> distances.get(i)));

            List<BlockPos> sc = new ArrayList<>(centers.size());
            List<String> sl = new ArrayList<>(centers.size());
            List<Integer> sd = new ArrayList<>(centers.size());
            List<Boolean> se = new ArrayList<>(centers.size());

            for (int i : order) {
                sc.add(centers.get(i));
                sl.add(labels.get(i));
                sd.add(distances.get(i));
                se.add(enemies.get(i));
            }

            return new MsgWorkerVillageRelationsList(sc, sl, sd, se);
        }
    }
}