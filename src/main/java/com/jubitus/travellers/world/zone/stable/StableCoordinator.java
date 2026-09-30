package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.camp.CampsiteManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick driver for every Stable zone. Owns only the pieces that must be shared across passes -
 * the transient "which live horses belong to a stable" set (used by {@code MountTheftGuard}), the
 * per-entry stale-link grace timers, and the whole-zone settle gate - and delegates the actual work:
 * <ul>
 *   <li>{@link StableAging} - dormant-entry growth, load-agnostic.</li>
 *   <li>{@link StableWork} - taming/saddling/breeding rules; its {@code timerPass} runs here only
 *       while no loaded palfrenier is working the zone.</li>
 *   <li>{@link StableContainmentSweep} - per-tick containment for known pool horses, ungated.</li>
 *   <li>{@link StablePhysicalReconciler} - the heavy scan/claim/materialize pass, settle-gated.</li>
 *   <li>{@link StableBedService} - palfrenier presence count + bed break/recovery.</li>
 * </ul>
 * Ticked unconditionally from {@code TravellerChunkManager}; all real work is internally throttled
 * off {@code TravellersConfig.STABLES}. Holds no persisted state - the pool lives in
 * {@code PlayerZoneManager}'s records.
 */
public final class StableCoordinator {

    /** Custom entity-data key (persisted via {@code Entity}'s "ForgeData" tag) marking a live horse
     * as belonging to a {@link StableHorseEntry#poolId}. Identity that survives any race a raw
     * Minecraft-UUID match could not - see {@link StableHorseLoadHandler} and
     * {@link StablePhysicalReconciler}. */
    private static final String POOL_ID_TAG = "TravellersStablePoolId";

    private static final Map<Integer, StableCoordinator> INSTANCES = new ConcurrentHashMap<>();

    private final Set<UUID> trackedLiveHorses = ConcurrentHashMap.newKeySet();
    /** How long each pool entry's live horse has been missing from a reconcile scan, keyed by
     * {@code poolId} - the stale-link grace period. */
    private final Map<UUID, Long> missingSince = new ConcurrentHashMap<>();
    /** World tick a zone's chunks were first observed fully loaded, keyed by {@code PlayerZoneRecord.id}
     * - the reconcile settle gate. Cleared the instant any chunk reads unloaded again. */
    private final Map<UUID, Long> zoneLoadedSince = new ConcurrentHashMap<>();
    /** Last world tick each Stable's beds were rescanned, keyed by {@code PlayerZoneRecord.id}. */
    private final Map<UUID, Long> lastBedScan = new ConcurrentHashMap<>();
    /** Last logged load/scheduler state per zone, so {@link StableWatchLog} only records transitions. */
    private final Map<UUID, String> lastZoneState = new ConcurrentHashMap<>();

    private StableCoordinator() {
    }

    public static StableCoordinator get(WorldServer world) {
        return INSTANCES.computeIfAbsent(world.provider.getDimension(), d -> new StableCoordinator());
    }

    /** Called on server shutdown - {@link #INSTANCES} is keyed by dimension id, which two different
     * save files loaded in one client session can both reuse (dimension 0), so leaving it populated
     * would leak one world's tick-clock-relative timers into the next. */
    public static void resetAll() {
        INSTANCES.clear();
    }

    // -------------------------------------------------------------------------
    // Tracked-set + tag helpers (shared with the split helpers and MountTheftGuard)
    // -------------------------------------------------------------------------

    public boolean isStableTracked(UUID liveEntityUUID) {
        return trackedLiveHorses.contains(liveEntityUUID);
    }

    public void trackLiveHorse(UUID liveEntityUUID) {
        trackedLiveHorses.add(liveEntityUUID);
    }

    public void forgetLiveHorse(UUID liveEntityUUID) {
        trackedLiveHorses.remove(liveEntityUUID);
    }

    Map<UUID, Long> missingSince() {
        return missingSince;
    }

