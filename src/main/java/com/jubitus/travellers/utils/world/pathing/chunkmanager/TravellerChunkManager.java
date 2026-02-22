package com.jubitus.travellers.utils.world.pathing.chunkmanager;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.util.*;

// TravellerChunkManager.java
// TravellerChunkManager.java
public class TravellerChunkManager implements ForgeChunkManager.LoadingCallback {
    public static final TravellerChunkManager INSTANCE = new TravellerChunkManager();
    private static final int HARD_STUCK_TICKS = 20 * 60 * 5; // 5 minutes
    private static final String NBT_FORCED_CHUNKS = "ForcedChunks";
    // Dimension -> state
    private final Map<Integer, WorldState> worldStates = new HashMap<>();

    private TravellerChunkManager() {
    }

    public static void registerCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(Travellers.INSTANCE, INSTANCE);
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        if (!(event.world instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.world;
        WorldState state = getOrCreateState(ws);

        // ✅ cheap: hops can stay frequent
        tickOffscreenHops(state);
        batchLoadRestoredChunks(state);
        // ✅ expensive: recompute less often
        int interval = Math.max(1, TravellersModConfig.chunkRecomputeIntervalTicks); // add config
        if ((ws.getTotalWorldTime() % interval) == 0) {
            recomputeForWorld(state);
        }
        boolean dirty = false;
        for (MerchantData md : state.merchants.values()) {
            if (md.movedChunk) {
                dirty = true;
                md.movedChunk = false;
            }
        }

        if (dirty) recomputeForWorld(state);

    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.getWorld();
        int dim = ws.provider.getDimension();

        WorldState state = worldStates.get(dim);
        if (state != null) {
            saveForcedToTicket(state); // <- persist latest list
        }

        worldStates.remove(dim);
    }

    private void saveForcedToTicket(WorldState state) {
        if (state.ticket == null) return;

        NBTTagCompound tag = state.ticket.getModData();
        NBTTagList list = new NBTTagList();

        for (ChunkPos cp : state.forced) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", cp.x);
            c.setInteger("z", cp.z);
            list.appendTag(c);
        }

        tag.setTag(NBT_FORCED_CHUNKS, list);
    }

    // Called by Forge on world load to restore tickets
    @Override
    public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
        if (world.isRemote) return;
        WorldServer ws = (WorldServer) world;
        WorldState state = getOrCreateState(ws);

        System.out.println("[Travellers] ticketsLoaded: dim=" + ws.provider.getDimension()
                + " tickets=" + tickets.size());

        // 1) Read any saved chunk list from ANY ticket (NORMAL or ENTITY)
        Set<ChunkPos> restored = new HashSet<>();

        for (ForgeChunkManager.Ticket t : tickets) {
            try {
                NBTTagCompound tag = t.getModData();
                NBTTagList list = tag.getTagList(NBT_FORCED_CHUNKS, 10); // 10 = compound

                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound c = list.getCompoundTagAt(i);
                    restored.add(new ChunkPos(c.getInteger("x"), c.getInteger("z")));
                }
            } catch (Throwable ignored) {
                // ignore bad/empty tickets
            }
        }

        // 2) Keep ONLY a NORMAL ticket (release entity tickets + extras)
        ForgeChunkManager.Ticket normalTicket = null;

        for (ForgeChunkManager.Ticket t : tickets) {
            if (t.getType() == ForgeChunkManager.Type.NORMAL && normalTicket == null) {
                normalTicket = t;
            } else {
                // release entity tickets and extra normal tickets
                ForgeChunkManager.releaseTicket(t);
            }
        }

        state.ticket = normalTicket;

        // 3) If we didn't have a NORMAL ticket, request one now
        if (state.ticket == null) {
            state.ticket = ForgeChunkManager.requestTicket(
                    Travellers.INSTANCE,
                    ws,
                    ForgeChunkManager.Type.NORMAL
            );

            if (state.ticket == null) {
                System.out.println("[Travellers] FAILED to get NORMAL ticket in dim " +
                        ws.provider.getDimension());
                return;
            }

            System.out.println("[Travellers] Created NORMAL ticket for dim " + ws.provider.getDimension());
            state.ticket.setChunkListDepth(TravellersModConfig.merchantMaxForcedChunks);
        } else {
            // Ensure depth is correct even on restored tickets
            state.ticket.setChunkListDepth(TravellersModConfig.merchantMaxForcedChunks);
        }

        // 4) Apply restored chunk forcing using the NORMAL ticket (safe)
        state.forced.clear();
        state.forced.addAll(restored);

        System.out.println("[Travellers] Restoring " + restored.size() + " forced chunks");

        ChunkProviderServer cps = ws.getChunkProvider();

        for (ChunkPos pos : restored) {
            ForgeChunkManager.forceChunk(state.ticket, pos);
        }

