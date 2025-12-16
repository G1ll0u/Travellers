package com.jubitus.travellers.utils.world.pathing.chunkmanager;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.millenaire.common.utilities.MillLog;

import javax.annotation.Nullable;
import java.util.*;

// TravellerChunkManager.java
// TravellerChunkManager.java
public class TravellerChunkManager implements ForgeChunkManager.LoadingCallback {
    private static final int HARD_STUCK_TICKS = 20 * 60 * 5; // 5 minutes
    private static final String NBT_FORCED_CHUNKS = "ForcedChunks";

    public static final TravellerChunkManager INSTANCE = new TravellerChunkManager();
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
    // Dimension -> state
    private final Map<Integer, WorldState> worldStates = new HashMap<>();

    private TravellerChunkManager() {
    }

    public static void registerCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(Travellers.INSTANCE, INSTANCE);
    }
    @SubscribeEvent
    public void onWorldTick(net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent event) {
        if (event.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        if (!(event.world instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.world;
        WorldState state = getOrCreateState(ws);

        // Always recompute; recomputeForWorld will clean up if there are no merchants
        recomputeForWorld(state);
    }
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.getWorld();
        int dim = ws.provider.getDimension();

        // Just drop our in-memory state; do NOT release the ticket.
        worldStates.remove(dim);
    }
    // Called by Forge on world load to restore tickets
    @Override
    public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
        if (world.isRemote) return;
        WorldServer ws = (WorldServer) world;
        WorldState state = getOrCreateState(ws);

        System.out.println("[Travellers] ticketsLoaded: dim=" + ws.provider.getDimension()
                + " tickets=" + tickets.size());

        for (ForgeChunkManager.Ticket t : tickets) {
            if (state.ticket == null) {
                state.ticket = t;
            } else {
                ForgeChunkManager.releaseTicket(t); // discard extra
            }
        }

        if (state.ticket != null) {
            NBTTagCompound tag = state.ticket.getModData();
            NBTTagList list = tag.getTagList(NBT_FORCED_CHUNKS, 10); // 10 = compound

            System.out.println("[Travellers] Restored ticket NBT with " + list.tagCount() + " chunks");

            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound c = list.getCompoundTagAt(i);
                ChunkPos pos = new ChunkPos(c.getInteger("x"), c.getInteger("z"));

                state.forced.add(pos);
                ForgeChunkManager.forceChunk(state.ticket, pos);

                // 🔸 NEW: actually load the chunk so travellers in it exist again
                // and can tick / call onChunkloaderTick.
                ws.getChunkProvider().loadChunk(pos.x, pos.z);
                // or: ws.getChunkFromChunkCoords(pos.x, pos.z);
            }
        }
    }


    private WorldState getOrCreateState(WorldServer ws) {
        return worldStates.computeIfAbsent(ws.provider.getDimension(), dim -> new WorldState(ws));
    }

    // ----------------------------------------------------------------
    // Per-merchant API
    // ----------------------------------------------------------------

    // Called each tick by any travellers that should chunkload (merchant, worker, etc.)
    public void onChunkloaderTick(EntityTraveller traveller, int radius) {
        if (traveller.world.isRemote) return;
        if (!TravellersModConfig.merchantChunkloadingEnabled) return; // rename config later if you want

        WorldServer ws = (WorldServer) traveller.world;
        WorldState state = getOrCreateState(ws);

        MerchantData data = state.merchants.get(traveller.getUniqueID());
        if (data == null) {
            data = new MerchantData(traveller, radius);
            state.merchants.put(traveller.getUniqueID(), data);
        } else {
            data.radius = radius; // in case radius can change (config reload, etc.)
            data.updateFromEntity(traveller);
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

    private void recomputeForWorld(WorldState state) {
        // prune dead merchants
        Iterator<Map.Entry<UUID, MerchantData>> it = state.merchants.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MerchantData> e = it.next();
            Entity ent = state.world.getEntityFromUuid(e.getKey());
            if (!(ent instanceof EntityTraveller) || !ent.isEntityAlive()) {
                it.remove();
            }
        }

        if (state.merchants.isEmpty()) {
            // Nothing to schedule, but keep any existing ticket/forced chunks.
            // This is important so tickets survive across restarts.
            return;
        }

        ensureTicket(state);

        // 1) Decide which merchants are allowed to chunkload this tick
        List<MerchantData> active = new ArrayList<>(state.merchants.values());

        // still keep this guard in case map was emptied between calls
        if (active.isEmpty()) {
            return;
        }
        // --------------------------------------------------------------------
        // 🔥 GLOBAL HARD-UNSTUCK CHECK (correct place, 'state' is valid here)
        // --------------------------------------------------------------------
//        for (MerchantData md : active) {
//            int idle = md.ticksSinceLastProgress(state.world);
//
//            if (idle > HARD_STUCK_TICKS) {
//                Entity e = state.world.getEntityFromUuid(md.id);
//                if (e instanceof EntityTraveller) {
//                    hardUnstuckTeleport((EntityTraveller) e, md, state.world);
//                }
//            }
//        }
        // --- NEW: global targetVillage fallback ---
        for (MerchantData md : state.merchants.values()) {
            Entity ent = state.world.getEntityFromUuid(md.id);
            if (!(ent instanceof EntityTraveller)) continue;

            EntityTraveller t = (EntityTraveller) ent;
            TravelCommon tc = t.getTravelCommon();
            if (tc == null) continue;

            // Skip if route-locked (jobs, escort, etc.)
            if (t.isRouteLocked()) continue;

            // For workers: only when truly idle for roaming
            if (t instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) t;
                if (!w.isIdleForRoaming()) continue;
            }

            // If they somehow lost their target + route, replan
            if (tc.getTargetVillage() == null
                    && tc.routeIndex < 0
                    && !tc.getVillagesCached().isEmpty()) {

                tc.ensureRoutePlanned();
            }
        }
        active.sort((a, b) -> {
            int pa = a.chunkloadingPriority(state.world);
            int pb = b.chunkloadingPriority(state.world);
            return Integer.compare(pb, pa); // higher priority first
        });

        int allowedMerchants = Math.min(
                TravellersModConfig.merchantMaxChunkloadingMerchants,
                active.size()
        );

        Set<ChunkPos> desired = new HashSet<>();

        for (int i = 0; i < active.size(); i++) {
            MerchantData md = active.get(i);
            md.chunkloading = (i < allowedMerchants);

            if (!md.chunkloading) continue;

            addMerchantRadiusChunks(md, desired);

            if (desired.size() >= TravellersModConfig.merchantMaxForcedChunks) {
                break;
            }
        }

        if (desired.isEmpty()) {
            // If we have merchants but no desired chunks (config limits, etc.),
            // DO NOT wipe existing forced chunks. This keeps at least some area
            // around a merchant loaded so the ticket always has chunks to persist.
            return;
        }

        Set<ChunkPos> toUnforce = new HashSet<>(state.forced);
        toUnforce.removeAll(desired);

        Set<ChunkPos> toForce = new HashSet<>(desired);
        toForce.removeAll(state.forced);

        for (ChunkPos cp : toUnforce) {
            ForgeChunkManager.unforceChunk(state.ticket, cp);
        }
        for (ChunkPos cp : toForce) {
            ForgeChunkManager.forceChunk(state.ticket, cp);
        }

        state.forced.clear();
        state.forced.addAll(desired);
// Persist this to the ticket so Forge can save it
        saveForcedToTicket(state);
    }


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


    private void addMerchantRadiusChunks(MerchantData md, Set<ChunkPos> out) {
        int r = md.radius;
        ChunkPos center = new ChunkPos(md.pos);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                out.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
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

    @Nullable
    public MerchantData getMerchantData(World world, UUID id) {
        if (!(world instanceof WorldServer)) return null;
        WorldState state = worldStates.get(((WorldServer) world).provider.getDimension());
        return state == null ? null : state.merchants.get(id);
    }

    @Nullable
    public WorldState getWorldState(World world) {
        if (!(world instanceof WorldServer)) return null;
        return worldStates.get(((WorldServer) world).provider.getDimension());
    }

    // ----------------------------------------------------
    // Inner classes
    // ----------------------------------------------------

    public static class WorldState {
        final WorldServer world;
        ForgeChunkManager.Ticket ticket;
        public final Set<ChunkPos> forced = new HashSet<>();
        final Map<UUID, MerchantData> merchants = new HashMap<>();

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

        @Nullable
        private BlockPos lastTargetVillage;

        public MerchantData(EntityTraveller t, int radius) {
            this.id = t.getUniqueID();
            this.pos = t.getPosition();
            this.radius = radius;

            BlockPos tv = t.getTargetVillage();
            this.lastTargetVillage = (tv == null ? null : tv.toImmutable());
            this.lastDistToTargetSq = computeDistSqToTarget(t);
            this.lastProgressTick = t.world.getTotalWorldTime();
        }


        public void updateFromEntity(EntityTraveller t) {
            this.pos = t.getPosition();

            BlockPos tv = t.getTargetVillage();
            long nowTick = t.world.getTotalWorldTime();

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


        public int ticksSinceLastProgress(WorldServer world) {
            return (int) (world.getTotalWorldTime() - lastProgressTick);
        }

        public int chunkloadingPriority(WorldServer world) {
            int idleTicks = ticksSinceLastProgress(world);
            if (idleTicks < 200) return 2;
            if (idleTicks < 1200) return 1;
            return 0;
        }
    }
    @SubscribeEvent
    public void onCanEntityUpdate(net.minecraftforge.event.entity.EntityEvent.CanUpdate event) {
        Entity e = event.getEntity();
        if (e instanceof EntityTravellerWorker || e instanceof EntityTravellerMerchant) {
            event.setCanUpdate(true);
        }
    }
    private void hardUnstuckTeleport(EntityTraveller t, MerchantData md, WorldServer world) {
        BlockPos target = t.getTargetVillage();

        MillLog.major(t, "[Travellers] HARD-UNSTUCK triggered. "
                + "idleTicks=" + md.ticksSinceLastProgress(world)
                + " lastDistSq=" + md.lastDistToTargetSq
                + " currentDistSq=" + (target == null ? -1 : t.getDistanceSqToCenter(target))
                + " target=" + target
                + " routeIndex=" + (t.getTravelCommon() != null ? t.getTravelCommon().routeIndex : -1)
                + " hasPath=" + !t.getNavigator().noPath()
                + " pos=" + t.posX + "," + t.posY + "," + t.posZ);

        if (target == null) {
            MillLog.major(t, "[Travellers] HARD-UNSTUCK aborted: targetVillage is null.");
            return;
        }

        TravelCommon tc = t.getTravelCommon();
        BlockPos stand = tc.topStandable(target);

        if (stand == null) {
            MillLog.major(t, "[Travellers] topStandable returned null, falling back to Y>=64.");
            stand = new BlockPos(target.getX(), Math.max(64, target.getY()), target.getZ());
        }

        t.setPositionAndUpdate(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        t.getNavigator().clearPath();
        tc.setTravelRecoverTicks(0);

        md.lastDistToTargetSq = t.getDistanceSqToCenter(target);
        md.lastProgressTick = world.getTotalWorldTime();

        MillLog.major(t, "[Travellers] HARD-UNSTUCK -> Teleported to " + stand);
    }

}

