package com.jubitus.travellers.zone.stable;

import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One horse tracked by a Stable's pool. Either {@link #liveEntityUUID} links the one real
 * {@code EntityHorse} standing for it (the Stable is live), or it is null and {@link #horseNbt} is
 * the whole horse, position included (the Stable is dormant) - see {@code StableCoordinator}.
 */
public final class StableHorseEntry {

    public enum Origin {
        WILD_CLAIMED,
        BRED,
        RELEASED_FROM_DEAD_RIDER,
        RECLAIMED_BY_OWNER,
        /** A palfrenier's own bonded mount, surrendered into the pool it now tends the instant it
         * arrives on Stable duty - see {@code StableHandTask}'s arrival check. */
        WORKER_OWN_MOUNT
    }

    public UUID poolId;
    @Nullable public UUID liveEntityUUID;
    public boolean tamed;
    public boolean saddled;
    /** Taming progress, 0..{@code AbstractHorse.getMaxTemper()} (100). Owned by {@link StableWork}
     * so it survives the horse's chunk unloading while the offscreen sim keeps advancing it -
     * {@code StableCoordinator.reconcilePhysical} pushes it back onto the (possibly stale) live
     * entity on reload rather than reading the frozen entity's own value. */
    public int temper;
    public int growingAge;
    public long breedingCooldownUntil;
    public long reservedAtTick;
    public Origin origin = Origin.WILD_CLAIMED;
    public NBTTagCompound horseNbt;
    /** Elapsed-time anchor for taming progress, used by {@link StableWork} (which runs the same
     * whether the Stable is loaded or offscreen) - mirrors {@code CampOccupant.lastProgressTick} so
     * switching between loaded and virtual never causes a time-jump either way. */
    public long lastTameProgressTick;
    /** Elapsed-time anchor for offline growth ({@link StableCoordinator#advanceVirtualAging}) - kept
     * fresh even while the horse is live (see {@code StableCoordinator.reconcilePhysical}) so a horse
     * that goes dormant never "catches up" on time it already aged through normally while loaded. */
    public long lastAgeSyncTick;

    public StableHorseEntry() {
        this.poolId = UUID.randomUUID();
    }

    public void writeToNBT(NBTTagCompound tag) {
        tag.setUniqueId("PoolId", poolId);
        if (liveEntityUUID != null) tag.setUniqueId("LiveEntity", liveEntityUUID);
        tag.setBoolean("Tamed", tamed);
        tag.setBoolean("Saddled", saddled);
        tag.setInteger("Temper", temper);
        tag.setInteger("GrowingAge", growingAge);
        tag.setLong("BreedingCooldownUntil", breedingCooldownUntil);
        tag.setLong("ReservedAtTick", reservedAtTick);
        tag.setString("Origin", origin.name());
        tag.setLong("LastTameProgressTick", lastTameProgressTick);
        tag.setLong("LastAgeSyncTick", lastAgeSyncTick);
        if (horseNbt != null) tag.setTag("HorseNbt", horseNbt);
    }

    public static StableHorseEntry readFromNBT(NBTTagCompound tag) {
        StableHorseEntry e = new StableHorseEntry();
        e.poolId = tag.getUniqueId("PoolId");
        e.liveEntityUUID = tag.hasKey("LiveEntity") ? tag.getUniqueId("LiveEntity") : null;
        e.tamed = tag.getBoolean("Tamed");
        e.saddled = tag.getBoolean("Saddled");
        e.temper = tag.getInteger("Temper");
        e.growingAge = tag.getInteger("GrowingAge");
        e.breedingCooldownUntil = tag.getLong("BreedingCooldownUntil");
        e.reservedAtTick = tag.getLong("ReservedAtTick");
        e.lastTameProgressTick = tag.getLong("LastTameProgressTick");
        e.lastAgeSyncTick = tag.getLong("LastAgeSyncTick");
        try {
            e.origin = Origin.valueOf(tag.getString("Origin"));
        } catch (IllegalArgumentException ignored) {
            e.origin = Origin.WILD_CLAIMED;
        }
        e.horseNbt = tag.hasKey("HorseNbt") ? tag.getCompoundTag("HorseNbt") : null;
        return e;
    }
}