// Grace period so recompute doesn't instantly unforce restored chunks
        if (!restored.isEmpty()) {
            long now = ws.getTotalWorldTime();
            state.restoredForcedFromDisk = true;
            state.restoreGraceUntilTick = now + (20L * 10L); // 10 seconds, tune
        }

// Load them gradually to avoid lag spikes
        state.pendingRestoreLoad.clear();
        state.pendingRestoreLoad.addAll(restored);


        // 5) Save back into the ticket NBT so it persists cleanly
        saveForcedToTicket(state);
    }

    private WorldState getOrCreateState(WorldServer ws) {
        return worldStates.computeIfAbsent(ws.provider.getDimension(), dim -> new WorldState(ws));
    }

    // Called each tick by any travellers that should chunkload (merchant, worker, etc.)
    public void onChunkloaderTick(EntityTraveller traveller, int radius) {
        if (traveller.world.isRemote) return;
        if (!TravellersModConfig.merchantChunkloadingEnabled) return;
        if (!isChunkloader(traveller)) return; // ✅ only workers/merchants participate

        WorldServer ws = (WorldServer) traveller.world;
        WorldState state = getOrCreateState(ws);


        MerchantData data = state.merchants.get(traveller.getUniqueID());
        if (data == null) {
            data = new MerchantData(traveller, radius);
            state.merchants.put(traveller.getUniqueID(), data);
        } else {
            data.radius = radius;
            data.updateFromEntity(traveller);
        }
    }

    // ----------------------------------------------------------------
    // Per-merchant API
    // ----------------------------------------------------------------

    private static boolean isChunkloader(EntityTraveller t) {
        return (t instanceof EntityTravellerWorker) || (t instanceof EntityTravellerMerchant);
    }

    private void tickOffscreenHops(WorldState state) {
        if (!TravellersModConfig.travellerOffscreenHopEnabled) return;

        int interval = TravellersModConfig.travellerOffscreenHopIntervalTicks;
        if (interval <= 0) return;

        long now = state.world.getTotalWorldTime();
        boolean anyHop = false;

        for (MerchantData md : state.merchants.values()) {
            Entity ent = state.world.getEntityFromUuid(md.id);
            if (!(ent instanceof EntityTraveller)) {

                ChunkProviderServer cps = state.world.getChunkProvider();
                cps.provideChunk(md.lastChunkX, md.lastChunkZ);

                ent = state.world.getEntityFromUuid(md.id);
                if (!(ent instanceof EntityTraveller)) continue;
            }


            EntityTraveller t = (EntityTraveller) ent;

// don't touch boats for onscreen entities
            if (t.isInPlayerViewDistance()) continue;

// NOW it's offscreen, it's safe/desired to drop boat
            if (t.isRiding()) {
                Entity r = t.getRidingEntity();
                t.dismountRidingEntity();

                if (r instanceof EntityTravellerBoat) {
                    ((EntityTravellerBoat) r).killNow();   // use your immediate kill
                } else if (r instanceof EntityBoat) {
                    r.setDead();
                }
            }

            if (t.isInCombat() || t.isAutoEating() || t.isHandActive()) continue;
            BlockPos goal = getChunkloadGoal(t);
            if (goal == null) continue;
            if (now - md.lastOffscreenHopTick < interval) continue;

            boolean ok = t.offscreenHopTowardPos(goal, TravellersModConfig.travellerOffscreenHopStepBlocks);
            if (ok) {
                md.lastOffscreenHopTick = now;

                // ✅ very important
                md.updateFromEntity(t);

                anyHop = true;
            }
        }

        // ✅ Make forced chunks follow immediately
        if (anyHop) {
            recomputeForWorld(state);
        }
    }


    public void onChunkloaderRemoved(EntityTraveller traveller) {
        if (traveller.world.isRemote) return;
        if (!(traveller.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) traveller.world;
        WorldState state = getOrCreateState(ws);

        state.merchants.remove(traveller.getUniqueID());
        // We’ll drop any now-unused chunks on next recompute
    }


    // ----------------------------------------------------------------
    // Chunk recompute logic
    // ----------------------------------------------------------------

    @Nullable
    public MerchantData getMerchantData(World world, UUID id) {
        if (!(world instanceof WorldServer)) return null;
        WorldState state = worldStates.get(world.provider.getDimension());
        return state == null ? null : state.merchants.get(id);
    }

    @Nullable
    public WorldState getWorldState(World world) {
        if (!(world instanceof WorldServer)) return null;
        return worldStates.get(world.provider.getDimension());
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().world.isRemote) return;
        if (!(event.getEntity().world instanceof WorldServer)) return;

        if (!(event.getEntity() instanceof EntityTraveller)) return;

        EntityTraveller t = (EntityTraveller) event.getEntity();
        if (!isChunkloader(t)) return;

        // removes from tracking and unforces if it was the last one
        untrackChunkloader(t);
    }

    public void untrackChunkloader(EntityTraveller traveller) {
        if (traveller.world.isRemote || !(traveller.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) traveller.world;

        WorldState state = getOrCreateState(ws);

        if (state.merchants.remove(traveller.getUniqueID()) != null) {
            if (state.merchants.isEmpty()) {
                // Last chunkloading travellers in this world is gone
                clearForcedChunks(state);
            } else {
                recomputeForWorld(state);
            }
        }
    }


    // ----------------------------------------------------------------
    // State / debug accessors
    // ----------------------------------------------------------------

    private void clearForcedChunks(WorldState state) {
        if (state.ticket == null) {
            state.forced.clear();
            return;
        }

        for (ChunkPos cp : state.forced) {
            ForgeChunkManager.unforceChunk(state.ticket, cp);
        }
        state.forced.clear();

        // Clear stored NBT too
        NBTTagCompound tag = state.ticket.getModData();
        tag.removeTag(NBT_FORCED_CHUNKS);

        ForgeChunkManager.releaseTicket(state.ticket);
        state.ticket = null;
    }

    private void recomputeForWorld(WorldState state) {
        // prune dead merchants
        Iterator<Map.Entry<UUID, MerchantData>> it = state.merchants.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MerchantData> e = it.next();
            Entity ent = state.world.getEntityFromUuid(e.getKey());

            if (ent == null) {
                // keep for a while (unloaded), but don't keep forever (dead/despawned)
                long now = state.world.getTotalWorldTime();
                long ttl = 20L * 60L * 5L; // 5 minutes
                if (now - e.getValue().lastSeenTick > ttl) {
                    it.remove();
                }
                continue;
            }

            if (!(ent instanceof EntityTraveller) || !ent.isEntityAlive()) {
                it.remove();
                continue;
            }

            EntityTraveller t = (EntityTraveller) ent;
            if (!isChunkloader(t)) {
                it.remove();
                continue;
            }
        }


        if (state.merchants.isEmpty()) {
            long now = state.world.getTotalWorldTime();

            boolean inGrace = state.restoredForcedFromDisk && now < state.restoreGraceUntilTick;
            boolean stillLoading = !state.pendingRestoreLoad.isEmpty();

            // During startup restoration: KEEP forced chunks so entities can tick and re-register.
            if (inGrace || stillLoading) {
                saveForcedToTicket(state); // keep persisted list as-is
                return;
            }

            // Optional: only after grace, you may decide to clear.
            if (state.ticket != null) {
                for (ChunkPos cp : state.forced) ForgeChunkManager.unforceChunk(state.ticket, cp);
            }
            state.forced.clear();
            saveForcedToTicket(state);
            return;
        }


        ensureTicket(state);

        // 1) Decide which merchants are allowed to chunkload this tick
        // 1) Decide which merchants are allowed to chunkload this tick
        List<MerchantData> hired = new ArrayList<>();
        List<MerchantData> nonHired = new ArrayList<>();

        for (MerchantData md : state.merchants.values()) {
            // If entity is currently loaded, refresh hired status right now (extra safety)
            Entity ent = state.world.getEntityFromUuid(md.id);
            if (ent instanceof EntityTravellerWorker) {
                md.hiredFollower = ((EntityTravellerWorker) ent).isHiredFollower();
            }

            if (md.hiredFollower) hired.add(md);
            else nonHired.add(md);
        }

// Sort non-hired by priority (these are the ones we cap)
        nonHired.sort((a, b) -> Integer.compare(
                b.chunkloadingPriority(state.world),
                a.chunkloadingPriority(state.world)
        ));

        int allowedNonHired = Math.min(
                TravellersModConfig.merchantMaxChunkloadingMerchants,
                nonHired.size()
        );

// Reset flags
        for (MerchantData md : state.merchants.values()) md.chunkloading = false;

// Hired workers ALWAYS chunkload (do not count toward cap)
        for (MerchantData md : hired) md.chunkloading = true;

// Only top N non-hired chunkload
        for (int i = 0; i < allowedNonHired; i++) {
            nonHired.get(i).chunkloading = true;
        }

// This is the final list we use to build desired chunks.
// Put hired first so they get chunk budget first.
        List<MerchantData> active = new ArrayList<>();
        active.addAll(hired);
        active.addAll(nonHired.subList(0, allowedNonHired));

// still keep this guard
        if (active.isEmpty()) return;

        Set<ChunkPos> desired = new HashSet<>();
        int maxForced = TravellersModConfig.merchantMaxForcedChunks;

// ✅ BASELINE: ALWAYS keep each tracked travellers's CURRENT chunk forced.
// This guarantees the entity stays loaded so offscreen hops can run,
// even if they are not currently "allowed" to chunkload extras this tick.
        for (MerchantData md : state.merchants.values()) {
            int cx = md.pos.getX() >> 4;
            int cz = md.pos.getZ() >> 4;
            desired.add(new ChunkPos(cx, cz));
            if (desired.size() >= maxForced) break; // obey global budget
        }

// ✅ EXTRAS: only give goal/neighbor chunks to the "active" chunkloaders.
        for (MerchantData md : active) {
            if (!md.chunkloading) continue;
            if (desired.size() >= maxForced) break;

            int cx = md.pos.getX() >> 4;
            int cz = md.pos.getZ() >> 4;

            // (cx,cz) is already included by baseline, but harmless to re-add.
            desired.add(new ChunkPos(cx, cz));

            Entity ent = state.world.getEntityFromUuid(md.id);
            if (ent instanceof EntityTraveller) {
                EntityTraveller t = (EntityTraveller) ent;

                BlockPos tv = getChunkloadGoal(t);
                if (tv != null) {
                    int tx = tv.getX() >> 4;
                    int tz = tv.getZ() >> 4;

                    int dx = Integer.compare(tx, cx);
                    int dz = Integer.compare(tz, cz);

                    int ncx = cx + dx;
                    int ncz = cz + dz;

                    for (int ox = -1; ox <= 1; ox++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            desired.add(new ChunkPos(ncx + ox, ncz + oz));
                            if (desired.size() >= maxForced) break;
                        }
                        if (desired.size() >= maxForced) break;
                    }
                }
            }

            // optional safety neighbors (your existing code)
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    desired.add(new ChunkPos(cx + ox, cz + oz));
                    if (desired.size() >= maxForced) break;
                }
                if (desired.size() >= maxForced) break;
            }
        }


        if (desired.isEmpty()) {
            // If we have merchants but no desired chunks (config limits, etc.),
            // DO NOT wipe existing forced chunks. This keeps at least some area
            // around a merchant loaded so the ticket always has chunks to persist.
            return;
        }
        long now = state.world.getTotalWorldTime();
        boolean inGrace = state.restoredForcedFromDisk && now < state.restoreGraceUntilTick;
        boolean restoring = inGrace || !state.pendingRestoreLoad.isEmpty();

        Set<ChunkPos> toUnforce = new HashSet<>(state.forced);
        toUnforce.removeAll(desired);

        Set<ChunkPos> toForce = new HashSet<>(desired);
        toForce.removeAll(state.forced);

