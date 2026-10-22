package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.battle.BattleParticipant;
import com.jubitus.travellers.battle.BattleRecord;
import com.jubitus.travellers.battle.VirtualBattleManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;

/**
 * Feeds the map GUIs with battle markers: running battles and, for a minute, the result of ended ones. Same subscribe/tick/view-range
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

        long t0 = MapStreamStats.begin();
        try {
            publish(server);
        } finally {
            MapStreamStats.end(MapStreamStats.Stream.BATTLE, t0);
        }
    }

    private static void publish(MinecraftServer server) {
        List<Tally> all = collectAll(server);
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
            for (Tally t : all) {
                if (t.battle.dimension != viewDim) continue;
                long dx = (long) t.battle.anchor.getX() - cx;
                long dz = (long) t.battle.anchor.getZ() - cz;
                if (dx * dx + dz * dz > rangeSq) continue;
                // Vision rule: a battle is only visible if within range of at least one of the
                // player's own alive units (loaded/virtual). Master switch off = old unfiltered behavior.
                if (visionGated && !PlayerUnitVisionSource.isVisible(mine, t.battle.dimension,
                        t.battle.anchor.getX(), t.battle.anchor.getZ(), visionRadius)) continue;
                visible.add(t.entryFor(playerId));
            }

            MapStreamStats.send(MapStreamStats.Stream.BATTLE, player,
                    new MsgMapBattleUpdate(visible.isEmpty() ? Collections.emptyList() : visible));
        }
    }

    private static List<Tally> collectAll(MinecraftServer server) {
        List<Tally> out = new ArrayList<>();
        for (WorldServer ws : server.worlds) {
            long now = ws.getTotalWorldTime();
            VirtualBattleManager mgr = VirtualBattleManager.get(ws);
            // A battle is shown from its first blow: groups merely closing in, or passing by, show nothing.
            for (BattleRecord b : mgr.getBattlesView()) {
                if (b.firstBlowTick >= 0) out.add(new Tally(b, now));
            }
            for (BattleRecord b : mgr.getRecentlyEnded(now)) {
                if (b.firstBlowTick >= 0) out.add(new Tally(b, now));
            }
        }
        return out;
    }

    /** One battle counted once per publish; turned into a viewer-relative entry per subscriber. */
    private static final class Tally {
        final BattleRecord battle;
        final boolean running;
        final int seconds;
        /** Per faction: {standing, fallen}. */
        final Map<String, int[]> perFaction = new HashMap<>();

        Tally(BattleRecord b, long now) {
            battle = b;
            running = b.endTick < 0;
            seconds = (int) (Math.max(0, now - (running ? b.firstBlowTick : b.endTick)) / 20);
            for (BattleParticipant p : b.participants.values()) {
                perFaction.computeIfAbsent(p.groupKey, k -> new int[2])[p.alive ? 0 : 1]++;
            }
        }

        MsgMapBattleUpdate.Entry entryFor(UUID viewer) {
            String viewKey = BattleParticipant.OWNER_KEY_PREFIX + viewer;
            Map<String, Integer> camps = battle.camps(viewKey);
            MsgMapBattleUpdate.Entry e = new MsgMapBattleUpdate.Entry();
            e.dim = battle.dimension;
            e.pos = battle.anchor;
            e.campAssault = battle.campAnchorId != null;
            e.involved = perFaction.containsKey(viewKey);
            for (Map.Entry<String, int[]> f : perFaction.entrySet()) {
                MsgMapBattleUpdate.Side s = camps.get(f.getKey()) == 0 ? e.first : e.second;
                int[] c = f.getValue();
                s.standing += c[0];
                s.fallen += c[1];
                s.kinds |= kindOf(f.getKey(), viewKey);
                if (f.getKey().equals(viewKey)) {
                    s.yoursStanding = c[0];
                    s.yoursFallen = c[1];
                }
            }
            e.result = running ? MsgMapBattleUpdate.Result.RUNNING
                    : e.first.standing > 0 && e.second.standing == 0 ? MsgMapBattleUpdate.Result.FIRST_WON
                    : e.second.standing > 0 && e.first.standing == 0 ? MsgMapBattleUpdate.Result.SECOND_WON
                    : MsgMapBattleUpdate.Result.NO_WINNER;
            e.seconds = seconds;
            return e;
        }

        private static int kindOf(String faction, String viewKey) {
            if (BattleRecord.RAIDER.equals(faction)) return MsgMapBattleUpdate.KIND_RAIDERS;
            if (faction.equals(viewKey)) return MsgMapBattleUpdate.KIND_YOURS;
            if (faction.startsWith(BattleParticipant.OWNER_KEY_PREFIX)) return MsgMapBattleUpdate.KIND_HIRED;
            return MsgMapBattleUpdate.KIND_TRAVELLERS;
        }
    }
}
