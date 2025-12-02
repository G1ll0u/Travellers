package com.jubitus.traveller.traveller.pathing;

import com.jubitus.traveller.Travellers;
import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.entityAI.EntityTravellerMerchant;
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

import javax.annotation.Nullable;
import java.util.*;

// TravellerChunkManager.java
// TravellerChunkManager.java
public class TravellerChunkManager implements ForgeChunkManager.LoadingCallback {

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
            }
        }
    }

    private WorldState getOrCreateState(WorldServer ws) {
        return worldStates.computeIfAbsent(ws.provider.getDimension(), dim -> new WorldState(ws));
    }

    // ----------------------------------------------------------------
    // Per-merchant API
    // ----------------------------------------------------------------

    public void onMerchantTick(EntityTravellerMerchant m) {
        if (m.world.isRemote) return;
        if (!TravellersModConfig.merchantChunkloadingEnabled) return;

        WorldServer ws = (WorldServer) m.world;
        WorldState state = getOrCreateState(ws);

        MerchantData data = state.merchants.get(m.getUniqueID());
        if (data == null) {
            data = new MerchantData(m, TravellersModConfig.merchantChunkRadius);
            state.merchants.put(m.getUniqueID(), data);
        }

        data.updateFromEntity(m);
    }

    public void onMerchantRemoved(EntityTravellerMerchant m) {
        if (m.world.isRemote) return;
        WorldServer ws = (WorldServer) m.world;
        WorldState state = getOrCreateState(ws);

        state.merchants.remove(m.getUniqueID());
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
            if (!(ent instanceof EntityTravellerMerchant) || !ent.isEntityAlive()) {
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

    public void untrackMerchant(EntityTravellerMerchant m) {
        if (m.world.isRemote || !(m.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) m.world;

        WorldState state = getOrCreateState(ws);

        if (state.merchants.remove(m.getUniqueID()) != null) {
            if (state.merchants.isEmpty()) {
                // Last merchant in this world is gone: now we truly unload
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

        public MerchantData(EntityTravellerMerchant m, int radius) {
            this.id = m.getUniqueID();
            this.pos = m.getPosition();
            this.radius = radius;
            this.lastDistToTargetSq = computeDistSqToTarget(m);
            this.lastProgressTick = m.world.getTotalWorldTime();
        }

        public void updateFromEntity(EntityTravellerMerchant m) {
            this.pos = m.getPosition();

            double nowD2 = computeDistSqToTarget(m);
            long nowTick = m.world.getTotalWorldTime();

            if (nowD2 < lastDistToTargetSq - 1.0) {
                lastDistToTargetSq = nowD2;
                lastProgressTick = nowTick;
            }
        }

        private double computeDistSqToTarget(EntityTravellerMerchant m) {
            BlockPos tv = m.getTargetVillage();
            if (tv == null) return Double.MAX_VALUE;
            return m.getDistanceSqToCenter(tv);
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


}