    /** Strips the pool marker from a horse NBT snapshot about to be respawned as something else
     * (a worker's bonded mount via Get Equipment, or a released pen horse). */
    public static void stripPoolTag(NBTTagCompound horseNbt) {
        if (horseNbt == null || !horseNbt.hasKey("ForgeData")) return;
        NBTTagCompound fd = horseNbt.getCompoundTag("ForgeData");
        fd.removeTag(POOL_ID_TAG + "Most");
        fd.removeTag(POOL_ID_TAG + "Least");
    }

    public static UUID getPoolTag(EntityHorse horse) {
        return horse.getEntityData().hasUniqueId(POOL_ID_TAG) ? horse.getEntityData().getUniqueId(POOL_ID_TAG) : null;
    }

    public static void setPoolTag(EntityHorse horse, UUID poolId) {
        horse.getEntityData().setUniqueId(POOL_ID_TAG, poolId);
    }

    public static void stripPoolTagFromEntity(EntityHorse horse) {
        horse.getEntityData().removeTag(POOL_ID_TAG + "Most");
        horse.getEntityData().removeTag(POOL_ID_TAG + "Least");
    }

    /** The Stable a live horse belongs to, found by its persisted {@link #POOL_ID_TAG} - used by
     * {@link StableHorseLoadHandler} to relink a pool horse the instant its chunk loads. */
    public PlayerZoneRecord findZoneForPoolHorse(WorldServer world, UUID poolId) {
        for (PlayerZoneRecord zone : PlayerZoneManager.get(world).getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            if (((StableZoneData) zone.data).findByPoolId(poolId) != null) return zone;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Per-tick driver
    // -------------------------------------------------------------------------

    public void tick(WorldServer world, long now) {
        if (!TravellersConfig.STABLES.enabled) return;
        if (now % TravellersConfig.STABLES.containmentCheckIntervalTicks != 0) return;

        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        for (PlayerZoneRecord zone : mgr.getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            StableZoneData data = (StableZoneData) zone.data;

            // Dormant-entry growth is load-agnostic - always runs.
            boolean dirty = StableAging.advance(world, zone, data, now);
            boolean zoneLoaded = isZoneFullyLoaded(world, zone);
            boolean night = CampsiteManager.isNight(world);

            // Exactly one scheduler advances the pool. If a loaded palfrenier is on station, the
            // palfrenier AI (StableHandTask) does the work itself with real effects, and the
            // offscreen timer pass stays off so the two can never advance the same pool at once.
            // Otherwise, only when the zone is fully unloaded, a virtual palfrenier on station
            // drives the elapsed-time timer pass against the chest mirror.
            int loadedHands = night ? 0
                    : StableBedService.countLoadedPalfreniersOnStation(world, zone, data);
            int virtualHands = 0;
            if (loadedHands == 0 && !night && !zoneLoaded) {
                virtualHands = StableBedService.countVirtualPalfreniersOnStation(world, zone, data);
                if (virtualHands > 0) dirty |= StableWork.timerPass(world, zone, data, now, virtualHands);
            }
            StableWatchLog.maybeSnapshot(world, zone, data, zoneLoaded, loadedHands, virtualHands);
            logZoneStateChange(world, zone, zoneLoaded, night, loadedHands, virtualHands);

            // Ungated containment for horses we already track - never waits for the settle gate.
            if (world.isBlockLoaded(zone.center)) {
                StableContainmentSweep.run(world, zone, data, this);
            }

            // Settle gate: the heavy reconcile pass (claim/relink/stale-link/materialize) must not
            // run until every chunk touching the zone box has been continuously loaded for the grace
            // window, or a lagging entity repopulation makes the scan miss real horses and the pool
            // doubles. See StablePhysicalReconciler.
            if (zoneLoaded) {
                Long since = zoneLoadedSince.get(zone.id);
                if (since == null) {
                    zoneLoadedSince.put(zone.id, now);
                } else if (now - since >= TravellersConfig.STABLES.missingHorseGraceTicks) {
                    Long lastBeds = lastBedScan.get(zone.id);
                    boolean bedsDue = lastBeds == null
                            || now - lastBeds >= StableBedService.BED_RESCAN_INTERVAL;
                    if (bedsDue) lastBedScan.put(zone.id, now);
                    // reconcile marks dirty for its own changes; `dirty` still carries any
                    // aging/sim change it didn't make, so leave the final markDirty below to run.
                    StablePhysicalReconciler.reconcile(world, mgr, zone, this, bedsDue);
                }
            } else {
                zoneLoadedSince.remove(zone.id);
            }

            if (dirty) mgr.markDirty();
        }
    }

    /** Whether every chunk touching this zone's scan box is currently loaded - not just the centre
     * chunk, which a zone wider than one chunk can pass while a far corner is still unloaded.
     * {@code chunkExists} never force-loads. */
    public boolean isZoneFullyLoaded(WorldServer world, PlayerZoneRecord zone) {
        int margin = TravellersConfig.STABLES.containmentMarginBlocks;
        int minCx = (zone.center.getX() - zone.halfWidth - margin) >> 4;
        int maxCx = (zone.center.getX() + zone.halfWidth + margin) >> 4;
        int minCz = (zone.center.getZ() - zone.halfDepth - margin) >> 4;
        int maxCz = (zone.center.getZ() + zone.halfDepth + margin) >> 4;
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!world.getChunkProvider().chunkExists(cx, cz)) return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Zone deletion
    // -------------------------------------------------------------------------

    /** Frees the horse pool of a Stable being deleted: every live pool horse becomes an ordinary owned
     * tame horse again. Workers are released beforehand by {@code ZoneAssignments.onZoneDeleted}. */
    public void releaseZone(WorldServer world, PlayerZoneRecord zone) {
        StableZoneData data = (zone.data instanceof StableZoneData) ? (StableZoneData) zone.data : null;
        if (data == null) return;

        for (StableHorseEntry entry : new ArrayList<>(data.pool)) {
            if (entry.liveEntityUUID == null) continue;
            Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
            trackedLiveHorses.remove(entry.liveEntityUUID);
            if (!(ent instanceof EntityHorse) || ent.isDead) continue;
            NBTTagCompound nbt = new NBTTagCompound();
            ent.writeToNBT(nbt);
            nbt.removeTag("UUIDMost");
            nbt.removeTag("UUIDLeast");
            stripPoolTag(nbt);
            ent.setDead();
            EntityHorse fresh = new EntityHorse(world);
            fresh.readFromNBT(nbt);
            world.spawnEntity(fresh);
        }
        data.pool.clear();

        zoneLoadedSince.remove(zone.id);
        lastBedScan.remove(zone.id);
        lastZoneState.remove(zone.id);
        StableWatchLog.forget(zone.id);
    }

    /** Records a {@link StableWatchLog} ZONE line only when a Stable's load state or which scheduler
     * is driving its pool actually changes - the observability half of "exactly one scheduler runs". */
    private void logZoneStateChange(WorldServer world, PlayerZoneRecord zone, boolean zoneLoaded,
                                    boolean night, int loadedHands, int virtualHands) {
        String scheduler = night ? "night (idle)"
                : loadedHands > 0 ? "loaded palfrenier x" + loadedHands
                : virtualHands > 0 ? "offscreen timer x" + virtualHands
                : "idle";
        String state = (zoneLoaded ? "loaded" : "unloaded") + " | " + scheduler;
        String prev = lastZoneState.put(zone.id, state);
        if (prev != null && !prev.equals(state)) {
            StableWatchLog.zone(world, zone, state + "  (was: " + prev + ")");
        }
    }

    // -------------------------------------------------------------------------
    // Debug inspection (/travellersdebug stable)
    // -------------------------------------------------------------------------

    /** One-line live state of a Stable - loaded/virtual, pool breakdown, hands, chest stock. */
    public String debugSummary(WorldServer world, PlayerZoneRecord zone) {
        if (!(zone.data instanceof StableZoneData)) return "(not a stable)";
        StableZoneData data = (StableZoneData) zone.data;
        boolean loaded = isZoneFullyLoaded(world, zone);
        int hl = CampsiteManager.isNight(world) ? 0
                : StableBedService.countLoadedPalfreniersOnStation(world, zone, data);
        int hv = StableBedService.countVirtualPalfreniersOnStation(world, zone, data);
        return StableWatchLog.summary(data, loaded, hl, hv);
    }

    /** Cross-checks a loaded Stable's pool entries against the real horses in its box - orphan live
     * horses, dead links, count mismatches. "OK" as the first line means the two agree. */
    public java.util.List<String> debugVerify(WorldServer world, PlayerZoneRecord zone) {
        java.util.List<String> out = new ArrayList<>();
        if (!(zone.data instanceof StableZoneData)) {
            out.add("(not a stable)");
            return out;
        }
        StableZoneData data = (StableZoneData) zone.data;
        if (!world.isBlockLoaded(zone.center)) {
            out.add("zone not loaded - " + data.pool.size() + " pool entries, cannot check against live entities");
            return out;
        }
        int m = TravellersConfig.STABLES.containmentMarginBlocks + 2;
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(
                zone.center.getX() - zone.halfWidth - m, zone.center.getY() - zone.halfHeight - m,
                zone.center.getZ() - zone.halfDepth - m,
                zone.center.getX() + zone.halfWidth + 1 + m, zone.center.getY() + zone.halfHeight + 1 + m,
                zone.center.getZ() + zone.halfDepth + 1 + m);

        Set<UUID> taggedInWorld = new HashSet<>();
        for (EntityHorse h : world.getEntitiesWithinAABB(EntityHorse.class, box)) {
            if (h.isDead) continue;
            UUID tag = getPoolTag(h);
            if (tag == null) continue;
            taggedInWorld.add(h.getUniqueID());
            StableHorseEntry e = data.findByPoolId(tag);
            if (e == null) {
                out.add("ORPHAN LIVE: horse " + StableWatchLog.shortId(h.getUniqueID())
                        + " carries tag " + StableWatchLog.shortId(tag) + " with no pool entry");
            } else if (!h.getUniqueID().equals(e.liveEntityUUID)) {
                out.add("LINK MISMATCH: entry " + StableWatchLog.shortId(tag) + " links "
                        + StableWatchLog.shortId(e.liveEntityUUID) + " but the tagged horse is "
                        + StableWatchLog.shortId(h.getUniqueID()));
            }
        }

        int liveLinked = 0, dormant = 0, deadLinks = 0;
        for (StableHorseEntry e : data.pool) {
            if (e.liveEntityUUID == null) {
                dormant++;
                if (e.horseNbt == null) out.add("NO-NBT DORMANT: entry " + StableWatchLog.shortId(e.poolId));
                continue;
            }
            liveLinked++;
            Entity ent = world.getEntityFromUuid(e.liveEntityUUID);
            if (!(ent instanceof EntityHorse) || ent.isDead) {
                deadLinks++;
                out.add("DEAD LINK: entry " + StableWatchLog.shortId(e.poolId) + " links "
                        + StableWatchLog.shortId(e.liveEntityUUID) + " - not a live horse");
            }
        }

        out.add(String.format(java.util.Locale.ROOT,
                "pool=%d (%d live-linked, %d dormant, %d dead links) | world=%d pool-tagged horses in box",
                data.pool.size(), liveLinked, dormant, deadLinks, taggedInWorld.size()));
        if (liveLinked - deadLinks != taggedInWorld.size()) {
            out.add("COUNT MISMATCH: " + (liveLinked - deadLinks) + " healthy live-linked entries vs "
                    + taggedInWorld.size() + " tagged horses in the world");
        }
        if (out.size() == 1) out.add(0, "OK - pool and world agree");
        return out;
    }

    // -------------------------------------------------------------------------
    // Player takes / returns a pool horse
    // -------------------------------------------------------------------------

    /** True if {@code horse} is a pool horse belonging to a Stable owned by {@code playerUUID} -
     * used by {@code MountTheftGuard} to let the owner (and only the owner) take one out. */
    public boolean isPoolHorseOwnedBy(WorldServer world, EntityHorse horse, UUID playerUUID) {
        return findPoolZone(world, horse, playerUUID) != null;
    }

    @javax.annotation.Nullable
    public UUID getPoolOwner(WorldServer world, EntityHorse horse) {
        PlayerZoneRecord zone = findPoolZone(world, horse, null);
        return zone != null ? zone.ownerUUID : null;
    }

    /** The Stable whose pool contains {@code horse}, optionally restricted to one owner. */
    private PlayerZoneRecord findPoolZone(WorldServer world, EntityHorse horse, UUID ownerFilter) {
        UUID poolId = getPoolTag(horse);
        for (PlayerZoneRecord zone : PlayerZoneManager.get(world).getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            if (ownerFilter != null && !ownerFilter.equals(zone.ownerUUID)) continue;
            StableZoneData data = (StableZoneData) zone.data;
            if (data.findByLiveEntity(horse.getUniqueID()) != null
                    || (poolId != null && data.findByPoolId(poolId) != null)) {
                return zone;
            }
        }
        return null;
    }

    /** The Stable owned by {@code ownerUUID} whose box contains {@code pos}, or null - where a
     * dismounting owner drops a horse back into the pool. */
    public PlayerZoneRecord findOwnStableAt(WorldServer world, UUID ownerUUID, BlockPos pos) {
        for (PlayerZoneRecord zone : PlayerZoneManager.get(world).getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            if (!ownerUUID.equals(zone.ownerUUID)) continue;
            if (Math.abs(pos.getX() - zone.center.getX()) <= zone.halfWidth
                    && Math.abs(pos.getZ() - zone.center.getZ()) <= zone.halfDepth
                    && Math.abs(pos.getY() - zone.center.getY()) <= zone.halfHeight) {
                return zone;
            }
        }
        return null;
    }

    /** The owner mounted one of their pool horses to take it out: drop the entry and give the horse
     * its normal vanilla AI back so it behaves like any tame horse once ridden away. */
    public void detachHorseFromPool(WorldServer world, EntityHorse horse) {
        PlayerZoneRecord zone = findPoolZone(world, horse, null);
        if (zone != null) {
            StableZoneData data = (StableZoneData) zone.data;
            UUID poolId = getPoolTag(horse);
            StableHorseEntry entry = poolId != null ? data.findByPoolId(poolId) : null;
            if (entry == null) entry = data.findByLiveEntity(horse.getUniqueID());
            if (entry != null) {
                data.pool.remove(entry);
                missingSince.remove(entry.poolId);
                PlayerZoneManager.get(world).markDirty();
                StableWatchLog.record(world, zone, StableWatchLog.Kind.LEAVE,
                        StableWatchLog.shortId(entry.poolId) + " taken by owner", true);
            }
        }
        trackedLiveHorses.remove(horse.getUniqueID());
        stripPoolTagFromEntity(horse);
        StableHorseAI.restoreVanillaAI(horse);
    }

    /** The owner dismounted a tame horse they own inside one of their Stable zones: fold it into
     * that zone's pool as a live-linked entry (never a dormant NBT one - the horse is right here). */
    public boolean attachHorseToPool(WorldServer world, PlayerZoneRecord zone, EntityHorse horse) {
        if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) return false;
        StableZoneData data = (StableZoneData) zone.data;
        if (data.findByLiveEntity(horse.getUniqueID()) != null) return false;

        long now = world.getTotalWorldTime();
        StableHorseEntry entry = new StableHorseEntry();
        entry.liveEntityUUID = horse.getUniqueID();
        entry.origin = StableHorseEntry.Origin.RECLAIMED_BY_OWNER;
        entry.tamed = horse.isTame();
        entry.saddled = horse.isHorseSaddled();
        entry.temper = horse.getTemper();
        entry.growingAge = horse.getGrowingAge();
        entry.reservedAtTick = now;
        entry.lastAgeSyncTick = now;
        entry.lastTameProgressTick = now;
        NBTTagCompound nbt = new NBTTagCompound();
        horse.writeToNBT(nbt);
        entry.horseNbt = nbt;
        data.pool.add(entry);

        setPoolTag(horse, entry.poolId);
        trackLiveHorse(horse.getUniqueID());
        StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
        StableContainmentGuard.enforce(horse, zone.center, zone.halfWidth, zone.halfDepth);
        PlayerZoneManager.get(world).markDirty();
        StableWatchLog.record(world, zone, StableWatchLog.Kind.INTAKE,
                StableWatchLog.shortId(entry.poolId) + " returned by owner", true);
        return true;
    }
}
