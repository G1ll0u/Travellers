package com.jubitus.travellers.utils.world.mill;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import org.millenaire.common.village.Building;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Tracks barracks slot assignments and pending worker respawns.
 * One instance per world dimension (per-world storage).
 */
public class BarracksSlotManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersBarracksSlots";

    // "dim:x,y,z" -> BarracksRecord
    private final Map<String, BarracksRecord> barracks = new HashMap<>();
    // worker UUID -> barracks key (reverse index for quick lookup)
    private final Map<UUID, String> workerToBarracks = new HashMap<>();
    // worker UUID -> pending respawn data
    private final Map<UUID, PendingRespawn> pendingRespawns = new HashMap<>();

    public BarracksSlotManager() {
        super(DATA_NAME);
    }

    public BarracksSlotManager(String name) {
        super(name);
    }

    public static BarracksSlotManager get(World world) {
        if (!(world instanceof WorldServer)) {
            throw new IllegalStateException("BarracksSlotManager can only be used server-side");
        }
        WorldServer ws = (WorldServer) world;
        MapStorage storage = ws.getPerWorldStorage();
        BarracksSlotManager mgr = (BarracksSlotManager) storage.getOrLoadData(BarracksSlotManager.class, DATA_NAME);
        if (mgr == null) {
            mgr = new BarracksSlotManager();
            storage.setData(DATA_NAME, mgr);
        }
        return mgr;
    }

    // -------------------------------------------------------------------------
    // Slot management
    // -------------------------------------------------------------------------

    /** How many free slots remain in the given barracks. Unregistered barracks are treated as fully empty. */
    public int getFreeSlots(UUID owner, BlockPos pos, int dim) {
        String key = key(dim, pos);
        BarracksRecord rec = barracks.get(key);
        int max = Math.max(1, TravellersModConfig.barracksSlotsPerBuilding);
        if (rec == null || !owner.equals(rec.owner)) return max;
        return Math.max(0, max - rec.assignedWorkers.size());
    }

    /** True if this worker is already assigned to the given barracks position. */
    public boolean isAssignedTo(UUID workerUUID, BlockPos pos) {
        String key = workerToBarracks.get(workerUUID);
        if (key == null) return false;
        BarracksRecord rec = barracks.get(key);
        return rec != null && rec.pos.equals(pos);
    }

    /** True if the worker has any barracks assignment tracked here. */
    public boolean isWorkerRegistered(UUID workerUUID) {
        return workerToBarracks.containsKey(workerUUID);
    }

    /** Assign a worker to a barracks slot. Returns false if the barracks is full. */
    public boolean assignWorker(UUID owner, BlockPos pos, int dim, String name, UUID workerUUID) {
        String key = key(dim, pos);
        BarracksRecord rec = barracks.computeIfAbsent(key, k -> {
            BarracksRecord r = new BarracksRecord();
            r.owner = owner;
            r.pos = pos;
            r.dimension = dim;
            r.name = name;
            return r;
        });

        // Update name in case it changed
        rec.name = name;

        // Check if already assigned here (idempotent)
        if (rec.assignedWorkers.contains(workerUUID)) return true;

        // Check slot capacity
        int max = Math.max(1, TravellersModConfig.barracksSlotsPerBuilding);
        if (rec.assignedWorkers.size() >= max) return false;

        // Remove from previous barracks if any
        String oldKey = workerToBarracks.get(workerUUID);
        if (oldKey != null && !oldKey.equals(key)) {
            BarracksRecord old = barracks.get(oldKey);
            if (old != null) old.assignedWorkers.remove(workerUUID);
        }

        rec.assignedWorkers.add(workerUUID);
        workerToBarracks.put(workerUUID, key);
        markDirty();
        return true;
    }

    /** Free a worker's slot (on dismiss, death without respawn, or manual unassign). */
    public void unassignWorker(UUID workerUUID) {
        String key = workerToBarracks.remove(workerUUID);
        if (key != null) {
            BarracksRecord rec = barracks.get(key);
            if (rec != null) rec.assignedWorkers.remove(workerUUID);
        }
        pendingRespawns.remove(workerUUID);
        markDirty();
    }

    /** Get the barracks position assigned to this worker, or null. */
    @Nullable
    public BlockPos getAssignedBarracks(UUID workerUUID) {
        String key = workerToBarracks.get(workerUUID);
        if (key == null) return null;
        BarracksRecord rec = barracks.get(key);
        return rec != null ? rec.pos : null;
    }

    // -------------------------------------------------------------------------
    // Respawn scheduling
    // -------------------------------------------------------------------------

    /**
     * Schedule a hired worker to respawn next morning at their assigned barracks.
     * The slot remains occupied until respawn.
     */
    public void scheduleRespawn(UUID ownerUUID, UUID workerUUID, BlockPos barrackPos, int dim, NBTTagCompound workerNbt) {
        PendingRespawn pr = new PendingRespawn();
        pr.workerUUID = workerUUID;
        pr.ownerUUID = ownerUUID;
        pr.barrackPos = barrackPos;
        pr.dimension = dim;
        pr.workerNbt = workerNbt.copy();
        pendingRespawns.put(workerUUID, pr);

        // Ensure the slot stays registered
        String key = key(dim, barrackPos);
        BarracksRecord rec = barracks.get(key);
        if (rec != null && !rec.assignedWorkers.contains(workerUUID)) {
            rec.assignedWorkers.add(workerUUID);
            workerToBarracks.put(workerUUID, key);
        }
        markDirty();
    }

    /**
     * Process all pending respawns for the given world's dimension.
     * Called once per morning from TravellerChunkManager.
     */
    public void processRespawns(WorldServer world) {
        if (!TravellersModConfig.workerRespawnInBarracks) return;

        int dim = world.provider.getDimension();
        List<UUID> toProcess = new ArrayList<>();
        for (PendingRespawn pr : pendingRespawns.values()) {
            if (pr.dimension == dim) toProcess.add(pr.workerUUID);
        }

        for (UUID wid : toProcess) {
            PendingRespawn pr = pendingRespawns.get(wid);
            if (pr == null) continue;

            // Verify the barracks still exists
            if (!MillControlledVillageHelper.barracksBuildingExistsAnywhere(world, pr.barrackPos)) {
                // Barracks was deleted: worker remains assigned (homeless) but no respawn
                pendingRespawns.remove(wid);
                String key = workerToBarracks.remove(wid);
                if (key != null) {
                    BarracksRecord rec = barracks.get(key);
                    if (rec != null) rec.assignedWorkers.remove(wid);
                }
                markDirty();
                continue;
            }

            // Find safe spawn position near barracks center
            BlockPos spawnPos = findSafeSpawnPos(world, pr.barrackPos, 8);

            // Only spawn if chunk is loaded
            if (!world.isBlockLoaded(spawnPos)) continue;

            // Respawn the worker
            spawnWorker(world, pr, spawnPos);

            pendingRespawns.remove(wid);
            markDirty();
        }
    }

    private void spawnWorker(WorldServer world, PendingRespawn pr, BlockPos spawnPos) {
        try {
            EntityTravellerWorker worker = new EntityTravellerWorker(world);
            // NBT was stripped of inventory data before being stored — worker spawns empty
            worker.readFromNBT(pr.workerNbt);
            // Restore full health
            worker.setHealth(worker.getMaxHealth());
            // Place at safe spawn position
            worker.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            worker.setFollowOrderActive(false);
            world.spawnEntity(worker);
        } catch (Exception e) {
            System.err.println("[Travellers] BarracksSlotManager: failed to respawn worker " + pr.workerUUID + ": " + e.getMessage());
        }
    }

    private static BlockPos findSafeSpawnPos(WorldServer world, BlockPos center, int radius) {
        // Try center first
        BlockPos top = world.getTopSolidOrLiquidBlock(center);
        if (isSafeSpawn(world, top)) return top.up();

        // Try random offsets
        Random rand = world.rand;
        for (int i = 0; i < 12; i++) {
            int ox = rand.nextInt(radius * 2 + 1) - radius;
            int oz = rand.nextInt(radius * 2 + 1) - radius;
            top = world.getTopSolidOrLiquidBlock(new BlockPos(center.getX() + ox, center.getY(), center.getZ() + oz));
            if (isSafeSpawn(world, top)) return top.up();
        }

        // Fallback: center + 1 (might be inside a block but is better than nothing)
        return center.up();
    }

    private static boolean isSafeSpawn(WorldServer world, BlockPos ground) {
        return world.getBlockState(ground).isSideSolid(world, ground, EnumFacing.UP)
                && world.isAirBlock(ground.up())
                && world.isAirBlock(ground.up(2));
    }

    // -------------------------------------------------------------------------
    // Nearest barracks finder
    // -------------------------------------------------------------------------

    /**
     * Find the nearest barracks with at least one free slot for the given owner.
     * Only considers barracks present in the provided live list (from Millénaire).
     */
    @Nullable
    public BlockPos findNearestFreeBarracks(UUID ownerUUID, int dim, double x, double z, List<Building> liveBarracks) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Building b : liveBarracks) {
            BlockPos pos = MillControlledVillageHelper.toBlockPos(b.getPos());
            if (getFreeSlots(ownerUUID, pos, dim) <= 0) continue;
            double dx = pos.getX() - x;
            double dz = pos.getZ() - z;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = pos;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // Reconciliation (call on morning tick for online players)
    // -------------------------------------------------------------------------

    /**
     * Remove records for barracks that no longer exist in Millénaire.
     * Workers that were assigned to deleted barracks lose their assignment.
     */
    public void reconcileForPlayer(UUID ownerUUID, int dim, List<Building> existingBarracks) {
        Set<BlockPos> validPos = new HashSet<>();
        for (Building b : existingBarracks) {
            validPos.add(MillControlledVillageHelper.toBlockPos(b.getPos()));
        }

        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, BarracksRecord> e : barracks.entrySet()) {
            BarracksRecord rec = e.getValue();
            if (!ownerUUID.equals(rec.owner) || rec.dimension != dim) continue;
            if (!validPos.contains(rec.pos)) toRemove.add(e.getKey());
        }

        if (toRemove.isEmpty()) return;

        for (String k : toRemove) {
            BarracksRecord rec = barracks.remove(k);
            if (rec == null) continue;
            for (UUID wu : rec.assignedWorkers) {
                workerToBarracks.remove(wu);
                pendingRespawns.remove(wu);
            }
        }
        markDirty();
    }

    // -------------------------------------------------------------------------
    // NBT serialization
    // -------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        barracks.clear();
        workerToBarracks.clear();
        pendingRespawns.clear();

        NBTTagList barracksList = nbt.getTagList("Barracks", 10);
        for (int i = 0; i < barracksList.tagCount(); i++) {
            NBTTagCompound bt = barracksList.getCompoundTagAt(i);
            BarracksRecord rec = new BarracksRecord();
            rec.owner = bt.getUniqueId("Owner");
            rec.pos = new BlockPos(bt.getInteger("BX"), bt.getInteger("BY"), bt.getInteger("BZ"));
            rec.dimension = bt.getInteger("BDim");
            rec.name = bt.getString("BName");

            NBTTagList workers = bt.getTagList("Workers", 10);
            for (int j = 0; j < workers.tagCount(); j++) {
                NBTTagCompound wt = workers.getCompoundTagAt(j);
                UUID wu = wt.getUniqueId("WID");
                rec.assignedWorkers.add(wu);
                String k = key(rec.dimension, rec.pos);
                workerToBarracks.put(wu, k);
            }

            barracks.put(key(rec.dimension, rec.pos), rec);
        }

        NBTTagList respawnList = nbt.getTagList("PendingRespawns", 10);
        for (int i = 0; i < respawnList.tagCount(); i++) {
            NBTTagCompound rt = respawnList.getCompoundTagAt(i);
            PendingRespawn pr = new PendingRespawn();
            pr.workerUUID = rt.getUniqueId("WID");
            pr.ownerUUID = rt.getUniqueId("OID");
            pr.barrackPos = new BlockPos(rt.getInteger("BX"), rt.getInteger("BY"), rt.getInteger("BZ"));
            pr.dimension = rt.getInteger("BDim");
            pr.workerNbt = rt.getCompoundTag("Nbt");
            pendingRespawns.put(pr.workerUUID, pr);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList barracksList = new NBTTagList();
        for (BarracksRecord rec : barracks.values()) {
            NBTTagCompound bt = new NBTTagCompound();
            bt.setUniqueId("Owner", rec.owner);
            bt.setInteger("BX", rec.pos.getX());
            bt.setInteger("BY", rec.pos.getY());
            bt.setInteger("BZ", rec.pos.getZ());
            bt.setInteger("BDim", rec.dimension);
            bt.setString("BName", rec.name == null ? "" : rec.name);

            NBTTagList workers = new NBTTagList();
            for (UUID wu : rec.assignedWorkers) {
                NBTTagCompound wt = new NBTTagCompound();
                wt.setUniqueId("WID", wu);
                workers.appendTag(wt);
            }
            bt.setTag("Workers", workers);
            barracksList.appendTag(bt);
        }
        nbt.setTag("Barracks", barracksList);

        NBTTagList respawnList = new NBTTagList();
        for (PendingRespawn pr : pendingRespawns.values()) {
            NBTTagCompound rt = new NBTTagCompound();
            rt.setUniqueId("WID", pr.workerUUID);
            rt.setUniqueId("OID", pr.ownerUUID);
            rt.setInteger("BX", pr.barrackPos.getX());
            rt.setInteger("BY", pr.barrackPos.getY());
            rt.setInteger("BZ", pr.barrackPos.getZ());
            rt.setInteger("BDim", pr.dimension);
            rt.setTag("Nbt", pr.workerNbt);
            respawnList.appendTag(rt);
        }
        nbt.setTag("PendingRespawns", respawnList);

        return nbt;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String key(int dim, BlockPos pos) {
        return dim + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    public static class BarracksRecord {
        public UUID owner;
        public BlockPos pos;
        public int dimension;
        public String name = "";
        public final Set<UUID> assignedWorkers = new LinkedHashSet<>();
    }

    public static class PendingRespawn {
        public UUID workerUUID;
        public UUID ownerUUID;
        public BlockPos barrackPos;
        public int dimension;
        public NBTTagCompound workerNbt;
    }
}
