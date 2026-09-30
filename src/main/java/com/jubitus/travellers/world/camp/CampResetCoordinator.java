package com.jubitus.travellers.world.camp;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.world.camp.shelter.TerrainSnapshot;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Drives "/travellersdebug camp reset [all]": restores every campsite's terrain to how it was
 * before any camp ever built on it, without requiring a player to be nearby. Plain "reset" keeps
 * campsite records/slots (no rediscovery needed) and only wipes occupation/build state; "reset all"
 * additionally deletes every processed record and clears discovery bookkeeping, so campsites are
 * rediscovered from scratch afterward.
 *
 * Paced across ticks ({@link TravellersConfig.CampEvents#campResetCampsitesPerTick} campsites per
 * tick) so a world with hundreds of campsites doesn't spike lag or memory. Each campsite's chunks
 * are loaded only long enough to run its restore synchronously (same "temporary load, no ticket,
 * unloads naturally" pattern already used for worker chunk hops in TravellerChunkManager) — nothing
 * here forces chunks to stay loaded afterward.
 *
 * A campsite only needs its chunks touched if it actually has terrain to restore (pending
 * teardowns, ruin orphans, a built firepit, or an occupant that touched terrain) — the build system
 * never places a block without recording a matching TerrainSnapshot, so a record with none of these
 * provably has nothing physical to clean up.
 */
public class CampResetCoordinator {

    public static final CampResetCoordinator INSTANCE = new CampResetCoordinator();

    private final Map<Integer, ResetJob> jobs = new HashMap<>();

    private CampResetCoordinator() {
    }

    public boolean isActive(int dim) {
        return jobs.containsKey(dim);
    }

    /**
     * Starts a reset job for this world. Returns false if one is already running here.
     * Reports immediately (and does nothing further) if there are no campsites to process.
     *
     * @param deleteRecords if true, every processed record is deleted outright (and discovery
     *                      bookkeeping cleared) instead of being kept with its state wiped —
     *                      campsites are rediscovered from scratch afterward.
     */
    public boolean start(WorldServer world, ICommandSender requester, boolean deleteRecords) {
        int dim = world.provider.getDimension();
        if (jobs.containsKey(dim)) return false;

        CampsiteManager mgr = CampsiteManager.get(world);
        Deque<CampsiteRecord> pending = new ArrayDeque<>();
        for (CampsiteRecord rec : mgr.getAll()) {
            if (rec.dimension == dim) pending.add(rec);
        }

        if (pending.isEmpty()) {
            requester.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "Camp reset: no campsites found in this dimension."));
            return true;
        }

        ResetJob job = new ResetJob(pending, requester, world.getTotalWorldTime(), deleteRecords);
        jobs.put(dim, job);
        requester.sendMessage(new TextComponentString(TextFormatting.GREEN
                + "Camp reset started: " + TextFormatting.YELLOW + job.total + TextFormatting.GREEN
                + " campsite(s) queued." + (deleteRecords
                ? TextFormatting.GRAY + " Campsites will be deleted (full wipe)."
                : "")));
        return true;
    }

    public void tick(WorldServer world, long now) {
        int dim = world.provider.getDimension();
        ResetJob job = jobs.get(dim);
        if (job == null) return;

        CampsiteManager mgr = CampsiteManager.get(world);
        int budget = TravellersConfig.CAMP_EVENTS.campResetCampsitesPerTick;
        boolean touchedAny = false;
        for (int i = 0; i < budget && !job.pending.isEmpty(); i++) {
            CampsiteRecord rec = job.pending.poll();
            if (processOne(world, rec)) job.restoredCount++;
            job.done++;
            touchedAny = true;
            if (job.deleteRecords) mgr.removeRecord(rec);
        }
        if (touchedAny) mgr.markDirty();

        int reportInterval = TravellersConfig.CAMP_EVENTS.campResetProgressIntervalTicks;
        if (now - job.lastReportTick >= reportInterval) {
            job.lastReportTick = now;
            job.requester.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Camp reset progress: " + job.done + "/" + job.total
                    + " campsite(s) checked (" + job.restoredCount + " had terrain restored)."));
        }

        if (job.pending.isEmpty()) {
            job.requester.sendMessage(new TextComponentString(TextFormatting.GREEN
                    + "Camp reset complete: " + TextFormatting.YELLOW + job.total + TextFormatting.GREEN
                    + " campsite(s) checked, " + TextFormatting.YELLOW + job.restoredCount + TextFormatting.GREEN
                    + " had terrain restored. " + (job.deleteRecords
                    ? "All campsites deleted; they will be rediscovered from scratch."
                    : "Slots and geometry were kept.")));
            jobs.remove(dim);
        }
    }

    /**
     * Restores one campsite's terrain if it has any (loading its footprint chunks just long enough
     * to do so), then clears its occupation/build bookkeeping either way (harmless if the record is
     * about to be deleted). Returns true if terrain was actually touched.
     */
    private boolean processOne(WorldServer world, CampsiteRecord rec) {
        boolean hasTerrain = !rec.pendingTeardowns.isEmpty() || !rec.orphans.isEmpty()
                || (rec.firepitBuilt && rec.firepitSnapshot != null)
                || rec.occupants.values().stream().anyMatch(o -> o.snapshot != null);

        if (hasTerrain) {
            for (ChunkPos cp : CampsiteManager.footprintChunks(rec)) {
                world.getChunkProvider().provideChunk(cp.x, cp.z); // temporary load; unloads naturally afterward
            }
            for (TerrainSnapshot s : rec.pendingTeardowns) s.restore(world);
            for (TerrainSnapshot s : rec.orphans.values()) s.restore(world);
            for (CampOccupant o : rec.occupants.values()) {
                if (o.snapshot != null) o.snapshot.restore(world);
            }
            if (rec.firepitBuilt && rec.firepitSnapshot != null) rec.firepitSnapshot.restore(world);
        }

        rec.pendingTeardowns.clear();
        rec.orphans.clear();
        CampsiteManager mgr = CampsiteManager.get(world);
        for (CampOccupant o : rec.occupants.values()) mgr.deindexOccupant(o.travellerUUID);
        rec.occupants.clear();
        rec.firepitBuilt = false;
        rec.firepitSnapshot = null;
        rec.firepitLit = true;
        rec.emptySinceTick = -1L;

        return hasTerrain;
    }

    private static class ResetJob {
        final Deque<CampsiteRecord> pending;
        final ICommandSender requester;
        final int total;
        final boolean deleteRecords;
        int done = 0;
        int restoredCount = 0;
        long lastReportTick;

        ResetJob(Deque<CampsiteRecord> pending, ICommandSender requester, long now, boolean deleteRecords) {
            this.pending = pending;
            this.requester = requester;
            this.total = pending.size();
            this.lastReportTick = now;
            this.deleteRecords = deleteRecords;
        }
    }
}
