package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

/**
 * A traveller/raider (or non-hired worker) has no Stable or groupmate to hand its horse to when it
 * dies, so {@link TravellerMountSupport#releaseMountOnRiderDeath} falls back to leaving the horse as
 * an ordinary loose, tamed horse - which, per vanilla ({@code EntityAnimal.canDespawn()} always
 * returns {@code false}), never despawns on its own. Left alone these accumulate forever near
 * well-travelled routes and battle sites.
 *
 * <p>This class is the cleanup: a horse marked here by {@link TravellerMountSupport} is removed once
 * both (a) a grace period has passed - long enough for a nearby player to notice and tame it for
 * themselves - and (b) no player is close enough to ever see it happen. Two mechanisms provide (b)
 * without ever force-loading a chunk:
 * <ul>
 *   <li>{@link #tick} - a throttled sweep of currently loaded horses, gated on distance to the
 *   nearest player. Handles a horse whose chunk stays loaded indefinitely (a player camped nearby).</li>
 *   <li>A hook in {@code ChunkUnloadVirtualizeHandler} - when a tagged horse's own chunk unloads
 *   (nobody is renders it any more, that is what unloading means) and the grace period has already
 *   elapsed, it is removed right there instead of being saved into the chunk file for good.</li>
 * </ul>
 *
 * <p>The tag is cleared the moment a player actually interacts with the horse (see
 * {@link HorseTheftGuard}) - claimed, no longer a cleanup candidate, ever.
 */
public final class OrphanMountCleanup {

    private OrphanMountCleanup() {
    }

    /** How often (in ticks) the loaded-horse sweep runs. Cheap per call (a few dozen horses at most),
     * so this only needs to be "not every tick", not aggressively throttled. */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /** Whether this orphaned horse has sat unclaimed long enough to be removed - distance to any
     * player is the caller's separate responsibility (see the two call sites in the class doc). */
    private static boolean graceElapsed(EntityHorse horse, long now) {
        if (!HorseMarkers.isOrphaned(horse)) return false;
        return now - HorseMarkers.getOrphanedTick(horse) >= TravellersConfig.MOUNTS.looseMountCleanupGraceTicks;
    }

    /**
     * Called from {@code ChunkUnloadVirtualizeHandler} for an orphan-tagged horse found in a chunk
     * that is unloading right now - the one moment removing it is guaranteed invisible even if a
     * player is nearby enough to keep other chunks loaded, since this specific chunk (and everything
     * that was in it) is leaving the client's view at this exact instant regardless.
     */
    public static boolean isEligibleAtChunkUnload(EntityHorse horse, long now) {
        return graceElapsed(horse, now);
    }

    /** Periodic sweep for orphaned horses whose chunk never unloads (a player camped nearby) -
     * removed only once nobody is close enough to notice. */
    public static void tick(WorldServer world, long now) {
        if (now % SWEEP_INTERVAL_TICKS != 0) return;

        double minDist = TravellersConfig.MOUNTS.looseMountCleanupMinPlayerDistance;
        for (Entity e : new java.util.ArrayList<>(world.loadedEntityList)) {
            if (e.isDead || !(e instanceof EntityHorse)) continue;
            EntityHorse horse = (EntityHorse) e;
            if (!graceElapsed(horse, now)) continue;
            if (world.getClosestPlayer(horse.posX, horse.posY, horse.posZ, minDist, false) != null) continue;
            horse.setDead();
        }
    }
}
