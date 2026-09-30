package com.jubitus.travellers.world.zone;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.home.MsgSyncWorkerHome;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
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

/**
 * Per-owner storage for every {@link PlayerZoneRecord} (Barracks, Stables, and whatever gets added
 * later), plus the Barrack-only bed-respawn lifecycle. The data-name is unchanged from the original
 * lone-barrack-only version ({@code WorldSavedData} keys off this string, not the class name) so
 * existing player saves load exactly as before - only Java class names changed here.
 */
public class PlayerZoneManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersLoneBarracks";

    private final List<PlayerZoneRecord> records = new ArrayList<>();
    // worker UUID -> pending bed respawn (Barrack-only)
    private final Map<UUID, PendingRespawn> pendingRespawns = new HashMap<>();

    public PlayerZoneManager() {
        super(DATA_NAME);
    }

    public PlayerZoneManager(String name) {
        super(name);
    }

    public static PlayerZoneManager get(World world) {
        WorldServer ws = (WorldServer) world;
        MapStorage storage = ws.getPerWorldStorage();
        PlayerZoneManager data = (PlayerZoneManager) storage.getOrLoadData(PlayerZoneManager.class, DATA_NAME);
        if (data == null) {
            data = new PlayerZoneManager();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    public List<PlayerZoneRecord> getAll() {
        return Collections.unmodifiableList(records);
    }

    public List<PlayerZoneRecord> getByOwner(UUID ownerUUID) {
        List<PlayerZoneRecord> out = new ArrayList<>();
        for (PlayerZoneRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID)) out.add(r);
        }
        return out;
    }

    public List<PlayerZoneRecord> getByOwnerAndType(UUID ownerUUID, ZoneType type) {
        List<PlayerZoneRecord> out = new ArrayList<>();
        for (PlayerZoneRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID) && r.type == type) out.add(r);
        }
        return out;
    }

    public boolean removeByOwnerAndCenter(UUID ownerUUID, BlockPos center) {
        boolean removed = records.removeIf(r -> ownerUUID.equals(r.ownerUUID) && center.equals(r.center));
        if (removed) markDirty();
        return removed;
    }

    public void add(PlayerZoneRecord record) {
        records.add(record);
        markDirty();
    }

    /** Refreshes this zone's chest positions and marks dirty. Name kept from the Barrack-only era;
     * works for any zone type since it just rescans the CHEST role. */
    public void refreshCoffers(World world, UUID ownerUUID, BlockPos center) {
        for (PlayerZoneRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID) && center.equals(r.center)) {
                r.setRolePositions(ZoneRoles.CHEST,
                        scanRole(world, r.center, r.halfWidth, r.halfDepth, r.halfHeight, BlockRoleScanners.CHEST));
                markDirty();
                return;
            }
        }
    }

    @Nullable
    public PlayerZoneRecord getByOwnerAndCenter(UUID ownerUUID, BlockPos center) {
        for (PlayerZoneRecord r : records) {
            if (ownerUUID.equals(r.ownerUUID) && center.equals(r.center)) return r;
        }
        return null;
    }

    /** Rescans beds for the matching Barrack and prunes bed assignments whose bed was removed.
     * No-op if unloaded. Barrack-only. */
    public void refreshBeds(World world, UUID ownerUUID, BlockPos center) {
        if (!world.isBlockLoaded(center)) return; // never force-load chunks
        PlayerZoneRecord r = getByOwnerAndCenter(ownerUUID, center);
        if (r == null || r.type != ZoneType.BARRACK) return;
        List<BlockPos> fresh = scanRole(world, r.center, r.halfWidth, r.halfDepth, r.halfHeight, BlockRoleScanners.BED);
        Set<BlockPos> valid = new HashSet<>(fresh);
        List<BlockPos> lost = new ArrayList<>();
        for (BlockPos bed : r.getRolePositions(ZoneRoles.BED)) {
            if (!valid.contains(bed)) lost.add(bed);
        }
        r.setRolePositions(ZoneRoles.BED, fresh);
        WorldServer ws = (world instanceof WorldServer) ? (WorldServer) world : null;
        for (BlockPos bed : lost) {
            pruneBedAssignment(ws, r, bed);
        }
        markDirty();
    }

    /**
     * Called from {@link BarrackBedGuards} the instant a tracked bed is broken, so the owner is
     * notified immediately instead of only on the next barracks-GUI open (which drives {@link #refreshBeds}).
     */
    public void onBedBroken(WorldServer world, BlockPos headPos) {
        for (PlayerZoneRecord r : records) {
            if (!r.getRolePositions(ZoneRoles.BED).contains(headPos)) continue;
            if (r.type == ZoneType.STABLE && r.data instanceof com.jubitus.travellers.world.zone.stable.StableZoneData) {
                // A Stable palfrenier keeps its duty assignment on a bed break - it just does ambient
                // behaviour with no labour until a bed is restored (auto-recovered by the coordinator's
                // bed rescan). Different from a Barrack, where losing your bed means losing your home.
                com.jubitus.travellers.world.zone.stable.StableBedService.onStableBedBroken(
                        world, this, r, (com.jubitus.travellers.world.zone.stable.StableZoneData) r.data, headPos);
                return;
            }
            if (r.type != ZoneType.BARRACK) continue;
            List<BlockPos> beds = new ArrayList<>(r.getRolePositions(ZoneRoles.BED));
            if (!beds.remove(headPos)) continue;
            r.setRolePositions(ZoneRoles.BED, beds);
            pruneBedAssignment(world, r, headPos);
            markDirty();
            return; // a bed belongs to at most one zone
        }
    }

    /**
     * Removes a worker's reservation of a now-missing bed, fully clears its home assignment (so it
     * shows up as unassigned/homeless rather than still counting against the zone's capacity),
     * and notifies the owner. Does not touch bed positions.
     */
    private void pruneBedAssignment(@Nullable WorldServer world, PlayerZoneRecord r, BlockPos bed) {
        UUID workerUUID = null;
        for (Map.Entry<UUID, BlockPos> e : r.barrackData().bedByWorker.entrySet()) {
            if (bed.equals(e.getValue())) {
                workerUUID = e.getKey();
                break;
            }
        }
        if (workerUUID == null) return;
        r.barrackData().bedByWorker.remove(workerUUID);
        if (world == null || world.getMinecraftServer() == null) return;
        String workerName = resolveWorkerName(world, workerUUID);
        String barrackName = (r.name == null || r.name.isEmpty()) ? "Barrack" : r.name;
        NotificationService.notifyOffline(world.getMinecraftServer(), r.ownerUUID,
                NotificationDefs.HOME_LONE_BARRACK_BED_DESTROYED, workerName, barrackName);
        PendingRespawn pr = pendingRespawns.get(workerUUID);
        if (pr != null && r.center.equals(pr.center)) pendingRespawns.remove(workerUUID);
        ZoneAssignments.release(world.getMinecraftServer(), workerUUID, r.center);
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

    // -------------------------------------------------------------------------
    // Generic block-role scanning - shared by every zone type
    // -------------------------------------------------------------------------

    /** Scans the zone's box for every block matching {@code scanner}. This is the one shared loop
     * behind every role of every zone type - never add a second bespoke scan loop beside it. */
    public static List<BlockPos> scanRole(World world, BlockPos center, int halfWidth, int halfDepth,
                                           int halfHeight, BlockRoleScanner scanner) {
        List<BlockPos> out = new ArrayList<>();
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        for (int x = cx - halfWidth; x <= cx + halfWidth; x++) {
            for (int y = cy - halfHeight; y <= cy + halfHeight; y++) {
                for (int z = cz - halfDepth; z <= cz + halfDepth; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    IBlockState st = world.getBlockState(p);
                    if (scanner.matches(world, p, st)) out.add(p);
                }
            }
        }
        return out;
    }

    public static List<BlockPos> findBeds(World world, BlockPos center, int halfWidth, int halfDepth, int halfHeight) {
        return scanRole(world, center, halfWidth, halfDepth, halfHeight, BlockRoleScanners.BED);
    }

    public static List<BlockPos> findCoffers(World world, BlockPos center, int halfWidth, int halfDepth, int halfHeight) {
        return scanRole(world, center, halfWidth, halfDepth, halfHeight, BlockRoleScanners.CHEST);
    }

    // -------------------------------------------------------------------------
    // Bed respawn (Barrack-only)
    // -------------------------------------------------------------------------

    public Map<UUID, PendingRespawn> getPendingRespawnsView() {
        return Collections.unmodifiableMap(pendingRespawns);
    }

    /** Drops every pending bed respawn scheduled for a zone that is being deleted. */
    public void dropPendingRespawnsForZone(BlockPos zoneCenter) {
        if (pendingRespawns.values().removeIf(pr -> zoneCenter.equals(pr.center))) markDirty();
    }

    /** Removes a worker's bed assignment and any pending respawn (unassign or death with respawn disabled). */
    public void unassignWorker(UUID workerUUID) {
        for (PlayerZoneRecord r : records) {
            if (r.data instanceof ZoneBedData) ((ZoneBedData) r.data).bedByWorker().remove(workerUUID);
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

            PlayerZoneRecord rec = getByOwnerAndCenter(pr.ownerUUID, pr.center);
            BlockPos bed = (rec != null && rec.data instanceof ZoneBedData)
                    ? ((ZoneBedData) rec.data).bedByWorker().get(wid) : null;
            if (rec == null || bed == null) {
                // Zone deleted or worker lost its bed: no respawn. (Works for a Stable palfrenier's
                // bed too - a Stable bed is also that worker's home, see MsgAssignWorkerDuty.)
                pendingRespawns.remove(wid);
                markDirty();
                continue;
            }

            if (!world.isBlockLoaded(bed)) continue; // wait until the bed chunk is loaded

            // Verify the bed still exists
            IBlockState st = world.getBlockState(bed);
            boolean stillBed;
            try { stillBed = BlockRoleScanners.BED.matches(world, bed, st); }
            catch (Throwable t) { stillBed = false; }
            if (!stillBed) {
                if (rec.data instanceof ZoneBedData) ((ZoneBedData) rec.data).bedByWorker().remove(wid);
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
            System.err.println("[Travellers] PlayerZoneManager: failed to respawn worker " + pr.workerUUID + ": " + e.getMessage());
        }
    }

    /** A standable spot next to the bed (on the floor, not on the roof). Shared with the palfrenier
     * materialize path so a Stable resident reappears at the foot of its bed inside the structure,
     * exactly like a Barrack death-respawn, never on the rooftop above an indoor Stable. */
    public static BlockPos findSpawnBesideBed(WorldServer world, BlockPos bed) {
        // Try horizontal neighbors at bed level and one below — never above to avoid rooftop
        for (EnumFacing d : EnumFacing.HORIZONTALS) {
            BlockPos p = bed.offset(d);
            if (ZonePlacement.isStandableColumn(world, p)) return p;
            if (ZonePlacement.isStandableColumn(world, p.down())) return p.down();
        }
        // Neighbors are walls: stand on top of the bed itself (stays indoors)
        if (ZonePlacement.isStandableColumn(world, bed.up())) return bed.up();
        // Last resort: the bed block (server physics will push entity to nearest air)
        return bed;
    }

    public boolean overlapsExisting(BlockPos center, int halfWidth, int halfDepth) {
        for (PlayerZoneRecord r : records) {
            int dx = Math.abs(center.getX() - r.center.getX());
            int dz = Math.abs(center.getZ() - r.center.getZ());
            if (dx < halfWidth + r.halfWidth && dz < halfDepth + r.halfDepth) return true;
        }
        return false;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        records.clear();
        pendingRespawns.clear();
        NBTTagList list = nbt.getTagList("Records", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            records.add(PlayerZoneRecord.readFromNBT(list.getCompoundTagAt(i)));
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
        for (PlayerZoneRecord r : records) {
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
