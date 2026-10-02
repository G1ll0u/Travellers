package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.world.camp.CampsiteManager;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-tick driver for every Stable zone. A Stable is either <b>live</b> (vanilla ticks its box: every
 * pool horse is a real entity) or <b>dormant</b> (every pool horse is data on its entry), never both
 * for one horse: going dormant captures and removes the entities, a chunk unloading captures the pool
 * horses in it before the chunk is saved ({@link StableChunkUnloadHandler}), and a pool horse joining
 * the world is only accepted if its entry links exactly that entity ({@link StableHorseLoadHandler}).
 * Live: containment, intake and live sync ({@link StablePhysicalReconciler}), work done by loaded
 * palfreniers. Dormant: growth ({@link StableAging}) and the elapsed-time {@link StableWork#timerPass}.
 * Transient state only - the pool lives in {@code PlayerZoneManager}'s records.
 */
public final class StableCoordinator {

    /** Custom entity-data key (persisted via {@code Entity}'s "ForgeData" tag) marking a live horse
     * as belonging to a {@link StableHorseEntry#poolId}. */
    private static final String POOL_ID_TAG = "TravellersStablePoolId";

    /** Extra blocks around the containment box whose chunks must be loaded before a Stable goes live
     * (a horse may drift a few blocks past the margin before containment brings it back). */
    private static final int LIVE_CHUNK_SLACK = 8;

    private static final Map<Integer, StableCoordinator> INSTANCES = new ConcurrentHashMap<>();

    /** Zones whose pool horses are live entities right now, keyed by {@code PlayerZoneRecord.id}. */
    private final Set<UUID> liveZones = ConcurrentHashMap.newKeySet();
    /** World tick each zone was first seen ticking, for the go-live debounce. */
    private final Map<UUID, Long> tickingSince = new ConcurrentHashMap<>();
    /** Live-linked entries whose horse was missing on the last live pass, keyed by poolId. */
    private final Set<UUID> missingOnce = ConcurrentHashMap.newKeySet();
    /** Last logged load/scheduler state per zone, so {@link StableWatchLog} only records transitions. */
    private final Map<UUID, String> lastZoneState = new ConcurrentHashMap<>();

    private StableCoordinator() {
    }

    public static StableCoordinator get(WorldServer world) {
        return INSTANCES.computeIfAbsent(world.provider.getDimension(), d -> new StableCoordinator());
    }

    /** Called on server shutdown - {@link #INSTANCES} is keyed by dimension id, which two different
     * save files loaded in one client session can both reuse. */
    public static void resetAll() {
        INSTANCES.clear();
    }

    // -------------------------------------------------------------------------
    // Tag helpers
    // -------------------------------------------------------------------------

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

    public static boolean isPoolHorse(Entity e) {
        return e instanceof EntityHorse && e.getEntityData().hasUniqueId(POOL_ID_TAG);
    }

    // A pool horse is never a mount: a leftover bond or orphan marker lets the mount system release,
    // claim or clean it up while the pool still owns it.
    public static void sanitizeLive(EntityHorse horse) {
        TravellerMountSupport.dropBondFromHorse(horse);
    }

    /** Snapshot counterpart of {@link #sanitizeLive}, plus identity and leash. */
    public static void sanitizeSnapshot(NBTTagCompound horseNbt) {
        horseNbt.removeTag("UUIDMost");
        horseNbt.removeTag("UUIDLeast");
        horseNbt.removeTag("Leash");
        if (horseNbt.hasKey("ForgeData", 10)) {
            TravellerMountSupport.clearMountMarkers(horseNbt.getCompoundTag("ForgeData"));
        }
    }

    /** A pool horse leaves the pool for good and stays in the world as an ordinary horse. */
    static void makeOrdinary(EntityHorse horse) {
        stripPoolTagFromEntity(horse);
        sanitizeLive(horse);
        StableHorseAI.restoreVanillaAI(horse);
    }

    /** The Stable whose pool holds {@code poolId}, or null. */
    public PlayerZoneRecord findZoneForPoolHorse(WorldServer world, UUID poolId) {
        for (PlayerZoneRecord zone : PlayerZoneManager.get(world).getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            if (((StableZoneData) zone.data).findByPoolId(poolId) != null) return zone;
        }
        return null;
    }

    public boolean isZoneLive(PlayerZoneRecord zone) {
        return liveZones.contains(zone.id);
    }

    // -------------------------------------------------------------------------
    // Per-tick driver
    // -------------------------------------------------------------------------

    public void tick(WorldServer world, long now) {
        if (!TravellersConfig.STABLES.enabled) return;
        if (now % TravellersConfig.STABLES.containmentCheckIntervalTicks != 0) return;

        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        StableRelease.processPending(world, mgr);

        for (PlayerZoneRecord zone : mgr.getAll()) {
            if (zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) continue;
            StableZoneData data = (StableZoneData) zone.data;

            boolean dirty = StableAging.advance(world, zone, data, now);
            boolean night = CampsiteManager.isNight(world);
            boolean live = liveZones.contains(zone.id);

            if (isZoneTicking(world, zone)) {
                Long since = tickingSince.putIfAbsent(zone.id, now);
                if (!live && now - (since == null ? now : since)
                        >= TravellersConfig.MATERIALIZATION.materializeChunkStableTicks) {
                    goLive(world, zone, data);
                    live = true;
                }
            } else {
                tickingSince.remove(zone.id);
                if (live) {
                    dirty |= goDormant(world, zone, data);
                    live = false;
                }
            }

            int loadedHands = 0;
            int virtualHands = 0;
            if (live) {
                loadedHands = night ? 0 : StableBedService.countLoadedPalfreniersOnStation(world, zone, data);
                StableContainmentSweep.run(world, zone, data);
                StablePhysicalReconciler.reconcile(world, mgr, zone, this);
            } else if (!night) {
                // Dormant: nobody can act on the horses, so every palfrenier on station (a frozen
                // loaded body included) counts as a hand for the elapsed-time pass.
                virtualHands = StableBedService.countVirtualPalfreniersOnStation(world, zone, data)
                        + StableBedService.countLoadedPalfreniersOnStation(world, zone, data);
                if (virtualHands > 0) dirty |= StableWork.timerPass(world, zone, data, now, virtualHands);
            }
            StableWatchLog.maybeSnapshot(world, zone, data, live, loadedHands, virtualHands);
            logZoneStateChange(world, zone, live, night, loadedHands, virtualHands);

            if (dirty) mgr.markDirty();
        }
    }

    /** Whether vanilla ticks this Stable's whole containment box: every chunk around it loaded and
     * no corner frozen by vanilla's entity-ticking gates. Never loads a chunk. */
    public boolean isZoneTicking(WorldServer world, PlayerZoneRecord zone) {
        int margin = TravellersConfig.STABLES.containmentMarginBlocks;
        int ex = zone.halfWidth + margin + LIVE_CHUNK_SLACK;
        int ez = zone.halfDepth + margin + LIVE_CHUNK_SLACK;
        int cx = zone.center.getX(), cz = zone.center.getZ();
        for (int x = (cx - ex) >> 4; x <= (cx + ex) >> 4; x++) {
            for (int z = (cz - ez) >> 4; z <= (cz + ez) >> 4; z++) {
                if (!world.getChunkProvider().chunkExists(x, z)) return false;
            }
        }
        int bx = zone.halfWidth + margin, bz = zone.halfDepth + margin;
        return !VirtualTravellerManager.isFrozenByVanillaAt(world, cx - bx, cz - bz)
                && !VirtualTravellerManager.isFrozenByVanillaAt(world, cx + bx, cz - bz)
                && !VirtualTravellerManager.isFrozenByVanillaAt(world, cx - bx, cz + bz)
                && !VirtualTravellerManager.isFrozenByVanillaAt(world, cx + bx, cz + bz);
    }

    /** Dormant -> live. A link that no longer resolves (a crash between saves) falls back to the
     * entry's data; the reconcile pass right after spawns every data entry. */
    private void goLive(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID == null) continue;
            Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
            if (!(ent instanceof EntityHorse) || ent.isDead) entry.liveEntityUUID = null;
        }
        liveZones.add(zone.id);
    }

    /** Live -> dormant: every live pool horse becomes data on its entry and leaves the world. */
    private boolean goDormant(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        boolean changed = false;
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID == null) continue;
            Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
            // Not in the world: its chunk already left, and the chunk copy stays the linked one.
            if (!(ent instanceof EntityHorse) || ent.isDead) continue;
            capture(world, entry, (EntityHorse) ent);
            changed = true;
        }
        liveZones.remove(zone.id);
        missingOnce.clear();
        return changed;
    }

    /** One live pool horse becomes data on its entry (position kept) and is removed from the world. */
    void capture(WorldServer world, StableHorseEntry entry, EntityHorse horse) {
        horse.removePassengers();
        NBTTagCompound nbt = new NBTTagCompound();
        horse.writeToNBT(nbt);
        sanitizeSnapshot(nbt);
        entry.horseNbt = nbt;
        entry.tamed = horse.isTame();
        entry.saddled = horse.isHorseSaddled();
        entry.temper = horse.getTemper();
        entry.growingAge = horse.getGrowingAge();
        entry.lastAgeSyncTick = world.getTotalWorldTime();
        entry.liveEntityUUID = null;
        horse.setDead();
    }

    /** A chunk holding a pool horse is unloading (before it is saved). */
    void onPoolHorseChunkUnload(WorldServer world, EntityHorse horse) {
        UUID poolId = getPoolTag(horse);
        PlayerZoneRecord zone = poolId != null ? findZoneForPoolHorse(world, poolId) : null;
        if (zone == null) {
            makeOrdinary(horse);
            return;
        }
        StableHorseEntry entry = ((StableZoneData) zone.data).findByPoolId(poolId);
        if (horse.getUniqueID().equals(entry.liveEntityUUID)) {
            capture(world, entry, horse); // the next tick sees the zone no longer ticking and puts the rest to sleep
        } else {
            horse.setDead(); // a stale copy: the entry holds the horse elsewhere or as data
        }
        PlayerZoneManager.get(world).markDirty();
    }

    /** Live pass: a linked horse gone from the world without dying or unloading (removed by some
     * other means) is dropped from the pool after two consecutive misses. */
    boolean dropVanishedHorses(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        boolean changed = false;
        Iterator<StableHorseEntry> it = data.pool.iterator();
        while (it.hasNext()) {
            StableHorseEntry entry = it.next();
            if (entry.liveEntityUUID == null) continue;
            Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
            if (ent instanceof EntityHorse && !ent.isDead) {
                missingOnce.remove(entry.poolId);
                continue;
            }
            if (missingOnce.add(entry.poolId)) continue;
            missingOnce.remove(entry.poolId);
            it.remove();
            changed = true;
            StableWatchLog.record(world, zone, StableWatchLog.Kind.LEAVE,
                    StableWatchLog.shortId(entry.poolId) + " vanished", true);
        }
        return changed;
    }

    // -------------------------------------------------------------------------
    // Zone deletion
    // -------------------------------------------------------------------------

    /** Frees the horse pool of a Stable being deleted: live horses become ordinary horses where they
     * stand, data horses are queued to appear as ordinary horses at their spot once that ground
     * ticks. Workers are released beforehand by {@code ZoneAssignments.onZoneDeleted}. */
    public void releaseZone(WorldServer world, PlayerZoneRecord zone) {
        StableZoneData data = (zone.data instanceof StableZoneData) ? (StableZoneData) zone.data : null;
        if (data == null) return;

        PlayerZoneManager mgr = PlayerZoneManager.get(world);
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID != null) {
                Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
                if (ent instanceof EntityHorse && !ent.isDead) makeOrdinary((EntityHorse) ent);
                // Not loaded: its chunk copy loses the pool tag when it joins (no zone any more).
                continue;
            }
            if (entry.horseNbt == null || StableMaterializer.isDeadSnapshot(entry.horseNbt)) continue;
            mgr.addPendingHorseRelease(StableRelease.releaseSnapshot(entry.horseNbt, zone));
        }
        data.pool.clear();
        mgr.markDirty();

        liveZones.remove(zone.id);
        tickingSince.remove(zone.id);
        lastZoneState.remove(zone.id);
        StableWatchLog.forget(zone.id);
    }

    /** Records a {@link StableWatchLog} ZONE line only when a Stable's state or which scheduler is
     * driving its pool actually changes. */
    private void logZoneStateChange(WorldServer world, PlayerZoneRecord zone, boolean live,
                                    boolean night, int loadedHands, int virtualHands) {
        String scheduler = night ? "night (idle)"
                : loadedHands > 0 ? "loaded palfrenier x" + loadedHands
                : virtualHands > 0 ? "offscreen timer x" + virtualHands
                : "idle";
        String state = (live ? "live" : "dormant") + " | " + scheduler;
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
        boolean loaded = isZoneLive(zone);
        int hl = CampsiteManager.isNight(world) ? 0
                : StableBedService.countLoadedPalfreniersOnStation(world, zone, data);
        int hv = StableBedService.countVirtualPalfreniersOnStation(world, zone, data);
        return StableWatchLog.summary(data, loaded, hl, hv);
    }

    /** Checks the live-XOR-data invariant of a Stable against the world. "OK" as the first line
     * means the pool and the world agree. */
    public java.util.List<String> debugVerify(WorldServer world, PlayerZoneRecord zone) {
        java.util.List<String> out = new ArrayList<>();
        if (!(zone.data instanceof StableZoneData)) {
            out.add("(not a stable)");
            return out;
        }
        StableZoneData data = (StableZoneData) zone.data;
        boolean live = isZoneLive(zone);

        int linked = 0, asData = 0;
        for (StableHorseEntry e : data.pool) {
            if (e.liveEntityUUID == null) {
                asData++;
                if (e.horseNbt == null) out.add("NO DATA: entry " + StableWatchLog.shortId(e.poolId) + " has neither a horse nor a snapshot");
                continue;
            }
            linked++;
            Entity ent = world.getEntityFromUuid(e.liveEntityUUID);
            boolean present = ent instanceof EntityHorse && !ent.isDead;
            if (live && !present) {
                out.add("MISSING: entry " + StableWatchLog.shortId(e.poolId) + " links "
                        + StableWatchLog.shortId(e.liveEntityUUID) + " but no such horse is in the world");
            } else if (!live && present) {
                out.add("LIVE WHILE DORMANT: entry " + StableWatchLog.shortId(e.poolId) + " still has a horse in the world");
            }
        }
        if (live && asData > 0) out.add(asData + " entries held as data while live (spawned on the next pass)");

        int tagged = 0;
        for (Entity ent : new ArrayList<>(world.loadedEntityList)) {
            if (!(ent instanceof EntityHorse) || ent.isDead) continue;
            UUID tag = getPoolTag((EntityHorse) ent);
            if (tag == null) continue;
            StableHorseEntry e = data.findByPoolId(tag);
            if (e == null) continue;
            tagged++;
            if (!ent.getUniqueID().equals(e.liveEntityUUID)) {
                out.add("SECOND COPY: horse " + StableWatchLog.shortId(ent.getUniqueID()) + " carries entry "
                        + StableWatchLog.shortId(tag) + " but is not the linked one");
            }
        }

        out.add(String.format(java.util.Locale.ROOT, "%s | pool=%d (%d linked, %d as data) | %d pool horses in the world",
                live ? "live" : "dormant", data.pool.size(), linked, asData, tagged));
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
                PlayerZoneManager.get(world).markDirty();
                StableWatchLog.record(world, zone, StableWatchLog.Kind.LEAVE,
                        StableWatchLog.shortId(entry.poolId) + " taken by owner", true);
            }
        }
        makeOrdinary(horse);
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
        sanitizeLive(horse);
        NBTTagCompound nbt = new NBTTagCompound();
        horse.writeToNBT(nbt);
        entry.horseNbt = nbt;
        data.pool.add(entry);

        setPoolTag(horse, entry.poolId);
        StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
        StableContainmentGuard.enforce(horse, zone.center, zone.halfWidth, zone.halfDepth);
        PlayerZoneManager.get(world).markDirty();
        StableWatchLog.record(world, zone, StableWatchLog.Kind.INTAKE,
                StableWatchLog.shortId(entry.poolId) + " returned by owner", true);
        return true;
    }
}
