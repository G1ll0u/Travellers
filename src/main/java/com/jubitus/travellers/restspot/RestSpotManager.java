package com.jubitus.travellers.restspot;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.ground.chunk.LoadedChunks;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * World-saved catalog of rest spots. Fed by ChunkReader (one fixed roll per chunk); a lucky chunk
 * waits until its neighbours are loaded, then is judged under a per-tick budget. A chunk judged
 * without a spot is remembered so it is never judged again. Never loads a chunk.
 */
public final class RestSpotManager extends WorldSavedData {

    private static final String DATA_NAME = "TravellersRestSpots";
    /** Lucky chunks looked at per tick, waiting ones included, as a multiple of the scoring budget. */
    private static final int LOOK_FACTOR = 8;

    private final List<RestSpotRecord> spots = new ArrayList<>();
    private final LongOpenHashSet judged = new LongOpenHashSet();
    private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();

    static long lucky;
    static long scored;
    static long kept;

    public RestSpotManager() {
        super(DATA_NAME);
    }

    public RestSpotManager(String name) {
        super(name);
    }

    public static RestSpotManager get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        RestSpotManager data = (RestSpotManager) storage.getOrLoadData(RestSpotManager.class, DATA_NAME);
        if (data == null) {
            data = new RestSpotManager();
            storage.setData(DATA_NAME, data);
        }
        return data;
    }

    public List<RestSpotRecord> getAll() {
        return Collections.unmodifiableList(spots);
    }

    /** Called by ChunkReader for every chunk it reads. */
    public void onChunkRead(WorldServer world, int cx, int cz) {
        if (!rolls(world.getSeed(), cx, cz)) return;
        long key = ChunkPos.asLong(cx, cz);
        if (judged.contains(key)) return;
        if (spotWithinSpacing((cx << 4) + 8, (cz << 4) + 8) != null) return;
        if (pending.add(key)) lucky++;
    }

    static boolean rolls(long seed, int cx, int cz) {
        long h = seed + 0x6A09E667F3BCC908L + cx * 0x9E3779B97F4A7C15L + cz * 0xC2B2AE3D27D4EB4FL;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53 < TravellersConfig.REST_SPOTS.discoveryChancePerChunk;
    }

    public void tick(WorldServer world) {
        if (!TravellersConfig.REST_SPOTS.enabled || pending.isEmpty()) return;
        int budget = TravellersConfig.REST_SPOTS.scoringsPerTick;
        int looks = Math.min(pending.size(), budget * LOOK_FACTOR);
        for (int i = 0; i < looks && budget > 0 && !pending.isEmpty(); i++) {
            long key = pending.removeFirstLong();
            int cx = (int) key, cz = (int) (key >>> 32);
            if (LoadedChunks.peek(world, cx, cz) == null) continue; // read again when it comes back
            if (!RestSpotScorer.isReady(world, cx, cz)) {
                pending.add(key); // to the back of the line
                continue;
            }
            budget--;
            judge(world, cx, cz, key);
        }
    }

    private void judge(WorldServer world, int cx, int cz, long key) {
        scored++;
        judged.add(key);
        RestSpotScorer.Verdict v = RestSpotScorer.evaluate(world, cx, cz);
        if (v.refusal == null && spotWithinSpacing(v.spot.center.getX(), v.spot.center.getZ()) == null) {
            spots.add(v.spot);
            kept++;
        }
        markDirty();
    }

    /** Debug: judges this chunk now, whatever its roll, and tells why it would be kept or refused. Keeps nothing. */
    public String judgeForDebug(WorldServer world, int cx, int cz) {
        if (!RestSpotScorer.isReady(world, cx, cz)) return "Neighbour chunks or terrain grid not ready, try again in a moment.";
        RestSpotScorer.Verdict v = RestSpotScorer.evaluate(world, cx, cz);
        String verdict = v.refusal == null ? "would be KEPT" : "refused: " + v.refusal;
        RestSpotRecord other = v.spot == null ? null : spotWithinSpacing(v.spot.center.getX(), v.spot.center.getZ());
        if (v.refusal == null && other != null) {
            verdict = other.center.equals(v.spot.center)
                    ? "already a rest spot (" + other.id.toString().substring(0, 8) + ")"
                    : "refused: " + (int) Math.sqrt(other.center.distanceSq(v.spot.center)) + " blocks from rest spot "
                    + other.id.toString().substring(0, 8) + " at " + other.center;
        }
        return v.spot == null ? verdict : verdict + "\n" + v.spot.describe() + "\nat " + v.spot.center;
    }

    /** Debug: forgets every spot and judgement, then queues the loaded chunks again (after a config change). */
    public int rescan(WorldServer world) {
        spots.clear();
        judged.clear();
        pending.clear();
        lucky = scored = kept = 0;
        markDirty();
        for (net.minecraft.world.chunk.Chunk chunk : world.getChunkProvider().getLoadedChunks()) {
            onChunkRead(world, chunk.x, chunk.z);
        }
        return pending.size();
    }

    @Nullable
    private RestSpotRecord spotWithinSpacing(int x, int z) {
        long min = TravellersConfig.REST_SPOTS.minSpacing;
        for (RestSpotRecord s : spots) {
            long dx = s.center.getX() - x, dz = s.center.getZ() - z;
            if (dx * dx + dz * dz < min * min) return s;
        }
        return null;
    }

    public String stats() {
        return String.format("Rest spots: %d in this world, %d chunks judged, %d waiting. Since start: lucky %d, judged %d, kept %d.",
                spots.size(), judged.size(), pending.size(), lucky, scored, kept);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        spots.clear();
        NBTTagList list = tag.getTagList("Spots", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) spots.add(RestSpotRecord.readFromNBT(list.getCompoundTagAt(i)));
        judged.clear();
        int[] xz = tag.getIntArray("Judged");
        for (int i = 0; i + 1 < xz.length; i += 2) judged.add(ChunkPos.asLong(xz[i], xz[i + 1]));
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (RestSpotRecord s : spots) list.appendTag(s.writeToNBT());
        tag.setTag("Spots", list);
        int[] xz = new int[judged.size() * 2];
        int i = 0;
        for (LongIterator it = judged.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            xz[i++] = (int) key;
            xz[i++] = (int) (key >>> 32);
        }
        tag.setIntArray("Judged", xz);
        return tag;
    }
}
