package com.jubitus.traveller.traveller.camp;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;
import java.util.UUID;

public class CampfireManager extends WorldSavedData {
    public static final String ID = "travellers_campfires_v1";
    private final java.util.List<Entry> list = new java.util.ArrayList<>();

    public CampfireManager() {
        super(ID);
    }

    public CampfireManager(String id) {
        super(id);
    }

    public static CampfireManager get(World world) {
        WorldServer ws = (WorldServer) world;
        MapStorage storage = ws.getPerWorldStorage();
        CampfireManager mgr = (CampfireManager) storage.getOrLoadData(CampfireManager.class, ID);
        if (mgr == null) {
            mgr = new CampfireManager();
            storage.setData(ID, mgr);
        }
        return mgr;
    }

    public void addOrRefresh(BlockPos pos, int dim, @Nullable UUID owner, long expireAt, int nearRadius) {
        for (Entry e : list) {
            if (e.dimension == dim && e.pos.equals(pos)) {
                if (owner != null) {
                    e.owner = owner;  // keep existing non-null owner if caller passes null
                }
                e.expireAt = expireAt;
                e.nearRadius = nearRadius;
                markDirty();
                return;
            }
        }
        Entry e = new Entry();
        e.pos = pos.toImmutable();
        e.dimension = dim;
        e.owner = owner;
        e.expireAt = expireAt;
        e.nearRadius = nearRadius;
        list.add(e);
        markDirty();
    }

    public void remove(BlockPos pos, int dim) {
        if (list.removeIf(e -> e.dimension == dim && e.pos.equals(pos))) markDirty();
    }

    /**
     * Called from a server tick handler (see below).
     */
    public void tickAll(MinecraftServer server) {
        java.util.Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            Entry e = it.next();

            WorldServer ws = server.getWorld(e.dimension);
            if (ws == null) {
                it.remove();
                markDirty();
                continue;
            }

            long now = ws.getTotalWorldTime();

            // If chunk not loaded, skip; we'll reevaluate when it's loaded.
            if (!ws.getChunkProvider().chunkExists(e.pos.getX() >> 4, e.pos.getZ() >> 4)) {
                continue;
            }

            boolean expired = now >= e.expireAt;

            // If block is gone or not a lit campfire, drop entry (and don't touch the world).
            if (!isMillenaireFireLit(ws, e.pos)) {
                it.remove();
                markDirty();
                continue;
            }

            // NEW: any traveller near, not just the owner
            boolean anyoneNear = !ws.getEntities(EntityTraveller.class,
                            ent -> ent != null && ent.isEntityAlive()
                                    && ent.getPosition().distanceSq(e.pos) <= (e.nearRadius * e.nearRadius))
                    .isEmpty();

            // If nobody is near and the TTL is up, remove the fire.
            if (!anyoneNear && expired) {
                Block fire = CampfireBlock.get();
                if (fire != null) {
                    IBlockState st = ws.getBlockState(e.pos);
                    if (st.getBlock() == fire) {
                        ws.setBlockToAir(e.pos);
                    }
                }
                it.remove();
                markDirty();
                continue;
            }

            // If someone *is* near but the TTL is already in the past
            // (e.g. we restarted after a disconnect), lazily extend it.
            if (anyoneNear && expired) {
                e.expireAt = now + (20 * 60); // keep it alive for another ~60s
                markDirty();
            }
        }
    }

    /**
     * True if the block at pos is Millénaire's fire pit and it is lit.
     * Safe to call only when the chunk is loaded.
     */
    private boolean isMillenaireFireLit(WorldServer ws, BlockPos pos) {
        Block fire = CampfireBlock.get();
        if (fire == null) return false;
        IBlockState st = ws.getBlockState(pos);
        return st.getBlock() == fire && CampfireBlock.isLit(st);
    }

    // ---- (de)serialization ----
    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        list.clear();
        NBTTagList arr = nbt.getTagList("L", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < arr.tagCount(); i++) {
            NBTTagCompound t = arr.getCompoundTagAt(i);
            Entry e = new Entry();
            e.pos = new BlockPos(t.getInteger("x"), t.getInteger("y"), t.getInteger("z"));
            e.dimension = t.getInteger("dim");
            e.expireAt = t.getLong("exp");
            e.nearRadius = t.getInteger("r");
            if (t.hasUniqueId("owner")) e.owner = t.getUniqueId("owner");
            list.add(e);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList arr = new NBTTagList();
        for (Entry e : list) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("x", e.pos.getX());
            t.setInteger("y", e.pos.getY());
            t.setInteger("z", e.pos.getZ());
            t.setInteger("dim", e.dimension);
            t.setLong("exp", e.expireAt);
            t.setInteger("r", e.nearRadius);
            if (e.owner != null) t.setUniqueId("owner", e.owner);
            arr.appendTag(t);
        }
        nbt.setTag("L", arr);
        return nbt;
    }

    public boolean isManaged(int dim, BlockPos pos) {
        for (Entry e : list) {
            if (e.dimension == dim && e.pos.equals(pos)) return true;
        }
        return false;
    }

    public void removeIfManaged(int dim, BlockPos pos) {
        if (list.removeIf(e -> e.dimension == dim && e.pos.equals(pos))) markDirty();
    }

    @Nullable
    public BlockPos nearestActive(World world, BlockPos around, int radius,
                                  @Nullable UUID excludeOwner, boolean verifyLit) {
        if (!(world instanceof WorldServer)) return null;
        WorldServer ws = (WorldServer) world;
        int dim = ws.provider.getDimension();
        long now = ws.getTotalWorldTime();

        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        // iterate your existing list
        java.util.Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            Entry e = it.next();

            if (e.dimension != dim) continue;
            if (excludeOwner != null && excludeOwner.equals(e.owner)) continue;

            // TTL check first
            if (now >= e.expireAt) {
                // stale → drop it eagerly
                it.remove();
                markDirty();
                continue;
            }

            // Optional verification against the actual world state
            if (verifyLit) {
                if (!ws.getChunkProvider().chunkExists(e.pos.getX() >> 4, e.pos.getZ() >> 4)) {
                    // In "verify" mode we skip unloaded chunks; leave it in list for later.
                    continue;
                }
                if (!isMillenaireFireLit(ws, e.pos)) {
                    // Not really there anymore → prune it now.
                    it.remove();
                    markDirty();
                    continue;
                }
            }

            double d2 = around.distanceSq(e.pos);
            if (d2 <= (long) radius * (long) radius && d2 < bestD2) {
                bestD2 = d2;
                best = e.pos;
            }
        }
        return best;
    }

    public void extinguishAllForWorld(WorldServer ws) {
        Block fire = CampfireBlock.get();
        if (fire == null) {
            list.clear();
            markDirty();
            return;
        }

        int dim = ws.provider.getDimension();
        java.util.Iterator<Entry> it = list.iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.dimension != dim) continue;

            IBlockState st = ws.getBlockState(e.pos);
            if (st.getBlock() == fire && CampfireBlock.isLit(st)) {
                ws.setBlockToAir(e.pos); // or set to unlit state if you prefer
            }
            it.remove();
        }

        markDirty();
    }

    public static class Entry {
        public BlockPos pos;
        public int dimension;
        public UUID owner;          // traveller UUID (optional)
        public long expireAt;       // world.getTotalWorldTime() tick deadline
        public int nearRadius;      // blocks for proximity cleanup
    }
}


