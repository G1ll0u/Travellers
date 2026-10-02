package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * One global horse-economy cap per player: total tamed/bred horses across every Stable they own
 * must stay under their total hired-worker count. Only gates deliberate breeding (see
 * {@link #canBreed}); opportunistic taming of a wandered-in wild horse is never blocked by it, and
 * it is never enforced retroactively (a later drop in worker count never evicts existing horses).
 */
public final class StableCapacity {

    private StableCapacity() {
    }

    public static int currentHorseCount(WorldServer overworld, UUID ownerUUID) {
        int count = 0;
        MinecraftServer srv = overworld.getMinecraftServer();
        for (WorldServer ws : srv.worlds) {
            for (PlayerZoneRecord zone : PlayerZoneManager.get(ws).getByOwnerAndType(ownerUUID, ZoneType.STABLE)) {
                if (!(zone.data instanceof StableZoneData)) continue;
                for (StableHorseEntry e : ((StableZoneData) zone.data).pool) {
                    if (e.tamed) count++;
                }
            }
        }
        return count;
    }

    /** Live + virtual, deduplicated - same shape as {@code MsgWorkerBarracksData.buildFor}'s own count. */
    public static int hiredWorkerCount(WorldServer overworld, UUID ownerUUID) {
        int count = 0;
        Set<UUID> seen = new HashSet<>();
        MinecraftServer srv = overworld.getMinecraftServer();
        for (WorldServer ws : srv.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class, e -> e != null && e.isEntityAlive())) {
                UUID owner = w.getFollowOwnerId();
                if (owner == null) owner = w.followOwnerId;
                boolean hired;
                try { hired = w.isHiredFollower(); } catch (Throwable t) { hired = false; }
                if (!hired) hired = w.hiredFollower;
                if (hired && ownerUUID.equals(owner) && seen.add(w.getUniqueID())) count++;
            }
        }
        VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
        for (VirtualTravellerRecord r : vwm.getRecordsView()) {
            if (r.isHiredFollower && ownerUUID.equals(r.followOwnerUUID) && seen.add(r.uuid)) count++;
        }
        return count;
    }

    public static boolean canBreed(WorldServer overworld, UUID ownerUUID) {
        return currentHorseCount(overworld, ownerUUID)
                < hiredWorkerCount(overworld, ownerUUID) * TravellersConfig.STABLES.horseCapPerWorker;
    }

    /**
     * Guard-rail cap for one Stable's own pool, independent of the player-wide worker cap - a small
     * pen must never hold dozens of horses just because the player has enough hired workers to
     * nominally afford it. Footprint area (the pen's own declared box, not the containment scan
     * margin) divided by {@code horseCapAreaPerHorse}, floored at {@code minPoolReserve} so a tiny
     * pen can still hold at least a breeding pair.
     */
    public static int zoneAreaCap(PlayerZoneRecord zone) {
        long area = (2L * zone.halfWidth + 1) * (2L * zone.halfDepth + 1);
        int cap = (int) (area / TravellersConfig.STABLES.horseCapAreaPerHorse);
        // Floor at reserve + 1, not reserve: a pen capped at exactly the reserve could breed up to
        // the cap and then never hand out a horse (Get Equipment refuses to drop the pool to/below
        // the reserve), making it a decorative Stable by accident.
        return Math.max(TravellersConfig.STABLES.minPoolReserve + 1, cap);
    }

    /** Whether {@code zone}'s pool has room for one more entry under its own area guard-rail -
     * checked independently of (in addition to) the player-wide {@link #canBreed} cap. Gates both
     * deliberate breeding and passively claiming a wild/reclaimed/bred horse. */
    public static boolean hasRoomFor(PlayerZoneRecord zone) {
        if (!(zone.data instanceof StableZoneData)) return true;
        return ((StableZoneData) zone.data).pool.size() < zoneAreaCap(zone);
    }
}
