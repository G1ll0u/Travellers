package com.jubitus.travellers.utils.world.lonebar;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgSyncWorkerHome;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LoneBarrackManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersLoneBarracks";

    private final List<LoneBarrackRecord> records = new ArrayList<>();
    // worker UUID -> pending bed respawn
    private final Map<UUID, PendingRespawn> pendingRespawns = new HashMap<>();

    public LoneBarrackManager() {
        super(DATA_NAME);
    }

    public LoneBarrackManager(String name) {
        super(name);
    }

    public static LoneBarrackManager get(World world) {
        WorldServer ws = (WorldServer) world;
        MapStorage storage = ws.getPerWorldStorage();
        LoneBarrackManager data = (LoneBarrackManager) storage.getOrLoadData(LoneBarrackManager.class, DATA_NAME);
        if (data == null) {
            data = new LoneBarrackManager();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    public List<LoneBarrackRecord> getAll() {
        return Collections.unmodifiableList(records);
    }

    public List<LoneBarrackRecord> getByOwner(UUID ownerUUID) {
        List<LoneBarrackRecord> out = new ArrayList<>();
        for (LoneBarrackRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID)) out.add(r);
        }
        return out;
    }

    public boolean removeByOwnerAndCenter(UUID ownerUUID, BlockPos center) {
        boolean removed = records.removeIf(r -> ownerUUID.equals(r.ownerUUID) && center.equals(r.center));
        if (removed) markDirty();
        return removed;
    }

    public void add(LoneBarrackRecord record) {
        records.add(record);
        markDirty();
    }

    /** Refreshes coffer positions for the record matching owner+center and marks dirty. */
    public void refreshCoffers(World world, UUID ownerUUID, BlockPos center) {
        for (LoneBarrackRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID) && center.equals(r.center)) {
                r.cofferPositions.clear();
                r.cofferPositions.addAll(findCoffers(world, r.center, r.halfWidth, r.halfDepth, r.halfHeight));
                markDirty();
                return;
            }
        }
    }

    @Nullable
    public LoneBarrackRecord getByOwnerAndCenter(UUID ownerUUID, BlockPos center) {
        for (LoneBarrackRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID) && center.equals(r.center)) return r;
        }
        return null;
    }

    /** Rescans beds for the matching record and prunes bed assignments whose bed was removed. No-op if unloaded. */
    public void refreshBeds(World world, UUID ownerUUID, BlockPos center) {
        if (!world.isBlockLoaded(center)) return; // never force-load chunks
        LoneBarrackRecord r = getByOwnerAndCenter(ownerUUID, center);
        if (r == null) return;
        List<BlockPos> fresh = findBeds(world, r.center, r.halfWidth, r.halfDepth, r.halfHeight);
        Set<BlockPos> valid = new HashSet<>(fresh);
        List<BlockPos> lost = new ArrayList<>();
        for (BlockPos bed : r.bedPositions) {
            if (!valid.contains(bed)) lost.add(bed);
        }
        r.bedPositions.clear();
        r.bedPositions.addAll(fresh);
        WorldServer ws = (world instanceof WorldServer) ? (WorldServer) world : null;
        for (BlockPos bed : lost) {
            pruneBedAssignment(ws, r, bed);
        }
        markDirty();
    }

    /**
     * Called from {@link LoneBarrackBedGuards} the instant a tracked bed is broken, so the owner is
     * notified immediately instead of only on the next barracks-GUI open (which drives {@link #refreshBeds}).
     */
    public void onBedBroken(WorldServer world, BlockPos headPos) {
        for (LoneBarrackRecord r : records) {
            if (!r.bedPositions.remove(headPos)) continue;
            pruneBedAssignment(world, r, headPos);
            markDirty();
            return; // a bed belongs to at most one barrack
        }
    }

    /**
     * Removes a worker's reservation of a now-missing bed, fully clears its home assignment (so it
     * shows up as unassigned/homeless rather than still counting against the barrack's capacity),
     * and notifies the barrack owner. Does not touch bedPositions.
     */
    private void pruneBedAssignment(@Nullable WorldServer world, LoneBarrackRecord r, BlockPos bed) {
        UUID workerUUID = null;
        for (Map.Entry<UUID, BlockPos> e : r.bedByWorker.entrySet()) {
            if (bed.equals(e.getValue())) {
                workerUUID = e.getKey();
                break;
            }
        }
        if (workerUUID == null) return;
        r.bedByWorker.remove(workerUUID);
        if (world == null || world.getMinecraftServer() == null) return;
        clearWorkerHomeAssignment(world, workerUUID, r.center);
        String workerName = resolveWorkerName(world, workerUUID);
        String barrackName = (r.name == null || r.name.isEmpty()) ? "Barrack" : r.name;
        NotificationService.notifyOffline(world.getMinecraftServer(), r.ownerUUID,
                NotificationDefs.HOME_LONE_BARRACK_BED_DESTROYED, workerName, barrackName);
    }

    /**
     * Clears a worker's LONE_BARRACK home assignment if it still points at {@code barrackCenter}
     * (live entity, virtualized record, or a still-pending death respawn), so the worker becomes
     * homeless instead of silently counting toward a barrack it no longer has a bed in.
     */
    private void clearWorkerHomeAssignment(WorldServer world, UUID workerUUID, BlockPos barrackCenter) {
        Entity e = world.getEntityFromUuid(workerUUID);
        if (e instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) e;
            if (w.getAssignedHomeType() == TravellerHomeManager.HomeType.LONE_BARRACK
                    && barrackCenter.equals(w.getAssignedHomePos())) {
                w.setAssignedHome(null, 0, "", TravellerHomeManager.HomeType.LONE_BARRACK);
                UUID ownerId = w.getFollowOwnerId();
                EntityPlayerMP owner = ownerId != null
                        ? world.getMinecraftServer().getPlayerList().getPlayerByUUID(ownerId) : null;
                if (owner != null) TravellerFollowNet.CHANNEL.sendTo(new MsgSyncWorkerHome(w), owner);
            }
            return;
        }

        MinecraftServer srv = world.getMinecraftServer();
        WorldServer overworld = srv.getWorld(0);
        if (overworld == null) return;
        VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
        VirtualTravellerRecord rec = vwm.getRecord(workerUUID);
        if (rec != null && rec.entityNbt != null) {
            EntityTravellerWorker temp = new EntityTravellerWorker(overworld);
            temp.readFromNBT(rec.entityNbt);
            if (temp.getAssignedHomeType() == TravellerHomeManager.HomeType.LONE_BARRACK
                    && barrackCenter.equals(temp.getAssignedHomePos())) {
                temp.setAssignedHome(null, 0, "", TravellerHomeManager.HomeType.LONE_BARRACK);
                NBTTagCompound updated = new NBTTagCompound();
                temp.writeToNBT(updated);
                rec.entityNbt = updated;
                vwm.markDirty();
            }
            return;
        }

        // Dead, awaiting respawn: no bed left to respawn into, drop the pending respawn now
        // instead of waiting for the next morning tick to discover the same thing.
        PendingRespawn pr = pendingRespawns.get(workerUUID);
        if (pr != null && barrackCenter.equals(pr.center)) {
            pendingRespawns.remove(workerUUID);
        }
    }

    /** Best-effort display name for a worker UUID: live entity, then virtual record, then a pending respawn's saved NBT. */
    private String resolveWorkerName(WorldServer world, UUID workerUUID) {
        Entity e = world.getEntityFromUuid(workerUUID);
        if (e instanceof EntityTravellerWorker && e.getName() != null && !e.getName().isEmpty()) {
            return e.getName();
        }
        MinecraftServer srv = world.getMinecraftServer();
        WorldServer overworld = srv != null ? srv.getWorld(0) : null;
        if (overworld != null) {
            VirtualTravellerRecord rec = VirtualTravellerManager.get(overworld).getRecord(workerUUID);
            if (rec != null && rec.name != null && !rec.name.isEmpty()) return rec.name;
        }
        PendingRespawn pr = pendingRespawns.get(workerUUID);
        if (pr != null && pr.workerNbt != null && pr.workerNbt.hasKey("CustomName")) {
            String n = pr.workerNbt.getString("CustomName");
            if (!n.isEmpty()) return n;
        }
        return "Worker";
    }

    /**
     * Detect bed (head) positions inside the box. Uses Block#isBed so modded beds are supported.
     * For standard BlockBed-derived beds only the HEAD half is counted to avoid double counting.
     */
    public static List<BlockPos> findBeds(World world, BlockPos center, int halfWidth, int halfDepth, int halfHeight) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
            for (int y = cy - halfHeight; y <= cy + halfHeight; y++) {
                for (int z = cz - halfDepth; z <= cz + halfDepth; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    IBlockState st = world.getBlockState(p);
                    Block b = st.getBlock();
                    boolean isBed;
                    try {
                        // isBed() catches modded sleepables; instanceof catches BlockBed subclasses
                        // that don't override isBed (vanilla default only matches Blocks.BED).
                        isBed = (b instanceof BlockBed) || b.isBed(st, world, p, null);
                    } catch (Throwable t) {
                        isBed = b instanceof BlockBed;
                    }
                    if (!isBed) continue;
                    if (b instanceof BlockBed) {
                        try {
                            if (st.getValue(BlockBed.PART) != BlockBed.EnumPartType.HEAD) continue;
                        } catch (Throwable ignored) {
                            // non-standard BlockBed: count it anyway
                        }
                    }
                    out.add(p);
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Bed respawn
    // -------------------------------------------------------------------------

    public java.util.Map<UUID, PendingRespawn> getPendingRespawnsView() {
        return Collections.unmodifiableMap(pendingRespawns);
    }

    /** Removes a worker's bed assignment and any pending respawn (unassign or death with respawn disabled). */
    public void unassignWorker(UUID workerUUID) {
        for (LoneBarrackRecord r : records) {
            r.bedByWorker.remove(workerUUID);
        }
        pendingRespawns.remove(workerUUID);
        markDirty();
    }

    public void scheduleRespawn(UUID ownerUUID, UUID workerUUID, BlockPos center, int dim, NBTTagCompound workerNbt) {
        PendingRespawn pr = new PendingRespawn();
        pr.workerUUID = workerUUID;
        pr.ownerUUID = ownerUUID;
        pr.center = center;
        pr.dimension = dim;
        pr.workerNbt = workerNbt.copy();
        pr.daysRemaining = Math.max(1, TravellersConfig.WORKER.workerBarrackRespawnDays);
        pendingRespawns.put(workerUUID, pr);
        markDirty();
    }

    /** Process pending bed respawns for this world's dimension. Called once per morning. */
    public void processRespawns(WorldServer world) {
        if (!TravellersConfig.CONTROLLED_VILLAGE.workerRespawnInBarracks) return;
        int dim = world.provider.getDimension();
        List<UUID> toProcess = new ArrayList<>();
        for (PendingRespawn pr : pendingRespawns.values()) {
            if (pr.dimension == dim) toProcess.add(pr.workerUUID);
        }

        for (UUID wid : toProcess) {
            PendingRespawn pr = pendingRespawns.get(wid);
            if (pr == null) continue;

            pr.daysRemaining--;
            markDirty();
            if (pr.daysRemaining > 0) continue;

            LoneBarrackRecord rec = getByOwnerAndCenter(pr.ownerUUID, pr.center);
            BlockPos bed = rec != null ? rec.bedByWorker.get(wid) : null;
            if (rec == null || bed == null) {
                // Barrack deleted or worker lost its bed: no respawn.
                pendingRespawns.remove(wid);
                markDirty();
                continue;
            }

            if (!world.isBlockLoaded(bed)) continue; // wait until the bed chunk is loaded

            // Verify the bed still exists
            IBlockState st = world.getBlockState(bed);
            boolean stillBed;
            try { stillBed = (st.getBlock() instanceof BlockBed) || st.getBlock().isBed(st, world, bed, null); }
            catch (Throwable t) { stillBed = st.getBlock() instanceof BlockBed; }
            if (!stillBed) {
                rec.bedByWorker.remove(wid);
                pendingRespawns.remove(wid);
                markDirty();
                continue;
            }

            spawnWorker(world, pr, findSpawnBesideBed(world, bed));
            pendingRespawns.remove(wid);
            markDirty();
        }
    }

    private static void spawnWorker(WorldServer world, PendingRespawn pr, BlockPos spawnPos) {
        try {
            EntityTravellerWorker worker = new EntityTravellerWorker(world);
            worker.readFromNBT(pr.workerNbt);
            worker.setHealth(worker.getMaxHealth());
            worker.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
            worker.setFollowOrderActive(false);
            world.spawnEntity(worker);
        } catch (Exception e) {
            System.err.println("[Travellers] LoneBarrackManager: failed to respawn worker " + pr.workerUUID + ": " + e.getMessage());
        }
    }

    /** A standable spot next to the bed (on the floor, not on the roof). */
    private static BlockPos findSpawnBesideBed(WorldServer world, BlockPos bed) {
        // Try horizontal neighbors at bed level and one below — never above to avoid rooftop
        for (EnumFacing d : EnumFacing.HORIZONTALS) {
            BlockPos p = bed.offset(d);
            if (standable(world, p)) return p;
            if (standable(world, p.down())) return p.down();
        }
        // Neighbors are walls: stand on top of the bed itself (stays indoors)
        if (standable(world, bed.up())) return bed.up();
        // Last resort: the bed block (server physics will push entity to nearest air)
        return bed;
    }

    private static boolean standable(WorldServer world, BlockPos p) {
        return world.getBlockState(p.down()).isSideSolid(world, p.down(), EnumFacing.UP)
                && world.isAirBlock(p) && world.isAirBlock(p.up());
    }

    public boolean overlapsExisting(BlockPos center, int halfWidth, int halfDepth) {
        for (LoneBarrackRecord r : records) {
            int dx = Math.abs(center.getX() - r.center.getX());
            int dz = Math.abs(center.getZ() - r.center.getZ());
            if (dx < halfWidth + r.halfWidth && dz < halfDepth + r.halfDepth) return true;
        }
        return false;
    }

    public static List<BlockPos> findCoffers(World world, BlockPos center, int halfWidth, int halfDepth, int halfHeight) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
            for (int y = cy - halfHeight; y <= cy + halfHeight; y++) {
                for (int z = cz - halfDepth; z <= cz + halfDepth; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    Block b = world.getBlockState(p).getBlock();
                    if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        records.clear();
        pendingRespawns.clear();
        NBTTagList list = nbt.getTagList("Records", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            records.add(LoneBarrackRecord.readFromNBT(list.getCompoundTagAt(i)));
        }
        NBTTagList respawns = nbt.getTagList("PendingRespawns", 10);
        for (int i = 0; i < respawns.tagCount(); i++) {
            NBTTagCompound rt = respawns.getCompoundTagAt(i);
            PendingRespawn pr = new PendingRespawn();
            pr.workerUUID = rt.getUniqueId("WID");
            pr.ownerUUID = rt.getUniqueId("OID");
            pr.center = new BlockPos(rt.getInteger("CX"), rt.getInteger("CY"), rt.getInteger("CZ"));
            pr.dimension = rt.getInteger("Dim");
            pr.workerNbt = rt.getCompoundTag("Nbt");
            pr.daysRemaining = rt.hasKey("Days") ? rt.getInteger("Days") : 1;
            pendingRespawns.put(pr.workerUUID, pr);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();
        for (LoneBarrackRecord r : records) {
            NBTTagCompound tag = new NBTTagCompound();
            r.writeToNBT(tag);
            list.appendTag(tag);
        }
        nbt.setTag("Records", list);

        NBTTagList respawns = new NBTTagList();
        for (PendingRespawn pr : pendingRespawns.values()) {
            NBTTagCompound rt = new NBTTagCompound();
            rt.setUniqueId("WID", pr.workerUUID);
            rt.setUniqueId("OID", pr.ownerUUID);
            rt.setInteger("CX", pr.center.getX());
            rt.setInteger("CY", pr.center.getY());
            rt.setInteger("CZ", pr.center.getZ());
            rt.setInteger("Dim", pr.dimension);
            rt.setTag("Nbt", pr.workerNbt);
            rt.setInteger("Days", pr.daysRemaining);
            respawns.appendTag(rt);
        }
        nbt.setTag("PendingRespawns", respawns);
        return nbt;
    }

    public static class PendingRespawn {
        public UUID workerUUID;
        public UUID ownerUUID;
        public BlockPos center;
        public int dimension;
        public NBTTagCompound workerNbt;
        public int daysRemaining = 1;
    }
}
