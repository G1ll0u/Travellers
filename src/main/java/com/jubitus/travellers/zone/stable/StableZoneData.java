package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.zone.ZoneBedData;
import com.jubitus.travellers.zone.ZoneTypeData;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable-only extension state: the horse pool, the per-palfrenier bed assignment (see
 * {@link ZoneBedData}) and a light chest-contents mirror ({@link StableChestMirror}) that lets the
 * stable simulation run identically loaded or offscreen. See {@link StableHorseEntry} for why a pool
 * horse needs no position/velocity - the pool itself is the entire virtual state. */
public final class StableZoneData implements ZoneTypeData, ZoneBedData {

    public final List<StableHorseEntry> pool = new ArrayList<>();

    /** worker UUID -> HEAD block of its assigned bed. A palfrenier with no entry here has no bed
     * and does only ambient "social" behaviour, no labour (loaded and virtual alike). */
    public final Map<UUID, BlockPos> bedByWorker = new HashMap<>();

    public final StableChestMirror chestMirror = new StableChestMirror();

    /** Elapsed-time anchor for the breeding check ({@link StableWork}) - mirrors
     * {@link StableHorseEntry#lastTameProgressTick} / {@link StableHorseEntry#lastAgeSyncTick} so
     * breeding fires on real elapsed time, not a fragile {@code now % interval} test that silently
     * halves or skips the rate when the tick intervals aren't divisible. */
    public long lastBreedTick;

    @Override
    public Map<UUID, BlockPos> bedByWorker() {
        return bedByWorker;
    }

    /**
     * The one path that turns a captured horse NBT snapshot into a dormant pool entry: strips the
     * identity bits that must not survive a later respawn ({@code UUID}, {@code Leash}, the pool
     * tag), reads the horse's tamed/saddled/temper/age off one scratch entity, and adds the entry.
     * Used by every "a horse enters the pool from outside" site (dead-rider release, palfrenier
     * surrendering its own mount, a failed Get-Equipment bond returning stock).
     */
    public StableHorseEntry addCapturedHorse(WorldServer world, NBTTagCompound nbt,
                                             StableHorseEntry.Origin origin, long now) {
        NBTTagCompound stored = nbt.copy();
        StableCoordinator.sanitizeSnapshot(stored);
        HorseMarkers.clearPoolId(stored);

        EntityHorse scratch = new EntityHorse(world);
        scratch.readFromNBT(stored);

        StableHorseEntry entry = new StableHorseEntry();
        entry.tamed = scratch.isTame();
        entry.saddled = scratch.isHorseSaddled();
        entry.temper = scratch.getTemper();
        entry.growingAge = scratch.getGrowingAge();
        entry.origin = origin;
        entry.reservedAtTick = now;
        entry.lastAgeSyncTick = now;
        entry.lastTameProgressTick = now;
        entry.horseNbt = stored;
        pool.add(entry);
        return entry;
    }

    public StableHorseEntry findByPoolId(UUID poolId) {
        for (StableHorseEntry e : pool) {
            if (e.poolId.equals(poolId)) return e;
        }
        return null;
    }

    public StableHorseEntry findByLiveEntity(UUID entityUUID) {
        for (StableHorseEntry e : pool) {
            if (entityUUID.equals(e.liveEntityUUID)) return e;
        }
        return null;
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (StableHorseEntry e : pool) {
            NBTTagCompound t = new NBTTagCompound();
            e.writeToNBT(t);
            list.appendTag(t);
        }
        tag.setTag("Pool", list);

        ZoneBedData.writeBeds(tag, bedByWorker);
        chestMirror.writeToNBT(tag);
        tag.setLong("LastBreedTick", lastBreedTick);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        pool.clear();
        NBTTagList list = tag.getTagList("Pool", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            pool.add(StableHorseEntry.readFromNBT(list.getCompoundTagAt(i)));
        }

        ZoneBedData.readBeds(tag, bedByWorker);
        chestMirror.readFromNBT(tag);
        lastBreedTick = tag.getLong("LastBreedTick");
    }
}
