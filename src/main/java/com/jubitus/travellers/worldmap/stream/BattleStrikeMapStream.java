package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.worldmap.net.MsgMapBattleStrikes;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the strikes recorded in {@link MapStrikeLog} since the last send (a quarter of a second) to every map viewer,
 * under the same view and vision filters as {@link BattleMapStream}. It owns the log's drain.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class BattleStrikeMapStream {

    private static final int UPDATE_EVERY_TICKS = 5;
    private static final int MAX_AGE_TICKS = 127;
    private static final int MAX_STRIKES_PER_PACKET = 128;
    private static final int DEFAULT_RANGE_BLOCKS = 4096;

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter % UPDATE_EVERY_TICKS != 0) return;
        if (MapStrikeLog.size() == 0) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || !MapStrikeLog.isActive()) {
            MapStrikeLog.clear();
            return;
        }
        long t0 = MapStreamStats.begin();
        try {
            publish(server);
        } finally {
            MapStrikeLog.clear();
            MapStreamStats.end(MapStreamStats.Stream.STRIKES, t0);
        }
    }

    private static void publish(MinecraftServer server) {
        boolean visionGated = TravellersConfig.WORLDMAP.foreignUnitVisionEnabled;
        Map<UUID, List<PlayerUnitVisionSource.Pos>> ownUnits = visionGated
                ? PlayerUnitVisionSource.computeOwnUnitPositions(server) : java.util.Collections.emptyMap();
        double visionRadius = TravellersConfig.WORLDMAP.hiredWorkerVisionRadius;

        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUniqueID();
            if (!PopulationMapStream.isSubscribed(id) && !WorkerMapStream.isSubscribed(id)) continue;

            int[] view = WorkerMapStream.getView(id);
            int viewDim = (view != null) ? view[0] : player.dimension;
            int cx = (view != null) ? view[1] : (int) Math.floor(player.posX);
            int cz = (view != null) ? view[2] : (int) Math.floor(player.posZ);
            int range = (view != null) ? view[3] : DEFAULT_RANGE_BLOCKS;
            long rangeSq = (long) range * (long) range;
            List<PlayerUnitVisionSource.Pos> mine = visionGated ? ownUnits.get(id) : null;

            WorldServer ws = server.getWorld(viewDim);
            long now = ws.getTotalWorldTime();

            List<MsgMapBattleStrikes.Entry> out = new ArrayList<>();
            for (int i = 0; i < MapStrikeLog.size(); i++) {
                if (MapStrikeLog.dimAt(i) != viewDim) continue;
                int vx = MapStrikeLog.victimXAt(i);
                int vz = MapStrikeLog.victimZAt(i);
                if (!within(vx, vz, cx, cz, rangeSq)
                        && !within(MapStrikeLog.attackerXAt(i), MapStrikeLog.attackerZAt(i), cx, cz, rangeSq)) continue;
                if (visionGated && !PlayerUnitVisionSource.isVisible(mine, viewDim, vx, vz, visionRadius)) continue;

                MsgMapBattleStrikes.Entry en = new MsgMapBattleStrikes.Entry();
                en.kind = MapStrikeLog.kindAt(i);
                en.ageTicks = (byte) Math.max(0, Math.min(MAX_AGE_TICKS, now - MapStrikeLog.tickAt(i)));
                en.attacker = MapStrikeLog.attackerAt(i);
                en.attackerX = MapStrikeLog.attackerXAt(i);
                en.attackerZ = MapStrikeLog.attackerZAt(i);
                en.victimX = vx;
                en.victimZ = vz;
                out.add(en);
            }
            if (out.isEmpty()) continue;
            if (out.size() > MAX_STRIKES_PER_PACKET) {
                out.sort(Comparator.comparingLong(en -> distSq(en.victimX, en.victimZ, cx, cz)));
                out.subList(MAX_STRIKES_PER_PACKET, out.size()).clear();
            }
            MapStreamStats.send(MapStreamStats.Stream.STRIKES, player, new MsgMapBattleStrikes(viewDim, out));
        }
    }

    private static boolean within(int x, int z, int cx, int cz, long rangeSq) {
        return distSq(x, z, cx, cz) <= rangeSq;
    }

    private static long distSq(int x, int z, int cx, int cz) {
        long dx = (long) x - cx;
        long dz = (long) z - cz;
        return dx * dx + dz * dz;
    }
}