// ✅ while restoring, keep everything that was restored forced
        if (restoring) {
            desired.addAll(state.forced);
            toUnforce.clear();
        }


        for (ChunkPos cp : toUnforce) {
            // Always remove from OUR ticket
            ForgeChunkManager.unforceChunk(state.ticket, cp);

            // But only try to actively unload if nothing else should keep it loaded
            if (!shouldAvoidQueueUnload(state, cp)) {
                state.unloading.put(cp, state.world.getTotalWorldTime());

                ChunkProviderServer cps = state.world.getChunkProvider();
                Chunk ch = cps.getLoadedChunk(cp.x, cp.z);
                if (ch != null) cps.queueUnload(ch);
            } else {
                // If it’s in render distance / forced elsewhere, don’t track it as "unloading"
                state.unloading.remove(cp);
            }
        }


        for (ChunkPos cp : toForce) {
            ForgeChunkManager.forceChunk(state.ticket, cp);
        }

        state.forced.clear();
        state.forced.addAll(desired);
// Persist this to the ticket so Forge can save it
        saveForcedToTicket(state);
        vacuumUnneededChunks(state);
    }

    // ----------------------------------------------------
    // Inner classes
    // ----------------------------------------------------

    private void ensureTicket(WorldState state) {
        if (state.ticket != null) return;

        state.ticket = ForgeChunkManager.requestTicket(
                Travellers.INSTANCE,
                state.world,
                ForgeChunkManager.Type.NORMAL
        );
        if (state.ticket == null) {
            System.out.println("[Travellers] FAILED to get chunkloading ticket in dim " +
                    state.world.provider.getDimension());
            return;
        }
        System.out.println("[Travellers] Created ticket for dim " + state.world.provider.getDimension());
        state.ticket.setChunkListDepth(TravellersModConfig.merchantMaxForcedChunks);
    }

    @Nullable
    private static BlockPos getChunkloadGoal(EntityTraveller t) {
        // Worker-only: when catchup is active, follow the dynamic goal (owner/player pos)
        if (t instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) t;

            if (w.isCatchupTravel()) { // your flag
                BlockPos cg = w.getDynamicCatchupGoal(); // your cached/updated goal (eg every 200 ticks)
                if (cg != null) return cg;
            }
        }

        // Everyone else: existing behavior
        return t.getTargetVillage();
    }

    private boolean shouldAvoidQueueUnload(WorldState state, ChunkPos cp) {
        WorldServer ws = state.world;

        // 1) If any player could be seeing it, don’t try to unload it.
        if (isInAnyPlayerViewDistance(ws, cp)) return true;

        // 2) If another mod (or spawn chunks, etc.) is forcing it, don’t try to unload it.
        return isForcedByOtherTicket(ws, cp, state.ticket);
    }

    private void vacuumUnneededChunks(WorldState state) {
        final long now = state.world.getTotalWorldTime();
        final long ttl = 20L * 60L; // 60 seconds; tune

        ChunkProviderServer cps = state.world.getChunkProvider();

        Iterator<Map.Entry<ChunkPos, Long>> it = state.unloading.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Long> e = it.next();
            ChunkPos cp = e.getKey();

            // if it became desired again, stop vacuuming it
            if (state.forced.contains(cp)) {
                it.remove();
                continue;
            }

            // give up after TTL (prevents unbounded growth)
            if (now - e.getValue() > ttl) {
                it.remove();
                continue;
            }

            Chunk ch = cps.getLoadedChunk(cp.x, cp.z);
            if (ch != null) {
                if (!shouldAvoidQueueUnload(state, cp)) {
                    cps.queueUnload(ch);
                } else {
                    // Stop vacuuming it if it’s now near players or forced elsewhere
                    it.remove();
                }
            } else {
                it.remove();
            }

        }

        // optional: process immediately (careful with perf)
        // cps.unloadQueuedChunks();
    }

    private boolean isInAnyPlayerViewDistance(WorldServer ws, ChunkPos cp) {
        // Server render distance in chunks
        int view = ws.getMinecraftServer().getPlayerList().getViewDistance();

        // Compare to each player in this dimension
        for (EntityPlayerMP p : ws.getMinecraftServer().getPlayerList().getPlayers()) {
            if (p.world != ws) continue;
            int pcx = p.chunkCoordX;
            int pcz = p.chunkCoordZ;

            int dx = Math.abs(cp.x - pcx);
            int dz = Math.abs(cp.z - pcz);

            // Vanilla view distance is a square
            if (dx <= view && dz <= view) return true;
        }
        return false;
    }

    private boolean isForcedByOtherTicket(WorldServer ws, ChunkPos cp, @Nullable ForgeChunkManager.Ticket ourTicket) {
        try {
            // In many Forge builds, this returns a multimap of forced chunks -> tickets.
            // Signature varies a bit across versions, so keep it wrapped in try/catch.
            // If it exists, it’s the best way to detect "another mod is forcing this chunk".
            com.google.common.collect.ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> forced =
                    ForgeChunkManager.getPersistentChunksFor(ws);

            if (!forced.containsKey(cp)) return false;

            for (ForgeChunkManager.Ticket t : forced.get(cp)) {
                if (ourTicket != null && t == ourTicket) continue; // ignore ourselves
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            // If method/signature not available in your Forge build, we just can’t detect it here.
            return false;
        }
    }

    private void batchLoadRestoredChunks(WorldState state) {
        if (state.pendingRestoreLoad.isEmpty()) return;

        final int perTick = 2;
        ChunkProviderServer cps = state.world.getChunkProvider();

        for (int i = 0; i < perTick && !state.pendingRestoreLoad.isEmpty(); i++) {
            ChunkPos pos = state.pendingRestoreLoad.pollFirst();
            cps.provideChunk(pos.x, pos.z);
        }

        // ✅ restore loading done: allow recompute to shrink again
        if (state.pendingRestoreLoad.isEmpty() && state.world.getTotalWorldTime() >= state.restoreGraceUntilTick) {
            state.restoredForcedFromDisk = false;
        }
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.getWorld();
        WorldState state = worldStates.get(ws.provider.getDimension());
        if (state != null) saveForcedToTicket(state);
    }

    public static class WorldState {
        public final Set<ChunkPos> forced = new HashSet<>();
        public final ArrayDeque<ChunkPos> pendingRestoreLoad = new ArrayDeque<>();
        final WorldServer world;
        final Map<UUID, MerchantData> merchants = new HashMap<>();
        final Map<ChunkPos, Long> unloading = new HashMap<>();
        public long restoreGraceUntilTick = 0;
        public boolean restoredForcedFromDisk = false;
        ForgeChunkManager.Ticket ticket;

        public WorldState(WorldServer w) {
            this.world = w;
        }


    }

    public static class MerchantData {
        public final UUID id;
        public BlockPos pos;
        public boolean chunkloading;
        public int radius;
        public double lastDistToTargetSq;
        public long lastProgressTick;
        public long lastOffscreenHopTick = 0;
        public long lastSeenTick;
        public boolean hiredFollower;
        public int lastChunkX, lastChunkZ;
        public boolean movedChunk;

        @Nullable
        private BlockPos lastTargetVillage;

        public MerchantData(EntityTraveller t, int radius) {
            this.id = t.getUniqueID();
            this.pos = t.getPosition();
            int cx = this.pos.getX() >> 4;
            int cz = this.pos.getZ() >> 4;
            this.lastChunkX = cx;
            this.lastChunkZ = cz;
            this.movedChunk = false;

            this.radius = radius;

            // ✅ track hired follower status
            if (t instanceof EntityTravellerWorker) {
                this.hiredFollower = ((EntityTravellerWorker) t).isHiredFollower();
            } else {
                this.hiredFollower = false;
            }

            BlockPos goal = TravellerChunkManager.getChunkloadGoal(t);
            this.lastTargetVillage = (goal == null ? null : goal.toImmutable());
            this.lastDistToTargetSq = computeDistSqToGoal(t);
            this.lastProgressTick = t.world.getTotalWorldTime();
            this.lastSeenTick = t.world.getTotalWorldTime(); // (you already added lastSeenTick elsewhere)
        }

        private double computeDistSqToGoal(EntityTraveller t) {
            BlockPos goal = TravellerChunkManager.getChunkloadGoal(t);
            if (goal == null) return Double.MAX_VALUE;
            return t.getDistanceSqToCenter(goal);
        }

        public void updateFromEntity(EntityTraveller t) {
            this.lastSeenTick = t.world.getTotalWorldTime();
            this.pos = t.getPosition();

            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;

            if (cx != lastChunkX || cz != lastChunkZ) {
                movedChunk = true;
                lastChunkX = cx;
                lastChunkZ = cz;
            }

            BlockPos tv = TravellerChunkManager.getChunkloadGoal(t);
            long nowTick = t.world.getTotalWorldTime();

            // ✅ keep hired status fresh
            if (t instanceof EntityTravellerWorker) {
                this.hiredFollower = ((EntityTravellerWorker) t).isHiredFollower();
            } else {
                this.hiredFollower = false;
            }

            if (tv == null) {
                // No target -> don't consider them stuck, just keep resetting
                lastTargetVillage = null;
                lastDistToTargetSq = Double.MAX_VALUE;
                lastProgressTick = nowTick;
                return;
            }

            double nowD2 = computeDistSqToTarget(t);

            if (!Objects.equals(tv, lastTargetVillage)) {
                lastTargetVillage = tv.toImmutable();
                lastDistToTargetSq = nowD2;
                lastProgressTick = nowTick;
                return;
            }

            if (nowD2 < lastDistToTargetSq - 1.0D) {
                lastDistToTargetSq = nowD2;
                lastProgressTick = nowTick;
            }
        }

        private double computeDistSqToTarget(EntityTraveller t) {
            BlockPos tv = t.getTargetVillage();
            if (tv == null) return Double.MAX_VALUE;
            return t.getDistanceSqToCenter(tv);
        }

        public int chunkloadingPriority(WorldServer world) {
            Entity e = world.getEntityFromUuid(this.id);

            // ✅ hired followers must always be chunkloaded
            if (e instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                if (w.isHiredFollower()) return 1000;
            }

            // ✅ near-player chunkloaders should win (optional)
            if (e instanceof EntityTraveller) {
                EntityTraveller t = (EntityTraveller) e;
                if (t.isInPlayerViewDistance()) return 50;
            }

            int idleTicks = ticksSinceLastProgress(world);
            if (idleTicks < 200) return 2;
            if (idleTicks < 1200) return 1;
            return 0;
        }

        public int ticksSinceLastProgress(WorldServer world) {
            return (int) (world.getTotalWorldTime() - lastProgressTick);
        }

    }


}

