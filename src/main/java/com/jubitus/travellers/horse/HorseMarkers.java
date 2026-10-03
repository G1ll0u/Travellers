package com.jubitus.travellers.horse;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Every marker the mod puts on a vanilla horse, kept in its ForgeData (saved with the horse): the
 * mount bond (owner + generation), the loose-horse cleanup timer, and the Stable pool id.
 */
public final class HorseMarkers {

    private static final String BONDED_OWNER = "TravellersMountOwner";
    private static final String MOUNT_GEN = "TravellersMountGen";
    private static final String ORPHANED_TICK = "TravellersMountOrphanedTick";
    private static final String POOL_ID = "TravellersStablePoolId";

    private HorseMarkers() {
    }

    // ---- mount bond ----

    public static boolean isBondedMount(Entity e) {
        return e instanceof EntityHorse && e.getEntityData().hasUniqueId(BONDED_OWNER);
    }

    @Nullable
    public static UUID getBondedOwner(Entity e) {
        if (!(e instanceof EntityHorse)) return null;
        NBTTagCompound data = e.getEntityData();
        return data.hasUniqueId(BONDED_OWNER) ? data.getUniqueId(BONDED_OWNER) : null;
    }

    public static void setBondedOwner(EntityHorse horse, UUID owner) {
        horse.getEntityData().setUniqueId(BONDED_OWNER, owner);
    }

    public static void clearBondedOwner(EntityHorse horse) {
        removeUuid(horse.getEntityData(), BONDED_OWNER);
    }

    /** The bond generation this physical horse was created with. */
    public static int getGeneration(Entity horse) {
        return horse.getEntityData().getInteger(MOUNT_GEN);
    }

    public static void setGeneration(EntityHorse horse, int gen) {
        horse.getEntityData().setInteger(MOUNT_GEN, gen);
    }

    /** Removes every mount-system marker (bond, generation, orphan timer) from a horse's ForgeData. */
    public static void clearMountMarkers(NBTTagCompound forgeData) {
        removeUuid(forgeData, BONDED_OWNER);
        forgeData.removeTag(MOUNT_GEN);
        forgeData.removeTag(ORPHANED_TICK);
    }

    // ---- loose-horse cleanup timer ----

    /** Starts the cleanup grace period of a dead rider's leftover horse. Never on a pool horse. */
    public static void markOrphaned(EntityHorse horse, long now) {
        if (isPoolHorse(horse)) return;
        horse.getEntityData().setLong(ORPHANED_TICK, now);
    }

    public static boolean isOrphaned(Entity e) {
        return e.getEntityData().hasKey(ORPHANED_TICK) && !isPoolHorse(e);
    }

    public static long getOrphanedTick(Entity e) {
        return e.getEntityData().getLong(ORPHANED_TICK);
    }

    public static void clearOrphanTag(Entity e) {
        e.getEntityData().removeTag(ORPHANED_TICK);
    }

    // ---- Stable pool ----

    public static boolean isPoolHorse(Entity e) {
        return e instanceof EntityHorse && e.getEntityData().hasUniqueId(POOL_ID);
    }

    @Nullable
    public static UUID getPoolId(EntityHorse horse) {
        NBTTagCompound data = horse.getEntityData();
        return data.hasUniqueId(POOL_ID) ? data.getUniqueId(POOL_ID) : null;
    }

    public static void setPoolId(EntityHorse horse, UUID poolId) {
        horse.getEntityData().setUniqueId(POOL_ID, poolId);
    }

    public static void clearPoolId(EntityHorse horse) {
        removeUuid(horse.getEntityData(), POOL_ID);
    }

    /** Strips the pool marker from a horse snapshot about to be respawned as something else. */
    public static void clearPoolId(@Nullable NBTTagCompound horseNbt) {
        if (horseNbt == null || !horseNbt.hasKey("ForgeData")) return;
        removeUuid(horseNbt.getCompoundTag("ForgeData"), POOL_ID);
    }

    private static void removeUuid(NBTTagCompound data, String key) {
        data.removeTag(key + "Most");
        data.removeTag(key + "Least");
    }
}
