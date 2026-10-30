package com.jubitus.travellers.camp;

import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.camp.shelter.ShelterPlacer;
import com.jubitus.travellers.camp.shelter.ShelterTemplate;
import com.jubitus.travellers.camp.shelter.ShelterTemplateRegistry;
import com.jubitus.travellers.camp.shelter.TerrainSnapshot;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.village.MillSettlements;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * World-saved catalog of discovered campsites. Discovery is fed by ChunkReader (one fixed roll per
 * chunk); new campsites are laid out under a per-tick budget. Never loads a chunk.
 */
public class CampsiteManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersCampsites";

    private final List<CampsiteRecord> records = new ArrayList<>();
    static long discoveryChecked;
    static long discoveryHits;
    static long discoveryCreated;
    static long discoveryDroppedAtLayout;
    private final Random rand = new Random();

    // --- Perf indexes (transient, rebuilt from `records`/`readFromNBT` — never separately serialized) ---
    // On worlds with thousands of campsites, a plain linear scan of `records` per lookup (the original
    // design) becomes the dominant server cost once population/camp count grows — every non-camping
    // virtual entity re-scans every campsite every refresh cycle to look for one to join, and every
    // already-camping entity re-scans every campsite every tick just to resolve its own camp by id.
    /** id -> record, O(1) instead of a linear scan. */
    private final Map<UUID, CampsiteRecord> byId = new HashMap<>();
    /** occupant traveller UUID -> the record they currently occupy, O(1) instead of scanning every record's occupant map. */
    private final Map<UUID, CampsiteRecord> occupantIndex = new HashMap<>();
    /** Coarse spatial bucket (dim,cellX,cellZ) -> records whose center falls in that cell, for "nearby" queries. */
    private final Map<Long, List<CampsiteRecord>> spatialGrid = new HashMap<>();
    // Cell size well above the typical campsite min-spacing/seek-radius so a "nearby" query only ever
    // has to look at a handful of cells regardless of total campsite count.
    private static final int GRID_CELL_SIZE = 512;

    public CampsiteManager() {
        super(DATA_NAME);
    }

    public CampsiteManager(String name) {
        super(name);
    }

    public static CampsiteManager get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        CampsiteManager data = (CampsiteManager) storage.getOrLoadData(CampsiteManager.class, DATA_NAME);
        if (data == null) {
            data = new CampsiteManager();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    // --- Perf index maintenance: every mutation of `records` or of an occupants map must go through
    // one of these so byId/occupantIndex/spatialGrid never drift out of sync with the source of truth. ---

    private static int cellCoord(int block) {
        return Math.floorDiv(block, GRID_CELL_SIZE);
    }

    private static long packCell(int dim, int cellX, int cellZ) {
        return ((long) dim & 0xFFFF) << 48 | ((long) cellX & 0xFFFFFF) << 24 | ((long) cellZ & 0xFFFFFF);
    }

    private void indexRecord(CampsiteRecord rec) {
        byId.put(rec.id, rec);
        long key = packCell(rec.dimension, cellCoord(rec.center.getX()), cellCoord(rec.center.getZ()));
        spatialGrid.computeIfAbsent(key, k -> new ArrayList<>()).add(rec);
        for (CampOccupant o : rec.occupants.values()) occupantIndex.put(o.travellerUUID, rec);
    }

    private void deindexRecord(CampsiteRecord rec) {
        byId.remove(rec.id);
        long key = packCell(rec.dimension, cellCoord(rec.center.getX()), cellCoord(rec.center.getZ()));
        List<CampsiteRecord> bucket = spatialGrid.get(key);
        if (bucket != null) {
            bucket.remove(rec);
            if (bucket.isEmpty()) spatialGrid.remove(key);
        }
        for (CampOccupant o : rec.occupants.values()) occupantIndex.remove(o.travellerUUID);
    }

    /** Call after adding an occupant to a record's `occupants` map so {@link #campOf} stays O(1). */
    void indexOccupant(UUID travellerUUID, CampsiteRecord rec) {
        occupantIndex.put(travellerUUID, rec);
    }

    /** Call after removing an occupant from a record's `occupants` map. */
    void deindexOccupant(UUID travellerUUID) {
        occupantIndex.remove(travellerUUID);
    }

    @FunctionalInterface
    private interface RecordVisitor {
        void visit(CampsiteRecord rec);
    }

    /** Visits every record whose grid cell overlaps a {@code radius}-block box around (x,z) in {@code dim}. */
    private void forEachNearby(int dim, double x, double z, int radius, RecordVisitor visitor) {
        int minCx = cellCoord((int) Math.floor(x - radius));
        int maxCx = cellCoord((int) Math.floor(x + radius));
        int minCz = cellCoord((int) Math.floor(z - radius));
        int maxCz = cellCoord((int) Math.floor(z + radius));
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                List<CampsiteRecord> bucket = spatialGrid.get(packCell(dim, cx, cz));
                if (bucket == null) continue;
                for (CampsiteRecord rec : bucket) visitor.visit(rec);
            }
        }
    }

    public List<CampsiteRecord> getAll() {
        return Collections.unmodifiableList(records);
    }

    /** Removes one record outright (identity-based). Used by a full "camp reset all" wipe. */
    public void removeRecord(CampsiteRecord rec) {
        records.remove(rec);
        deindexRecord(rec);
        markDirty();
    }

    /** Clears discovery bookkeeping so previously-probed chunks get a fresh chance at rediscovery. */
    /** Called by ChunkReader for every chunk it reads. Each chunk has a fixed yes/no roll, so reloads never add campsites. */
    public void onChunkRead(WorldServer world, int cx, int cz) {
        discoveryChecked++;
        if (!rollsCampsite(world.getSeed(), cx, cz)) return;
        discoveryHits++;
        tryDiscover(world, cx, cz);
    }

    static boolean rollsCampsite(long seed, int cx, int cz) {
        long h = seed + 0x2F7A3C9D1E5B4A61L + cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53 < TravellersConfig.CAMP_EVENTS.discoveryChancePerChunk;
    }

    public static String discoveryStats() {
        return String.format("chunks checked %d  lucky %d  campsites created %d  dropped (too few tent spots) %d",
                discoveryChecked, discoveryHits, discoveryCreated, discoveryDroppedAtLayout);
    }

    public static void resetDiscoveryCounters() {
        discoveryChecked = discoveryHits = discoveryCreated = discoveryDroppedAtLayout = 0;
    }

    private static final int CAMP_TICK_INTERVAL = 20;
    private long lastCampTick = -1;

    /**
     * Radius within which a camp is physically reconciled. One chunk beyond the view distance, so
     * blocks catch up before the chunks render and before virtual entities materialize (they spawn
     * at (viewDistance - 1) chunks — see VirtualTravellerManager.checkPlayerMaterialize).
     */
    private static int reconcileRadius(WorldServer world) {
        int viewChunks;
        try {
            viewChunks = world.getMinecraftServer().getPlayerList().getViewDistance();
        } catch (Throwable t) {
            viewChunks = 10;
        }
        return (viewChunks + 1) * 16;
    }

    /** Lays out newly found campsites under a budget, then ticks camps. */
    public void tick(WorldServer world, long now) {
        if (!TravellersConfig.CAMP_EVENTS.enabled) return;
        lazyComputePass(world, TravellersConfig.CAMP_EVENTS.campLayoutsPerTick);

        if (now - lastCampTick >= CAMP_TICK_INTERVAL) {
            lastCampTick = now;
            tickCamps(world, now);
        }
    }

    // -------------------------------------------------------------------------
    // Camp occupation lifecycle (P3)
    // -------------------------------------------------------------------------

    /** Per-camp: process departures, advance offscreen build/teardown progress, then reconcile physically when a player is near. */
    private void tickCamps(WorldServer world, long now) {
        int dim = world.provider.getDimension();
        // Snapshot: revalidateVillageProximity may remove a record from `records` mid-pass.
        for (CampsiteRecord rec : new ArrayList<>(records)) {
            if (rec.dimension != dim) continue;

            boolean nearLoaded = isPlayerNearCamp(world, rec) && footprintChunksLoaded(world, rec);
            if (nearLoaded) {
                if (revalidateVillageProximity(world, rec)) continue; // whole record evacuated + removed
                revalidateBuildingZoneOverlap(world, rec); // surgical: only slots actually overlapping a zone
            }

            boolean active = !rec.occupants.isEmpty() || !rec.pendingTeardowns.isEmpty() || rec.firepitBuilt;
            if (!active) continue;

            processDepartures(world, rec, now);
            advanceVirtualBuilds(world, rec, now);
            tickEmptyFirepit(rec, now);

            if (nearLoaded) {
                reconcilePhysical(world, rec);
            }
        }
    }

    /**
     * A village or lone building can spawn/grow within a campsite's protected radius after that
     * campsite was already discovered (and possibly built on) — {@link MillSettlements#isNear}
     * is only ever checked once, at discovery time. This re-checks it whenever the campsite's own
     * footprint chunks are loaded (piggybacking on the same player-proximity gate as
     * {@link #reconcilePhysical}) and, on a violation, force-evacuates the whole record: any built or
     * mid-build shelter is torn down (skipping cells the settlement itself now occupies — the
     * village's own blocks are never touched), and the record is permanently removed. Never
     * reconsidered: the record is gone, not just disabled, mirroring the natural-terrain slot
     * eviction elsewhere in this system.
     *
     * @return true if the record was evacuated and removed.
     */
    private boolean revalidateVillageProximity(WorldServer world, CampsiteRecord rec) {
        if (!MillSettlements.isNear(world, rec.center,
                TravellersConfig.CAMP_EVENTS.campsiteMinVillageDistance)) {
            return false;
        }

        int searchRadius = TravellersConfig.CAMP_EVENTS.campsiteMinVillageDistance;
        for (CampOccupant occ : rec.occupants.values()) {
            deindexOccupant(occ.travellerUUID);
            if (occ.snapshot != null) occ.snapshot.restoreSkippingVillageZone(world);
        }
        rec.occupants.clear();
        for (TerrainSnapshot s : rec.pendingTeardowns) s.restoreSkippingVillageZone(world);
        rec.pendingTeardowns.clear();
        for (TerrainSnapshot s : rec.orphans.values()) s.restoreSkippingVillageZone(world);
        rec.orphans.clear();
        if (rec.firepitBuilt && rec.firepitSnapshot != null) {
            rec.firepitSnapshot.restoreSkippingVillageZone(world);
        }
        rec.firepitBuilt = false;
        rec.firepitSnapshot = null;

        net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN,
                "[Travellers/Camp] campsite at %s evacuated: a Millénaire village/lone building is now within %d blocks",
                rec.center, searchRadius);
        removeRecord(rec);
        return true;
    }

    /**
     * Distinct, more surgical companion to {@link #revalidateVillageProximity}: even when a whole
     * campsite stays outside the configured village-distance buffer, one satellite building (e.g. a
     * farm/windmill placed at the edge of a growing village) can still expand to physically overlap
     * a single occupied slot's footprint. Evicts and permanently disables only that slot — the same
     * treatment {@link CampBuildCoordinator#ensureTerrainPrepared} already gives a slot that fails
     * the natural-terrain check — instead of tearing down the whole camp.
     */
    private void revalidateBuildingZoneOverlap(WorldServer world, CampsiteRecord rec) {
        if (rec.occupants.isEmpty()) return;
        int searchRadius = TravellersConfig.CAMP_EVENTS.campsiteMinVillageDistance;
        // One village/lone-building scan for the whole record's footprint, reused below per slot —
        // this is the only part whose cost scales with total village/lone-building count, so it must
        // run once per record, not once per occupied slot.
        BlockPos recMin = rec.center.add(-rec.radius, 0, -rec.radius);
        BlockPos recMax = rec.center.add(rec.radius, 0, rec.radius);
        List<MillFootprints.Footprint> zones = MillFootprints.reservedIn(world, recMin, recMax);
        if (zones.isEmpty()) return; // fast path: no village/lone-building anywhere near this camp

        for (java.util.Iterator<Map.Entry<Integer, CampOccupant>> it = rec.occupants.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, CampOccupant> e = it.next();
            int slotIndex = e.getKey();
            if (slotIndex < 0 || slotIndex >= rec.slots.size()) continue;
            ShelterSlot slot = rec.slots.get(slotIndex);
            BlockPos min = slot.origin;
            BlockPos max = slot.origin.add(ShelterSlot.FOOTPRINT - 1, 0, ShelterSlot.FOOTPRINT - 1);
            if (!MillFootprints.anyReserved(zones, min, max)) continue;

            CampOccupant occ = e.getValue();
            deindexOccupant(occ.travellerUUID);
            if (occ.snapshot != null) occ.snapshot.restoreSkippingVillageZone(world);
            slot.disabled = true;
            it.remove();
            markDirty();
            net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.WARN,
                    "[Travellers/Camp] slot %d at campsite %s evacuated: a Millénaire building zone now overlaps it",
                    slotIndex, rec.center);
        }
    }

    /**
     * Logical (voluntary) departures: occupants whose stay ended start tearing down (buildProgress
     * counts back down instead of being removed instantly) — the slot stays reserved until the
     * teardown animation/simulation actually finishes (see {@link #finalizeDeparture}).
     * Deferred while it's night: occupants must always sleep through the whole night (virtual or
     * loaded) and only start tearing down once day breaks, even if departTick already passed.
     */
    private void processDepartures(World world, CampsiteRecord rec, long now) {
        if (isNight(world)) return;
        for (CampOccupant occ : rec.occupants.values()) {
            if (occ.buildState != CampOccupant.BuildState.TEARING_DOWN && now >= occ.departTick) {
                occ.buildState = CampOccupant.BuildState.TEARING_DOWN;
                occ.lastProgressTick = now; // reset: avoids a bogus jump from time elapsed since it finished building
                markDirty();
            }
        }
    }

    /**
     * Advances build/teardown progress for occupants with no loaded entity nearby — pure bookkeeping
     * (no world access), so it runs regardless of whether the campsite's chunks are loaded. Finishing a
     * build flips BUILT; finishing a teardown frees the slot via {@link #finalizeDeparture}.
     */
    private void advanceVirtualBuilds(WorldServer world, CampsiteRecord rec, long now) {
        if (rec.occupants.isEmpty()) return;
        int ticksPerBlock = TravellersConfig.CAMP_EVENTS.virtualBuildTicksPerBlock;
        List<Integer> finishedTeardown = null;
        for (CampOccupant occ : rec.occupants.values()) {
            if (occ.buildState == CampOccupant.BuildState.BUILT) continue; // static until departure resets it
            if (world.getEntityFromUuid(occ.travellerUUID) != null) continue; // loaded AI drives it directly
            if (occ.slotIndex < 0 || occ.slotIndex >= rec.slots.size()) continue;
            ShelterSlot slot = rec.slots.get(occ.slotIndex);
            boolean finished = CampBuildCoordinator.advanceVirtual(occ, slot, rand, now, ticksPerBlock);
            if (!finished) continue;
            markDirty();
            if (occ.buildState == CampOccupant.BuildState.BUILDING) {
                occ.buildState = CampOccupant.BuildState.BUILT;
            } else if (occ.buildState == CampOccupant.BuildState.TEARING_DOWN) {
                if (finishedTeardown == null) finishedTeardown = new ArrayList<>();
                finishedTeardown.add(occ.slotIndex);
            }
        }
        if (finishedTeardown != null) {
            for (int slotIndex : finishedTeardown) finalizeDeparture(world, rec, slotIndex);
        }
    }

    /**
     * An abandoned camp's fire goes out on its own: once a camp with a built firepit has had no
     * occupants for {@code emptyCampFirepitExtinguishTicks} (e.g. they were all killed — a voluntary
     * last leaver douses and removes the pit itself via {@link #finalizeDeparture}), flip the logical
     * lit flag off. Pure bookkeeping, works offscreen; the physical block state catches up in
     * {@link #reconcilePhysical}. The unlit pit lingers like a ruin; the next group's fire-lighting
     * ritual relights it.
     */
    private void tickEmptyFirepit(CampsiteRecord rec, long now) {
        if (!rec.firepitBuilt) {
            if (rec.emptySinceTick != -1L) { rec.emptySinceTick = -1L; markDirty(); }
            return;
        }
        if (!rec.occupants.isEmpty()) {
            if (rec.emptySinceTick != -1L) { rec.emptySinceTick = -1L; markDirty(); }
            return;
        }
        if (rec.emptySinceTick < 0) {
            rec.emptySinceTick = now;
            markDirty();
            return;
        }
        if (rec.firepitLit && now - rec.emptySinceTick >= TravellersConfig.CAMP_EVENTS.emptyCampFirepitExtinguishTicks) {
            rec.firepitLit = false;
            markDirty();
        }
    }

    /**
     * Removes {@code slotIndex}'s occupant once its teardown has fully completed (buildProgress==0),
     * restoring the terrain it still occupies — instantly if the footprint is loaded, deferred via
     * {@code pendingTeardowns} otherwise. If this was the last occupant, also cleans up lingering ruins
     * and the firepit. Callers that want the firepit break animated (particles) must do so themselves
     * — via {@link com.jubitus.travellers.camp.build.IncrementalStructureBuilder#breakStep} on
     * the firepit position — before calling this; the restore here is idempotent either way.
     */
    public void finalizeDeparture(WorldServer world, CampsiteRecord rec, int slotIndex) {
        CampOccupant occ = rec.occupants.remove(slotIndex);
        if (occ == null) return;
        deindexOccupant(occ.travellerUUID);
        boolean loaded = footprintChunksLoaded(world, rec);
        if (occ.snapshot != null) {
            if (loaded) occ.snapshot.restore(world);
            else rec.pendingTeardowns.add(occ.snapshot);
        }
        if (rec.occupants.isEmpty()) {
            rec.pendingTeardowns.addAll(rec.orphans.values());
            rec.orphans.clear();
            if (rec.firepitBuilt && rec.firepitSnapshot != null) {
                if (loaded) rec.firepitSnapshot.restore(world);
                else rec.pendingTeardowns.add(rec.firepitSnapshot);
                rec.firepitBuilt = false;
                rec.firepitSnapshot = null;
            }
            rec.firepitLit = true; // a future firepit placement starts lit again
            rec.emptySinceTick = -1L;
        }
        markDirty();
    }

    /**
     * Brings the physical world in line with the occupation map: restores stale shelters, places the
     * firepit while occupied, and syncs every occupant's shelter to its current build progress — a
     * bulk catch-up (whether it's still 0, mid-way, or fully built) if it fell behind while offscreen,
     * a no-op if a loaded builder is already keeping it in step block by block.
     */
    private void reconcilePhysical(WorldServer world, CampsiteRecord rec) {
        boolean dirty = false;

        // 1. Restore terrain for shelters/firepit whose occupant finished leaving while unloaded.
        if (!rec.pendingTeardowns.isEmpty()) {
            for (TerrainSnapshot s : rec.pendingTeardowns) s.restore(world);
            rec.pendingTeardowns.clear();
            dirty = true;
        }

        // 2. Sync every occupant's shelter to its buildProgress.
        //    A loaded entity that hasn't placed a single block yet must first walk to approachPos (2
        //    blocks outside the footprint) — the 1.5-block threshold matches EntityAICampStay's own
        //    approach gate exactly — so terrain isn't graded right in front of the player before the
        //    entity arrives. Once progress is underway (even from virtual simulation) this no longer
        //    gates: the shelter must catch up to reflect what already (virtually) happened.
        //    Iterates a copy: syncPhysical can evict an occupant (natural-terrain check) mid-loop.
        for (CampOccupant occ : new ArrayList<>(rec.occupants.values())) {
            if (occ.slotIndex < 0 || occ.slotIndex >= rec.slots.size()) continue;
            ShelterSlot slot = rec.slots.get(occ.slotIndex);
            net.minecraft.entity.Entity physEnt = world.getEntityFromUuid(occ.travellerUUID);
            if (physEnt != null && occ.buildState == CampOccupant.BuildState.BUILDING && occ.buildProgress == 0) {
                BlockPos ap = slot.approachPos();
                double dsq = physEnt.getDistanceSq(ap.getX() + 0.5, ap.getY(), ap.getZ() + 0.5);
                if (dsq > 2.25) continue; // entity must be within 1.5 blocks of approachPos
            }
            // Reusing a slot that holds an abandoned ruin: clear the ruin first.
            TerrainSnapshot ruin = rec.orphans.remove(occ.slotIndex);
            if (ruin != null) ruin.restore(world);

            int beforeMark = occ.physicalMark;
            CampBuildCoordinator.syncPhysical(world, rec, occ, slot, rand);
            if (occ.bedPos == null) occ.bedPos = findBedInSlot(world, slot);
            if (beforeMark != occ.physicalMark) dirty = true;
        }

        // 3. Firepit appears (or an extinguished one is relit) once the camp is occupied. Removal
        //    happens on full cleanup (finalizeDeparture). When loaded entities are present: at least
        //    one that finished building must walk within 2 blocks of the firepit — that entity
        //    "lights" the fire. All-virtual: as soon as any occupant has finished building (mirrors
        //    the loaded ritual's timing).
        if (!rec.occupants.isEmpty() && (!rec.firepitBuilt || !rec.firepitLit)) {
            boolean anyLoaded = false;
            boolean anyBuilt = false;
            boolean anyLoadedNearFire = false;
            for (CampOccupant occ : rec.occupants.values()) {
                if (occ.buildState == CampOccupant.BuildState.BUILT) anyBuilt = true;
                net.minecraft.entity.Entity e = world.getEntityFromUuid(occ.travellerUUID);
                if (e == null) continue;
                anyLoaded = true;
                if (occ.buildState != CampOccupant.BuildState.BUILT) continue; // still building
                double dsq = e.getDistanceSq(rec.firepitPos.getX() + 0.5, rec.firepitPos.getY(), rec.firepitPos.getZ() + 0.5);
                if (dsq <= 4.0) { anyLoadedNearFire = true; break; }
            }
            if ((!anyLoaded && anyBuilt) || anyLoadedNearFire) {
                if (!rec.firepitBuilt) {
                    // Never place the pit into a block someone put at the camp center in the meantime —
                    // shift to a nearby natural cell instead (occupants would otherwise wait forever).
                    BlockPos firePos = findNaturalFirepitCell(world, rec.firepitPos);
                    if (firePos != null) {
                        rec.firepitPos = firePos;
                        rec.firepitSnapshot = CampFirepit.place(world, firePos);
                        rec.firepitBuilt = rec.firepitSnapshot != null;
                        rec.firepitLit = rec.firepitBuilt;
                        dirty = true;
                    }
                } else {
                    rec.firepitLit = true;
                    dirty = true;
                }
            }
        }

        // 4. Physical lit state follows the logical flag (extinguish after abandonment, relight above).
        if (rec.firepitBuilt && CampFirepit.isFirepitAt(world, rec.firepitPos)) {
            CampFirepit.setLit(world, rec.firepitPos, rec.firepitLit);
        }

        if (dirty) markDirty();
    }

    /**
     * The firepit cell itself if it's still natural terrain, else the nearest natural, standable cell
     * in the surrounding 5×5 ring (same Y), else null (center completely built over — no fire placed,
     * the camp just stays fireless until it cleans up).
     */
    @Nullable
    private static BlockPos findNaturalFirepitCell(WorldServer world, BlockPos center) {
        for (int ring = 0; ring <= 2; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos p = center.add(dx, 0, dz);
                    if (!CampTerrainValidator.isCellNatural(world, p)) continue;
                    if (!world.getBlockState(p).getBlock().isReplaceable(world, p)) continue;
                    if (!world.getBlockState(p.down()).getMaterial().isSolid()) continue;
                    return p;
                }
            }
        }
        return null;
    }

    private boolean isPlayerNearCamp(WorldServer world, CampsiteRecord rec) {
        int radius = reconcileRadius(world);
        long rSq = (long) radius * radius;
        for (EntityPlayer p : world.playerEntities) {
            double dx = p.posX - (rec.center.getX() + 0.5);
            double dz = p.posZ - (rec.center.getZ() + 0.5);
            if (dx * dx + dz * dz <= rSq) return true;
        }
        return false;
    }

    // --- Claim API (policy lives in VirtualTravellerManager / camp AI) ---

    /**
     * Nearest campsite within {@code seek} of (x,z) that {@code entityClass}/{@code cultureId} may claim, else null.
     * Uses the spatial grid so cost scales with local campsite density, not total campsite count — the
     * cheap distance check runs before the (relatively) expensive {@code canClaim}/{@code firstFreeSlot}
     * check, so a huge world with thousands of campsites doesn't pay for every one of them on every
     * claim attempt (this ordering used to be reversed and was the dominant server cost on such worlds).
     */
    @Nullable
    public CampsiteRecord findClaimable(int dim, double x, double z, String entityClass, @Nullable String cultureId, int seek) {
        double[] bestSq = {(double) seek * seek};
        CampsiteRecord[] best = {null};
        forEachNearby(dim, x, z, seek, rec -> {
            if (!rec.slotsComputed || rec.slots.isEmpty()) return;
            double dx = rec.center.getX() + 0.5 - x;
            double dz = rec.center.getZ() + 0.5 - z;
            double sq = dx * dx + dz * dz;
            if (sq > bestSq[0]) return;
            if (!rec.canClaim(entityClass, cultureId)) return;
            bestSq[0] = sq;
            best[0] = rec;
        });
        return best[0];
    }

    /** Assigns the first free slot to {@code uuid}. Returns the slot index, or -1 if it can't claim. */
    public int claim(CampsiteRecord rec, UUID uuid, String entityClass, String culture, long arrival, long depart) {
        if (!rec.canClaim(entityClass, culture)) return -1;
        int slot = rec.firstFreeSlot();
        if (slot < 0) return -1;
        rec.occupants.put(slot, new CampOccupant(uuid, entityClass, culture, slot, arrival, depart));
        indexOccupant(uuid, rec);
        markDirty();
        return slot;
    }

    /**
     * Frees the slot held by this traveller on DEATH. Its shelter is left as a lingering ruin
     * (orphan) — findable until the slot is reused or the camp is fully cleaned by a leaver.
     */
    public void releaseOccupant(UUID travellerUUID) {
        CampsiteRecord rec = occupantIndex.get(travellerUUID);
        if (rec == null) return;
        Integer slot = null;
        for (Map.Entry<Integer, CampOccupant> e : rec.occupants.entrySet()) {
            if (e.getValue().travellerUUID.equals(travellerUUID)) {
                slot = e.getKey();
                break;
            }
        }
        if (slot == null) return;
        CampOccupant occ = rec.occupants.remove(slot);
        deindexOccupant(travellerUUID);
        if (occ != null && occ.snapshot != null) {
            rec.orphans.put(slot, occ.snapshot); // ruin lingers, however far the build/teardown had gotten
        }
        markDirty();
    }

    @Nullable
    public CampsiteRecord getById(UUID id) {
        return byId.get(id);
    }

    /** The campsite currently holding an occupant with this UUID (for re-deriving camp state on virtualize). */
    @Nullable
    public CampsiteRecord campOf(UUID travellerUUID) {
        return occupantIndex.get(travellerUUID);
    }

    /** The occupant record itself (not just its camp), or {@code null} if this traveller isn't camping. */
    @Nullable
    public CampOccupant findOccupant(UUID travellerUUID) {
        CampsiteRecord rec = occupantIndex.get(travellerUUID);
        if (rec == null) return null;
        for (CampOccupant o : rec.occupants.values()) {
            if (o.travellerUUID.equals(travellerUUID)) return o;
        }
        return null;
    }

    /** Walkable stand position at a slot (slot center at entity-feet Y), or null if the slot is gone. */
    @Nullable
    public BlockPos slotStandPos(UUID campSiteId, int slotIndex) {
        CampsiteRecord rec = getById(campSiteId);
        if (rec == null || slotIndex < 0 || slotIndex >= rec.slots.size()) return null;
        return rec.slots.get(slotIndex).center().up();
    }

    /** Position 1 block outside the footprint entrance where the entity waits before the shelter is built. */
    @Nullable
    public BlockPos slotApproachPos(UUID campSiteId, int slotIndex) {
        CampsiteRecord rec = getById(campSiteId);
        if (rec == null || slotIndex < 0 || slotIndex >= rec.slots.size()) return null;
        return rec.slots.get(slotIndex).approachPos();
    }

    /** Scans the slot footprint for a bed block and returns its position, or null if no bed found. */
    @Nullable
    private static BlockPos findBedInSlot(World world, ShelterSlot slot) {
        for (int dx = 0; dx < ShelterSlot.FOOTPRINT; dx++) {
            for (int dz = 0; dz < ShelterSlot.FOOTPRINT; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos p = slot.origin.add(dx, dy, dz);
                    if (world.getBlockState(p).getBlock() instanceof net.minecraft.block.BlockBed) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    public static boolean isNight(World world) {
        long tod = world.getWorldTime() % 24000L;
        return tod >= 13000L && tod < 24000L;
    }

    /** Departure tick: next dawn if claimed at night, else a random daytime pause. */
    public static long computeDepartTick(World world, long now) {
        long tod = world.getWorldTime() % 24000L;
        if (isNight(world)) {
            long toMorning = (24000L - tod) % 24000L;
            if (toMorning == 0) toMorning = 24000L;
            return now + toMorning + 200L;
        }
        int min = TravellersConfig.CAMP_EVENTS.daytimePauseMinTicks;
        int max = TravellersConfig.CAMP_EVENTS.daytimePauseMaxTicks;
        return now + min + world.rand.nextInt(Math.max(1, max - min + 1));
    }

    private void tryDiscover(WorldServer world, int cx, int cz) {
        int radius = TravellersConfig.CAMP_EVENTS.campsiteRadius;
        // Candidate center: middle of the chunk (kept well inside so the flatness check stays in-chunk).
        int wx = (cx << 4) + 8;
        int wz = (cz << 4) + 8;
        BlockPos centerGround = groundColumn(world, wx, wz);
        if (centerGround == null) return;

        // Reject if too close to a village or another campsite.
        int villageSearchRadius = TravellersConfig.CAMP_EVENTS.campsiteMinVillageDistance;
        if (MillSettlements.isNear(world, centerGround, villageSearchRadius)) return;
        if (tooCloseToExisting(centerGround, world.provider.getDimension())) return;

        // Reject if the candidate footprint overlaps a Millénaire building's reserved zone — real
        // footprint metadata, not block identity, since a building can be made of plain vanilla
        // blocks. Cheap here: runs once per discovered chunk, not per tick.
        if (MillFootprints.overlapsReserved(world,
                centerGround.add(-radius, 0, -radius), centerGround.add(radius, 0, radius))) {
            return;
        }

        // Center must itself be flat enough to seat a firepit (1-block area, in-chunk).
        if (!isFlatArea(world, centerGround, 1)) return;

        // Never seat a camp center on/inside someone's build or any other artificial structure.
        if (!CampTerrainValidator.isVolumeNatural(world,
                centerGround.add(-1, -1, -1), centerGround.add(1, 3, 1))) return;

        // Record now; slots are computed lazily once the footprint chunks are all loaded
        // (the radius spans neighbour chunks that may not be generated at discovery time).
        CampsiteRecord rec = new CampsiteRecord(UUID.randomUUID(), centerGround,
                world.provider.getDimension(), radius);
        rec.firepitPos = centerGround;
        records.add(rec);
        indexRecord(rec);
        markDirty();
        discoveryCreated++;
    }

    /** Computes slots for not-yet-computed records whose footprint chunks are loaded; drops empty ones. */
    private void lazyComputePass(WorldServer world, int budget) {
        int dim = world.provider.getDimension();
        int done = 0;
        for (java.util.Iterator<CampsiteRecord> it = records.iterator(); it.hasNext() && done < budget; ) {
            CampsiteRecord rec = it.next();
            if (rec.slotsComputed || rec.dimension != dim) continue;
            if (!footprintChunksLoaded(world, rec)) continue;
            computeSlots(world, rec);
            done++;
            if (rec.slots.size() < TravellersConfig.CAMP_EVENTS.minShelterSlots) {
                deindexRecord(rec);
                it.remove(); // not enough room here after all
                discoveryDroppedAtLayout++;
                markDirty();
            }
        }
    }

    private boolean footprintChunksLoaded(WorldServer world, CampsiteRecord rec) {
        for (net.minecraft.util.math.ChunkPos cp : footprintChunks(rec)) {
            if (!world.getChunkProvider().chunkExists(cp.x, cp.z)) return false;
        }
        return true;
    }

    /** All chunk coordinates covering a campsite's footprint (radius + shelter margin). */
    public static List<net.minecraft.util.math.ChunkPos> footprintChunks(CampsiteRecord rec) {
        int margin = rec.radius + ShelterSlot.FOOTPRINT;
        int minCx = (rec.center.getX() - margin) >> 4;
        int maxCx = (rec.center.getX() + margin) >> 4;
        int minCz = (rec.center.getZ() - margin) >> 4;
        int maxCz = (rec.center.getZ() + margin) >> 4;
        List<net.minecraft.util.math.ChunkPos> list = new ArrayList<>();
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                list.add(new net.minecraft.util.math.ChunkPos(cx, cz));
            }
        }
        return list;
    }

    private boolean tooCloseToExisting(BlockPos center, int dim) {
        int minSpacing = TravellersConfig.CAMP_EVENTS.campsiteMinSpacing;
        long minSq = (long) minSpacing * minSpacing;
        boolean[] hit = {false};
        forEachNearby(dim, center.getX(), center.getZ(), minSpacing, r -> {
            if (hit[0]) return;
            long dx = r.center.getX() - center.getX();
            long dz = r.center.getZ() - center.getZ();
            if (dx * dx + dz * dz < minSq) hit[0] = true;
        });
        return hit[0];
    }

    /**
     * (Re)computes valid 5×5 shelter slots from currently-loaded terrain. Idempotent.
     * Slots face the firepit, keep at least 1 block from it, stay within radius, respect the
     * configured gap, and require flat solid ground with a clear entrance.
     */
    public void computeSlots(WorldServer world, CampsiteRecord rec) {
        rec.slots.clear();
        int radius = rec.radius;
        int gap = TravellersConfig.CAMP_EVENTS.shelterGap;
        int step = ShelterSlot.FOOTPRINT + gap;
        int maxSlots = TravellersConfig.CAMP_EVENTS.maxShelterSlots;
        BlockPos fire = rec.firepitPos;

        // One village/lone-building zone scan for the whole record, reused by every candidate slot
        // below — the only part of this whose cost scales with total village count, so it must run
        // once per computeSlots call, not once per candidate grid cell.
        List<MillFootprints.Footprint> zones = MillFootprints.reservedIn(world,
                fire.add(-radius, 0, -radius), fire.add(radius, 0, radius));

        List<ShelterSlot> accepted = new ArrayList<>();
        // Walk a grid of footprint origins around the center.
        for (int ox = fire.getX() - radius; ox <= fire.getX() + radius - ShelterSlot.FOOTPRINT && accepted.size() < maxSlots; ox += step) {
            for (int oz = fire.getZ() - radius; oz <= fire.getZ() + radius - ShelterSlot.FOOTPRINT && accepted.size() < maxSlots; oz += step) {
                ShelterSlot slot = tryBuildSlot(world, rec, ox, oz, accepted, zones);
                if (slot != null) accepted.add(slot);
            }
        }
        rec.slots.addAll(accepted);
        rec.slotsComputed = true;
        markDirty();
    }

    @Nullable
    private ShelterSlot tryBuildSlot(WorldServer world, CampsiteRecord rec, int ox, int oz, List<ShelterSlot> accepted,
                                      List<MillFootprints.Footprint> villageZones) {
        BlockPos fire = rec.firepitPos;
        int half = ShelterSlot.FOOTPRINT / 2;
        int centerX = ox + half;
        int centerZ = oz + half;

        // Footprint center within radius of the firepit.
        double distFire = Math.hypot(centerX - fire.getX(), centerZ - fire.getZ());
        if (distFire > rec.radius) return null;
        // At least 1 empty block between the firepit and the nearest footprint cell.
        int nearestX = Math.max(ox, Math.min(fire.getX(), ox + ShelterSlot.FOOTPRINT - 1));
        int nearestZ = Math.max(oz, Math.min(fire.getZ(), oz + ShelterSlot.FOOTPRINT - 1));
        int firepitGap = Math.max(Math.abs(fire.getX() - nearestX), Math.abs(fire.getZ() - nearestZ));
        if (firepitGap < 2) return null;

        // Strictly flat solid ground over the whole 5×5 footprint. The returned Y is the ground
        // surface block, so a placed shelter's bottom layer REPLACES the surface (floor/bed sit at
        // walkable level) instead of floating on top of it.
        Integer surfaceY = flatFootprintSurface(world, ox, oz);
        if (surfaceY == null) return null;

        // Reject footprints holding anything artificial (player builds, mod structures, Millénaire
        // blocks) — camps only ever occupy natural terrain. Re-checked again right before the first
        // grading touch (CampBuildCoordinator.ensureTerrainPrepared) since slots can sit unused for
        // a long time.
        if (!CampTerrainValidator.isVolumeNatural(world,
                new BlockPos(ox, surfaceY - 1, oz),
                new BlockPos(ox + ShelterSlot.FOOTPRINT - 1, surfaceY + 5, oz + ShelterSlot.FOOTPRINT - 1))) {
            return null;
        }

        // Reject footprints overlapping a Millénaire building's reserved zone (real footprint
        // metadata, catches buildings made of plain vanilla blocks that the check above would miss).
        if (MillFootprints.anyReserved(villageZones,
                new BlockPos(ox, 0, oz), new BlockPos(ox + ShelterSlot.FOOTPRINT - 1, 0, oz + ShelterSlot.FOOTPRINT - 1))) {
            return null;
        }

        // Gap from already-accepted slots.
        for (ShelterSlot s : accepted) {
            int dx = Math.abs(centerX - s.center().getX());
            int dz = Math.abs(centerZ - s.center().getZ());
            if (dx < ShelterSlot.FOOTPRINT + TravellersConfig.CAMP_EVENTS.shelterGap
                    && dz < ShelterSlot.FOOTPRINT + TravellersConfig.CAMP_EVENTS.shelterGap) return null;
        }

        // Prefer the facing toward the firepit, but fall back to another side of the footprint if that
        // one would open onto a cliff/ledge — better than a shelter nobody built by this system can walk
        // out of. Rejects the slot entirely only if none of the four sides work.
        EnumFacing preferred = facingToward(centerX, centerZ, fire);
        EnumFacing entrance = null;
        for (EnumFacing candidate : entranceCandidates(preferred)) {
            if (isEntranceViable(world, centerX, centerZ, surfaceY, candidate, half)) {
                entrance = candidate;
                break;
            }
        }
        if (entrance == null) return null;

        return new ShelterSlot(new BlockPos(ox, surfaceY, oz), entrance);
    }

    private static final EnumFacing[] HORIZONTAL_FACINGS =
            {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};

    /** {@code preferred} first, then the remaining three horizontal facings. */
    private static EnumFacing[] entranceCandidates(EnumFacing preferred) {
        EnumFacing[] out = new EnumFacing[4];
        out[0] = preferred;
        int i = 1;
        for (EnumFacing f : HORIZONTAL_FACINGS) {
            if (f != preferred) out[i++] = f;
        }
        return out;
    }

    /**
     * True if the entrance column just outside the footprint is clear at walkable height AND the
     * ground stays within {@code entranceMaxStepDiff} of the floor for
     * {@code entranceCheckDistance} blocks outward — rejects a facing that would open onto a cliff,
     * ravine or steep ledge.
     */
    private boolean isEntranceViable(WorldServer world, int centerX, int centerZ, int surfaceY,
                                      EnumFacing facing, int half) {
        BlockPos entrancePos = new BlockPos(centerX, surfaceY + 1, centerZ).offset(facing, half + 1);
        if (!world.isAirBlock(entrancePos) || !world.isAirBlock(entrancePos.up())) return false;

        int checkDist = TravellersConfig.CAMP_EVENTS.entranceCheckDistance;
        int maxStep = TravellersConfig.CAMP_EVENTS.entranceMaxStepDiff;
        for (int i = 1; i <= checkDist; i++) {
            BlockPos step = entrancePos.offset(facing, i - 1);
            BlockPos ground = groundColumn(world, step.getX(), step.getZ());
            if (ground == null) return false; // liquid, hazard, or no valid ground here
            if (Math.abs((ground.getY() - 1) - surfaceY) > maxStep) return false;
        }
        return true;
    }

    /**
     * Returns a target surface Y for the footprint if its ground varies by no more than the configured
     * tolerance (the footprint is graded flat to this Y when a shelter is built), else null. The target
     * is the median surface so grading does the least fill/cut.
     */
    @Nullable
    private Integer flatFootprintSurface(WorldServer world, int ox, int oz) {
        int tol = TravellersConfig.CAMP_EVENTS.flatTolerance;
        int[] surfaces = new int[ShelterSlot.FOOTPRINT * ShelterSlot.FOOTPRINT];
        int n = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int x = ox; x < ox + ShelterSlot.FOOTPRINT; x++) {
            for (int z = oz; z < oz + ShelterSlot.FOOTPRINT; z++) {
                BlockPos air = groundColumn(world, x, z);
                if (air == null) return null;
                int s = air.getY() - 1; // surface block
                surfaces[n++] = s;
                min = Math.min(min, s);
                max = Math.max(max, s);
            }
        }
        if (max - min > tol) return null;
        java.util.Arrays.sort(surfaces);
        return surfaces[surfaces.length / 2]; // median target floor
    }

    /** True if the area around {@code center} (extent each side) is flat ground within tolerance. */
    private boolean isFlatArea(WorldServer world, BlockPos center, int extent) {
        int tol = TravellersConfig.CAMP_EVENTS.flatTolerance;
        int baseY = center.getY();
        for (int x = -extent; x <= extent; x++) {
            for (int z = -extent; z <= extent; z++) {
                BlockPos air = groundColumn(world, center.getX() + x, center.getZ() + z);
                if (air == null) return false;
                if (Math.abs(air.getY() - baseY) > tol) return false;
            }
        }
        return true;
    }

    @Nullable
    private BlockPos groundColumn(WorldServer world, int x, int z) {
        int feetY = ColumnGround.naturalTop(world, x, z);
        return feetY == ColumnGround.NONE ? null : new BlockPos(x, feetY, z);
    }

    private static EnumFacing facingToward(int fromX, int fromZ, BlockPos to) {
        int dx = to.getX() - fromX;
        int dz = to.getZ() - fromZ;
        if (Math.abs(dx) >= Math.abs(dz)) return dx >= 0 ? EnumFacing.EAST : EnumFacing.WEST;
        return dz >= 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
    }

    /** Debug: force a campsite at the given column (ignores discovery chance / village distance). */
    @Nullable
    public CampsiteRecord forceCreateAt(WorldServer world, BlockPos pos) {
        BlockPos ground = groundColumn(world, pos.getX(), pos.getZ());
        if (ground == null) return null;
        if (tooCloseToExisting(ground, world.provider.getDimension())) return null;
        CampsiteRecord rec = new CampsiteRecord(UUID.randomUUID(), ground,
                world.provider.getDimension(), TravellersConfig.CAMP_EVENTS.campsiteRadius);
        rec.firepitPos = ground;
        if (footprintChunksLoaded(world, rec)) computeSlots(world, rec);
        records.add(rec);
        indexRecord(rec);
        markDirty();
        return rec;
    }

    /** Debug: recompute slots for every campsite within range whose footprint chunks are loaded. */
    public int recomputeNear(WorldServer world, BlockPos pos, int dist) {
        int dim = world.provider.getDimension();
        long maxSq = (long) dist * dist;
        int n = 0;
        for (CampsiteRecord r : records) {
            if (r.dimension != dim || r.center.distanceSq(pos) > maxSq) continue;
            if (!footprintChunksLoaded(world, r)) continue;
            // Recomputing would clear and rebuild rec.slots from current terrain, but rec.occupants is
            // keyed by index into that list — an occupant's slotIndex would silently start pointing at
            // an unrelated slot (different origin/entrance) with none of its build progress updated.
            // Skip camps with anyone currently occupying a slot; safe once they're all empty again.
            if (!r.occupants.isEmpty()) continue;
            computeSlots(world, r);
            n++;
        }
        return n;
    }

    /**
     * Nudges {@code pos} outside any occupied shelter footprint (a slot with at least one structure
     * block placed, i.e. mid-build or built) so a materializing entity that doesn't belong to that
     * camp never appears inside/under a shelter and suffocates. Pure in-memory lookup against the
     * persisted slot rectangles — no chunk loading, no world reads. {@code ownCampId} (the entity's
     * own claimed camp, if any) is skipped so a camping entity is never pushed off its own slot.
     * Bounded to a few iterations in case the nudged point lands inside a neighbouring slot.
     */
    public BlockPos resolveOutsideOccupiedSlots(int dim, BlockPos pos, @Nullable UUID ownCampId) {
        BlockPos candidate = pos;
        for (int i = 0; i < 4; i++) {
            ShelterSlot hit = findOccupiedSlotAt(dim, candidate, ownCampId);
            if (hit == null) return candidate;
            candidate = pushOutsideSlotFootprint(hit, candidate);
        }
        return candidate;
    }

    // Campsite radius is small (single digits) plus shelter footprint margin — a generous fixed search
    // radius comfortably covers it while still keeping the grid query to a handful of cells.
    private static final int OCCUPIED_SLOT_SEARCH_RADIUS = 64;

    @Nullable
    private ShelterSlot findOccupiedSlotAt(int dim, BlockPos pos, @Nullable UUID ownCampId) {
        ShelterSlot[] hit = {null};
        forEachNearby(dim, pos.getX(), pos.getZ(), OCCUPIED_SLOT_SEARCH_RADIUS, rec -> {
            if (hit[0] != null) return;
            if (rec.id.equals(ownCampId)) return;
            long dx = rec.center.getX() - pos.getX();
            long dz = rec.center.getZ() - pos.getZ();
            long maxReach = rec.radius + ShelterSlot.FOOTPRINT + 4;
            if (dx * dx + dz * dz > maxReach * maxReach) return;

            for (Map.Entry<Integer, CampOccupant> e : rec.occupants.entrySet()) {
                if (!e.getValue().hasAnyStructure()) continue;
                ShelterSlot slot = rec.slots.get(e.getKey());
                if (containsFootprintPlanar(slot, pos)) {
                    hit[0] = slot;
                    return;
                }
            }
        });
        return hit[0];
    }

    /** Raw footprint containment (no margin) — the margin is only applied when pushing back out. */
    private static boolean containsFootprintPlanar(ShelterSlot slot, BlockPos pos) {
        int minX = slot.origin.getX();
        int maxX = slot.origin.getX() + ShelterSlot.FOOTPRINT - 1;
        int minZ = slot.origin.getZ();
        int maxZ = slot.origin.getZ() + ShelterSlot.FOOTPRINT - 1;
        return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /**
     * Nudges (x,z) to just outside the given slot's footprint, toward the nearest edge.
     * The clearance is always at least 1 block (regardless of config) so the pushed point can never
     * land back inside the raw footprint that {@link #containsFootprintPlanar} tests against.
     */
    private static BlockPos pushOutsideSlotFootprint(ShelterSlot slot, BlockPos pos) {
        int margin = Math.max(1, TravellersConfig.MATERIALIZATION.campFootprintMargin);
        int minX = slot.origin.getX() - margin;
        int maxX = slot.origin.getX() + ShelterSlot.FOOTPRINT - 1 + margin;
        int minZ = slot.origin.getZ() - margin;
        int maxZ = slot.origin.getZ() + ShelterSlot.FOOTPRINT - 1 + margin;
        int x = pos.getX();
        int z = pos.getZ();

        int distMinX = x - minX;
        int distMaxX = maxX - x;
        int distMinZ = z - minZ;
        int distMaxZ = maxZ - z;
        int min = Math.min(Math.min(distMinX, distMaxX), Math.min(distMinZ, distMaxZ));

        if (min == distMinX) x = minX;
        else if (min == distMaxX) x = maxX;
        else if (min == distMinZ) z = minZ;
        else z = maxZ;

        return new BlockPos(x, pos.getY(), z);
    }

    @Nullable
    public CampsiteRecord nearest(int dim, BlockPos pos, int maxDist) {
        long bestSq = (long) maxDist * maxDist;
        CampsiteRecord best = null;
        for (CampsiteRecord r : records) {
            if (r.dimension != dim) continue;
            long dx = r.center.getX() - pos.getX();
            long dz = r.center.getZ() - pos.getZ();
            long sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = r;
            }
        }
        return best;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        records.clear();
        byId.clear();
        occupantIndex.clear();
        spatialGrid.clear();
        NBTTagList list = nbt.getTagList("Records", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            CampsiteRecord rec = CampsiteRecord.fromNbt(list.getCompoundTagAt(i));
            records.add(rec);
            indexRecord(rec);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (CampsiteRecord r : records) list.appendTag(r.toNbt());
        nbt.setTag("Records", list);
        return nbt;
    }
}
