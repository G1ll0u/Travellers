package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate;

/**
 * Feeds GuiTravellerPopulationMap with the position of every traveller, raider and worker in the
 * world (loaded and virtual alike), so the map gives a full population overview regardless of
 * which chunks happen to be loaded around a player.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class PopulationMapStream {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();

    private static final int UPDATE_EVERY_TICKS = 20;
    private static int tickCounter = 0;

    // Safety ceiling only - real limiting is the view range filter below. Raised well past what a
    // single view range would realistically contain even with thousands of travellers world-wide;
    // if it's ever exceeded, the nearest entries are kept (see MapStreamUtil), never an arbitrary cut.
    private static final int MAX_ENTRIES_PER_PACKET = 20000;
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

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        List<MsgMapPopulationUpdate.Entry> all = collectAll(server);

        for (UUID playerId : new ArrayList<>(SUBSCRIBERS)) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) continue;

            int[] view = WorkerMapStream.getView(playerId);
            int viewDim = (view != null) ? view[0] : player.dimension;
            int cx = (view != null) ? view[1] : (int) Math.floor(player.posX);
            int cz = (view != null) ? view[2] : (int) Math.floor(player.posZ);
            int range = (view != null) ? view[3] : DEFAULT_RANGE_BLOCKS;
            long rangeSq = (long) range * (long) range;

            List<MsgMapPopulationUpdate.Entry> visible = MapStreamUtil.nearestWithinRange(
                    all, viewDim, en -> en.dim, en -> en.pos, cx, cz, rangeSq, MAX_ENTRIES_PER_PACKET);

            if (visible.isEmpty()) {
                com.jubitus.travellers.net.TravellersNetwork.sendToPlayer(player,
                        new MsgMapPopulationUpdate(Collections.emptyList()));
            } else {
                com.jubitus.travellers.net.TravellersNetwork.sendToPlayer(player,
                        new MsgMapPopulationUpdate(visible));
            }
        }
    }

    private static List<MsgMapPopulationUpdate.Entry> collectAll(MinecraftServer server) {
        List<MsgMapPopulationUpdate.Entry> out = new ArrayList<>();

        for (WorldServer ws : server.worlds) {
            int dim = ws.provider.getDimension();
            List<EntityTraveller> entities = ws.getEntities(EntityTraveller.class,
                    t -> t != null && t.isEntityAlive());

            for (EntityTraveller t : entities) {
                MsgMapPopulationUpdate.Kind kind;
                if (t instanceof EntityTravellerWorker) {
                    kind = MsgMapPopulationUpdate.Kind.WORKER;
                } else if (t instanceof EntityTravellerRaider) {
                    kind = MsgMapPopulationUpdate.Kind.RAIDER;
                } else {
                    kind = MsgMapPopulationUpdate.Kind.TRAVELLER;
                }
                MsgMapPopulationUpdate.Entry en = entry(dim, t.getPosition(), kind, false, t.getName());
                en.uuid = t.getUniqueID();
                out.add(en);
            }
        }

        for (WorldServer ws : server.worlds) {
            int dim = ws.provider.getDimension();
            com.jubitus.travellers.zone.stable.StableCoordinator coord =
                    com.jubitus.travellers.zone.stable.StableCoordinator.get(ws);
            for (com.jubitus.travellers.zone.PlayerZoneRecord zone :
                    com.jubitus.travellers.zone.PlayerZoneManager.get(ws).getAll()) {
                if (zone.type != com.jubitus.travellers.zone.ZoneType.STABLE
                        || !(zone.data instanceof com.jubitus.travellers.zone.stable.StableZoneData)) {
                    continue;
                }
                boolean loaded = coord.isZoneLive(zone);
                String zn = (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;
                MsgMapPopulationUpdate.Entry se = new MsgMapPopulationUpdate.Entry();
                se.dim = dim;
                se.pos = zone.center;
                se.kind = MsgMapPopulationUpdate.Kind.STABLE;
                se.virtual = !loaded;
                se.label = zn + "\n" + coord.debugSummary(ws, zone);
                out.add(se);
            }
        }

        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            VirtualTravellerManager mgr = VirtualTravellerManager.get(overworld);
            for (VirtualTravellerRecord r : mgr.getAllRecords()) {
                MsgMapPopulationUpdate.Kind kind;
                switch (r.entityClass) {
                    case "WORKER": kind = MsgMapPopulationUpdate.Kind.WORKER; break;
                    case "RAIDER": kind = MsgMapPopulationUpdate.Kind.RAIDER; break;
                    default: kind = MsgMapPopulationUpdate.Kind.TRAVELLER; break;
                }
                BlockPos pos = new BlockPos((int) r.posX, (int) r.posY, (int) r.posZ);
                MsgMapPopulationUpdate.Entry en = entry(r.dimension, pos, kind, true, r.name);
                en.uuid = r.uuid;
                out.add(en);
            }
        }

        return out;
    }

    private static MsgMapPopulationUpdate.Entry entry(int dim, BlockPos pos, MsgMapPopulationUpdate.Kind kind,
                                                        boolean virtual, String name) {
        MsgMapPopulationUpdate.Entry e = new MsgMapPopulationUpdate.Entry();
        e.dim = dim;
        e.pos = pos;
        e.kind = kind;
        e.virtual = virtual;
        e.label = (name != null && !name.isEmpty()) ? name : (kind.name() + (virtual ? " (virtual)" : " (loaded)"));
        return e;
    }
}
