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
                if (t.dim != viewDim) continue;
                long dx = (long) t.pos.getX() - cx;
                long dz = (long) t.pos.getZ() - cz;
                if (dx * dx + dz * dz > rangeSq) continue;
                // Vision rule: a battle is only visible if within range of at least one of the
                // player's own alive units (loaded/virtual). Master switch off = old unfiltered behavior.
                if (visionGated && !PlayerUnitVisionSource.isVisible(mine, t.dim,
                        t.pos.getX(), t.pos.getZ(), visionRadius)) continue;
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
            for (BattleRecord b : mgr.getBattlesView()) out.add(new Tally(b, now));
            for (BattleRecord b : mgr.getRecentlyEnded(now)) out.add(new Tally(b, now));
        }
        return out;
    }

    /** One battle counted once per publish; turned into a viewer-relative entry per subscriber. */
    private static final class Tally {
        final int dim;
        final BlockPos pos;
        final boolean campAssault;
        final boolean running;
        final int winnerSide;
        final int seconds;
        final int[] standing = new int[2];
        final int[] fallen = new int[2];
        /** KIND_TRAVELLERS / KIND_RAIDERS bits per side (hired workers are told apart per viewer). */
        final int[] kinds = new int[2];
        final List<Set<UUID>> owners = Arrays.asList(new HashSet<>(), new HashSet<>());

        Tally(BattleRecord b, long now) {
            dim = b.dimension;
            pos = b.center;
            campAssault = b.campAnchorId != null;
            running = b.endTick < 0;
            winnerSide = b.winnerSide;
            seconds = (int) (Math.max(0, now - (running ? b.startTick : b.endTick)) / 20);
            for (BattleParticipant p : b.participants.values()) {
                int side = p.side == 1 ? 1 : 0;
                if (p.alive) standing[side]++;
                else fallen[side]++;
                if ("RAIDER".equals(p.groupKey)) {
                    kinds[side] |= MsgMapBattleUpdate.KIND_RAIDERS;
                } else if (p.groupKey.startsWith(BattleParticipant.OWNER_KEY_PREFIX)) {
                    try {
                        owners.get(side).add(UUID.fromString(p.groupKey.substring(BattleParticipant.OWNER_KEY_PREFIX.length())));
                    } catch (IllegalArgumentException ignored) {
                    }
                } else {
                    kinds[side] |= MsgMapBattleUpdate.KIND_TRAVELLERS;
                }
            }
        }

        MsgMapBattleUpdate.Entry entryFor(UUID viewer) {
            int mySide = owners.get(0).contains(viewer) ? 0 : owners.get(1).contains(viewer) ? 1 : -1;
            int first = mySide >= 0 ? mySide : 0;
            MsgMapBattleUpdate.Entry e = new MsgMapBattleUpdate.Entry();
            e.dim = dim;
            e.pos = pos;
            e.campAssault = campAssault;
            e.involved = mySide >= 0;
            e.first = side(first, viewer);
            e.second = side(1 - first, viewer);
            e.result = running ? MsgMapBattleUpdate.Result.RUNNING
                    : winnerSide < 0 ? MsgMapBattleUpdate.Result.NO_WINNER
                    : winnerSide == first ? MsgMapBattleUpdate.Result.FIRST_WON : MsgMapBattleUpdate.Result.SECOND_WON;
            e.seconds = seconds;
            return e;
        }

        private MsgMapBattleUpdate.Side side(int side, UUID viewer) {
            MsgMapBattleUpdate.Side s = new MsgMapBattleUpdate.Side();
            s.standing = standing[side];
            s.fallen = fallen[side];
            s.kinds = kinds[side];
            for (UUID owner : owners.get(side)) {
                s.kinds |= owner.equals(viewer) ? MsgMapBattleUpdate.KIND_YOURS : MsgMapBattleUpdate.KIND_HIRED;
            }
            return s;
        }
    }
}
