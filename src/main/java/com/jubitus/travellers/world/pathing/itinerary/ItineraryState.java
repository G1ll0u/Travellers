package com.jubitus.travellers.world.pathing.itinerary;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One unit's itinerary + cursor, owned by an {@code EntityTraveller} or a {@code VirtualTravellerRecord}
 * and handed over as-is at virtualize/materialize, bridges still waiting for a terrain plan included.
 */
public final class ItineraryState {

    // Bump when the way itineraries are built changes enough that saved ones should be rebuilt.
    private static final int FORMAT_VERSION = 6;

    // An itinerary counts as "for" a destination within this many blocks of its end (XZ).
    private static final int SAME_DEST_BLOCKS = 16;

    @Nullable
    private Itinerary itinerary;
    public final ItineraryCursor cursor = new ItineraryCursor();
    // Debug-only (path test): a hand-marked itinerary nothing may rebuild or replace.
    private boolean forced;
    // Bumped whenever the itinerary is replaced, so consumers can re-baseline progress measures.
    private int version;
    public long planTick;
    // Whether this unit may use roads; decided at build, read when its bridges get planned.
    boolean useRoads;

    // Gates that proved unreachable on this leg (transient): a rebuild picks another one.
    public final java.util.Set<Long> excludedGates = new java.util.HashSet<>();

    @Nullable
    List<ItineraryAssembly.Bridge> backlog;
    long nextBacklogTry;

    @Nullable
    public Itinerary get() {
        return itinerary;
    }

    public boolean isForced() {
        return forced;
    }

    public int version() {
        return version;
    }

    public boolean hasBacklog() {
        return backlog != null && !backlog.isEmpty();
    }

    public boolean isFor(@Nullable BlockPos dest) {
        if (itinerary == null || dest == null) return false;
        long dx = itinerary.dest().getX() - dest.getX();
        long dz = itinerary.dest().getZ() - dest.getZ();
        return dx * dx + dz * dz <= (long) SAME_DEST_BLOCKS * SAME_DEST_BLOCKS;
    }

    /** Installs a freshly built itinerary; the cursor is placed from scratch. */
    public void set(Itinerary it, double x, double z, @Nullable List<ItineraryAssembly.Bridge> bridges, long now) {
        itinerary = it;
        forced = false;
        backlog = bridges == null || bridges.isEmpty() ? null : new java.util.ArrayList<>(bridges);
        nextBacklogTry = now;
        planTick = now;
        cursor.reset(it, x, z);
        version++;
    }

    public void setForced(Itinerary it, double x, double z) {
        set(it, x, z, null, 0L);
        forced = true;
    }

    /** Swaps in an upgraded version of the same leg (a spliced bridge); the cursor is re-projected. */
    void replace(Itinerary it, double x, double z, long now) {
        itinerary = it;
        planTick = now;
        cursor.reset(it, x, z);
        version++;
    }

    public void clear() {
        if (itinerary != null) version++;
        itinerary = null;
        forced = false;
        backlog = null;
        planTick = 0L;
    }

    public void writeNbt(NBTTagCompound parent, String key) {
        if (itinerary == null || forced) return;
        NBTTagCompound tag = itinerary.toNbt();
        cursor.writeNbt(tag);
        tag.setLong("PT", planTick);
        tag.setInteger("IV", FORMAT_VERSION);
        tag.setBoolean("R", useRoads);
        if (hasBacklog()) {
            int[] b = new int[backlog.size() * 6];
            for (int i = 0; i < backlog.size(); i++) {
                ItineraryAssembly.Bridge br = backlog.get(i);
                b[i * 6] = br.start.getX();
                b[i * 6 + 1] = br.start.getY();
                b[i * 6 + 2] = br.start.getZ();
                b[i * 6 + 3] = br.end.getX();
                b[i * 6 + 4] = br.end.getY();
                b[i * 6 + 5] = br.end.getZ();
            }
            tag.setIntArray("B", b);
        }
        parent.setTag(key, tag);
    }

    public void readNbt(NBTTagCompound parent, String key) {
        clear();
        if (!parent.hasKey(key, 10)) return;
        NBTTagCompound tag = parent.getCompoundTag(key);
        if (tag.getInteger("IV") != FORMAT_VERSION) return; // built the old way: rebuilt on next use
        Itinerary it = Itinerary.fromNbt(tag);
        if (it == null) return;
        itinerary = it;
        cursor.readNbt(tag, it);
        planTick = tag.getLong("PT");
        useRoads = tag.getBoolean("R");
        int[] b = tag.getIntArray("B");
        if (b.length >= 6 && b.length % 6 == 0) {
            backlog = new java.util.ArrayList<>();
            for (int i = 0; i < b.length; i += 6) {
                backlog.add(new ItineraryAssembly.Bridge(new BlockPos(b[i], b[i + 1], b[i + 2]),
                        new BlockPos(b[i + 3], b[i + 4], b[i + 5])));
            }
        }
        version++;
    }
}
