package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.battle.BattleParticipant;
import com.jubitus.travellers.battle.BattleRecord;
import com.jubitus.travellers.battle.VirtualBattleManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;

/**
 * Feeds the population map GUI with live battle markers. Same subscribe/tick/view-range
 * pattern as {@link CampMapStream}; subscription piggybacks on the population map subscribe
 * message (a player watching the population map always sees battles too).
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class BattleMapStream {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    private static final int UPDATE_EVERY_TICKS = 20;
    private static int tickCounter = 0;

    private static final int DEFAULT_RANGE_BLOCKS = 4096;

    public static void subscribe(UUID playerId) {
        if (playerId != null) SUBSCRIBERS.add(playerId);
    }

    public static void unsubscribe(UUID playerId) {
        if (playerId != null) SUBSCRIBERS.remove(playerId);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        unsubscribe(e.player.getUniqueID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter % UPDATE_EVERY_TICKS != 0) return;
        if (SUBSCRIBERS.isEmpty()) return;
        if (!TravellersConfig.WORLDMAP.battleMarkersOnMap) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        List<MsgMapBattleUpdate.Entry> all = collectAll(server);
        boolean visionGated = TravellersConfig.WORLDMAP.foreignUnitVisionEnabled;
        Map<UUID, List<PlayerUnitVisionSource.Pos>> ownUnits = visionGated
                ? PlayerUnitVisionSource.computeOwnUnitPositions(server) : Collections.emptyMap();
        double visionRadius = TravellersConfig.WORLDMAP.hiredWorkerVisionRadius;

        for (UUID playerId : new ArrayList<>(SUBSCRIBERS)) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) continue;

            int[] view = WorkerMapStream.getView(playerId);
            int viewDim = (view != null) ? view[0] : player.dimension;
            int cx = (view != null) ? view[1] : (int) Math.floor(player.posX);
            int cz = (view != null) ? view[2] : (int) Math.floor(player.posZ);
            int range = (view != null) ? view[3] : DEFAULT_RANGE_BLOCKS;
            long rangeSq = (long) range * (long) range;

            List<PlayerUnitVisionSource.Pos> mine = visionGated ? ownUnits.get(playerId) : null;

            List<MsgMapBattleUpdate.Entry> visible = new ArrayList<>();
            for (MsgMapBattleUpdate.Entry en : all) {
                if (en.dim != viewDim) continue;
                long dx = (long) en.pos.getX() - cx;
                long dz = (long) en.pos.getZ() - cz;
                if (dx * dx + dz * dz > rangeSq) continue;
                // Vision rule: a battle is only visible if within range of at least one of the
                // player's own alive units (loaded/virtual). Master switch off = old unfiltered behavior.
                if (visionGated && !PlayerUnitVisionSource.isVisible(mine, en.dim,
                        en.pos.getX(), en.pos.getZ(), visionRadius)) continue;
                visible.add(en);
            }

            com.jubitus.travellers.net.TravellersNetwork.sendToPlayer(player,
                    new MsgMapBattleUpdate(visible.isEmpty() ? Collections.emptyList() : visible));
        }
    }

    private static List<MsgMapBattleUpdate.Entry> collectAll(MinecraftServer server) {
        List<MsgMapBattleUpdate.Entry> out = new ArrayList<>();
        for (WorldServer ws : server.worlds) {
            long now = ws.getTotalWorldTime();
            for (BattleRecord b : VirtualBattleManager.get(ws).getBattlesView()) {
                out.add(battleEntry(ws, b, now));
            }
        }
        return out;
    }

    private static MsgMapBattleUpdate.Entry battleEntry(WorldServer ws, BattleRecord b, long now) {
        MsgMapBattleUpdate.Entry e = new MsgMapBattleUpdate.Entry();
        e.dim = b.dimension;
        e.pos = b.center;
        e.aliveA = b.countAlive(0);
        e.aliveB = b.countAlive(1);
        e.campAssault = b.campAnchorId != null;

        long ageSec = Math.max(0, now - b.startTick) / 20;
        StringBuilder sb = new StringBuilder();
        sb.append("Battle (").append(ageSec / 60).append(':')
                .append(String.format("%02d", ageSec % 60)).append(')');
        if (e.campAssault) sb.append(" — camp assault");
        sb.append('\n').append(sideSummary(b, 0)).append(" vs ").append(sideSummary(b, 1));
        e.label = sb.toString();
        return e;
    }

    /** e.g. "12 Raiders" or "8 Travellers + 3 Hired". */
    private static String sideSummary(BattleRecord b, int side) {
        Map<String, Integer> counts = new HashMap<>();
        for (BattleParticipant p : b.participants.values()) {
            if (!p.alive || p.side != side) continue;
            String kind = "RAIDER".equals(p.groupKey) ? "Raiders"
                    : p.groupKey.startsWith("OWNER:") ? "Hired" : "Travellers";
            counts.merge(kind, 1, Integer::sum);
        }
        if (counts.isEmpty()) return "0";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> en : counts.entrySet()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(en.getValue()).append(' ').append(en.getKey());
        }
        return sb.toString();
    }
}
