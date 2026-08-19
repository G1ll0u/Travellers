package com.jubitus.travellers.server.map.stream;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feeds GuiPlayerWorldMap and GuiWorkerFormationHub with foreign (not-my-own) travellers, raiders
 * and workers, gated by the vision rule: a foreign unit is only sent to a player if it is within
 * TravellersConfig.WORLDMAP.hiredWorkerVisionRadius of at least one of that player's own vision
 * sources — their alive hired units (loaded or virtual), plus their own position when
 * TravellersConfig.WORLDMAP.playerIsVisionSource is on. Subscription piggybacks on
 * MsgSubscribeWorkerMap.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public class ForeignUnitMapStream {

    private static final Set<UUID> SUBSCRIBERS = ConcurrentHashMap.newKeySet();
    private static final int UPDATE_EVERY_TICKS = 20;
    private static final int MAX_ENTRIES_PER_PACKET = 512;
    // Spatial hash cell size, same convention as BattleEncounterScanner
    private static final int CELL_SIZE = 64;

    private static int tickCounter = 0;

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

    private static final class Candidate {
        final int dim;
        final double x, z;
        final BlockPos pos;
        final String name;
        final String entityClass;
        final boolean hired;
        final UUID ownerUUID;
        final int ownerColor;
        final boolean virtual;
        final float health;
        final float maxHealth;
        final boolean inBattle;

        Candidate(int dim, double x, double z, BlockPos pos, String name, String entityClass,
                  boolean hired, UUID ownerUUID, int ownerColor, boolean virtual,
                  float health, float maxHealth, boolean inBattle) {
            this.dim = dim;
            this.x = x;
            this.z = z;
            this.pos = pos;
            this.name = name;
            this.entityClass = entityClass;
            this.hired = hired;
            this.ownerUUID = ownerUUID;
            this.ownerColor = ownerColor;
            this.virtual = virtual;
            this.health = health;
            this.maxHealth = maxHealth;
            this.inBattle = inBattle;
        }

        static Candidate fromLoaded(EntityTraveller e, int dim) {
            String cls = (e instanceof EntityTravellerWorker) ? "WORKER"
                    : (e instanceof EntityTravellerRaider) ? "RAIDER" : "TRAVELLER";
            boolean hired = false;
            UUID owner = null;
            int ownerColor = 0;
            if (e instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                hired = w.isHiredFollower();
                owner = w.getFollowOwnerId();
                ownerColor = w.getOwnerMarkColor() & 0xFFFFFF;
            }
            return new Candidate(dim, e.posX, e.posZ, e.getPosition(), e.getName(), cls,
                    hired, owner, ownerColor, false, e.getHealth(), e.getMaxHealth(),
                    e.combat.isInCombatRaw());
        }

        static Candidate fromVirtual(VirtualTravellerRecord vr) {
            boolean hired = "WORKER".equals(vr.entityClass) && vr.isHiredFollower;
            return new Candidate(vr.dimension, vr.posX, vr.posZ,
                    new BlockPos(vr.posX, vr.posY, vr.posZ),
                    vr.name != null ? vr.name : "", vr.entityClass,
                    hired, hired ? vr.followOwnerUUID : null, vr.ownerColor, true,
                    vr.getHealthResolved(), vr.getCombatProfile().maxHealth, vr.battleId != null);
        }
    }

    private static long cellKey(int dim, double x, double z) {
        int cx = (int) Math.floor(x / CELL_SIZE);
        int cz = (int) Math.floor(z / CELL_SIZE);
        return ((long) dim << 48) | (((long) cx & 0xFFFFFF) << 24) | ((long) cz & 0xFFFFFF);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter % UPDATE_EVERY_TICKS != 0) return;
        if (SUBSCRIBERS.isEmpty()) return;
        if (!TravellersConfig.WORLDMAP.foreignUnitVisionEnabled) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // Build the full candidate pool + spatial hash once per tick
        List<Candidate> all = new ArrayList<>();
        for (WorldServer ws : server.worlds) {
            int dim = ws.provider.getDimension();
            for (EntityTraveller en : ws.getEntities(EntityTraveller.class, ent -> ent != null && ent.isEntityAlive())) {
                all.add(Candidate.fromLoaded(en, dim));
            }
        }
        WorldServer overworld = server.getWorld(0);
        if (overworld != null) {
            for (VirtualTravellerRecord vr : VirtualTravellerManager.get(overworld).getRecordsView()) {
                all.add(Candidate.fromVirtual(vr));
            }
        }

        Map<Long, List<Candidate>> cells = new HashMap<>();
        for (Candidate c : all) {
            cells.computeIfAbsent(cellKey(c.dim, c.x, c.z), k -> new ArrayList<>()).add(c);
        }

        Map<UUID, List<PlayerUnitVisionSource.Pos>> ownUnits = PlayerUnitVisionSource.computeOwnUnitPositions(server);
        double radius = TravellersConfig.WORLDMAP.hiredWorkerVisionRadius;
        boolean revealHealth = TravellersConfig.WORLDMAP.revealForeignEntityHealth;

        for (UUID playerId : new ArrayList<>(SUBSCRIBERS)) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(playerId);
            if (player == null) continue;

            List<PlayerUnitVisionSource.Pos> mine = ownUnits.get(playerId);
            if (mine == null || mine.isEmpty()) {
                sendForeignUnits(player, Collections.emptyList());
                continue;
            }

            Map<Candidate, Boolean> visibleSet = new IdentityHashMap<>();
            for (PlayerUnitVisionSource.Pos p : mine) {
                for (Candidate c : nearbyCandidates(cells, p.dim, p.x, p.z, radius)) {
                    if (c.ownerUUID != null && c.ownerUUID.equals(playerId)) continue; // already shown as "mine"
                    visibleSet.put(c, Boolean.TRUE);
                }
            }

            if (visibleSet.isEmpty()) {
                sendForeignUnits(player, Collections.emptyList());
                continue;
            }

            List<Candidate> visible = new ArrayList<>(visibleSet.keySet());
            if (visible.size() > MAX_ENTRIES_PER_PACKET) {
                visible.subList(MAX_ENTRIES_PER_PACKET, visible.size()).clear();
            }

            List<MsgMapForeignUnitsUpdate.Entry> out = new ArrayList<>(visible.size());
            for (Candidate c : visible) {
                MsgMapForeignUnitsUpdate.Entry en = new MsgMapForeignUnitsUpdate.Entry();
                en.dim = c.dim;
                en.pos = c.pos;
                en.name = c.name;
                en.entityClass = c.entityClass;
                en.hired = c.hired;
                en.ownerColor = c.ownerColor;
                en.virtual = c.virtual;
                en.health = revealHealth ? c.health : -1f;
                en.maxHealth = revealHealth ? c.maxHealth : -1f;
                en.inBattle = c.inBattle;
                out.add(en);
            }
            sendForeignUnits(player, out);
        }
    }

    private static void sendForeignUnits(EntityPlayerMP player, List<MsgMapForeignUnitsUpdate.Entry> out) {
        com.jubitus.travellers.server.net.TravellerFollowNet.sendToPlayer(player, new MsgMapForeignUnitsUpdate(out));
    }

    private static List<Candidate> nearbyCandidates(Map<Long, List<Candidate>> cells, int dim,
                                                      double x, double z, double radius) {
        List<Candidate> out = new ArrayList<>();
        int minCx = (int) Math.floor((x - radius) / CELL_SIZE);
        int maxCx = (int) Math.floor((x + radius) / CELL_SIZE);
        int minCz = (int) Math.floor((z - radius) / CELL_SIZE);
        int maxCz = (int) Math.floor((z + radius) / CELL_SIZE);
        double r2 = radius * radius;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                long key = ((long) dim << 48) | (((long) cx & 0xFFFFFF) << 24) | ((long) cz & 0xFFFFFF);
                List<Candidate> cell = cells.get(key);
                if (cell == null) continue;
                for (Candidate c : cell) {
                    double dx = c.x - x;
                    double dz = c.z - z;
                    if (dx * dx + dz * dz <= r2) out.add(c);
                }
            }
        }
        return out;
    }
}
