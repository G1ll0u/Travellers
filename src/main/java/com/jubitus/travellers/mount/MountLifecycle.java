package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseAI;
import com.jubitus.travellers.horse.HorseSnapshots;
import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import javax.annotation.Nullable;
import java.util.UUID;

/** Ends or releases a bond: rider or horse death, a horse whose chunk unloads, debug wipes. Reached through {@link TravellerMountSupport}. */
final class MountLifecycle {

    private MountLifecycle() {
    }

    /** A horse that must stop being a mount (a Stable pool horse): its loaded rider lets go of it and
     * every mount marker is removed. */
    static void dropBondFromHorse(EntityHorse horse) {
        UUID owner = HorseMarkers.getBondedOwner(horse);
        if (owner != null && horse.world instanceof WorldServer) {
            Entity o = ((WorldServer) horse.world).getEntityFromUuid(owner);
            if (o instanceof EntityTraveller && horse.getUniqueID().equals(((EntityTraveller) o).getMountUUID())) {
                EntityTraveller rider = (EntityTraveller) o;
                if (rider.getRidingEntity() == horse) rider.dismountRidingEntity();
                TravellerMountSupport.setLeashed(horse, null);
                rider.setMountUUID(null);
                rider.mountState.setMountSnapshotNbt(null);
            }
        }
        HorseMarkers.clearMountMarkers(horse.getEntityData());
    }

    /** A rider hands its live bonded horse over alive (a Stable return): dismounted, bond cleared on both
     * sides, speed back to normal. Null when there is no live, healthy bonded horse to hand over. */
    @Nullable
    static EntityHorse releaseLiveMount(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return null;
        Entity mount = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
        if (!(mount instanceof EntityHorse) || mount.isDead || ((EntityHorse) mount).getHealth() <= 0.0F) return null;
        EntityHorse horse = (EntityHorse) mount;
        if (rider.getRidingEntity() == horse) rider.dismountRidingEntity();
        TravellerMountSupport.setLeashed(horse, null);
        rider.setMountUUID(null);
        rider.mountState.setMountSnapshotNbt(null);
        dropBondFromHorse(horse);
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(DEFAULT_HORSE_SPEED);
        HorseAI.restoreVanilla(horse);
        return horse;
    }

    /**
     * A bonded horse outlives its rider as an ordinary tamed horse: leash off, speed back to normal,
     * bond marker gone so the anti-theft guard stops protecting it and a player can claim it. Its own
     * vanilla AI is not rebuilt here (there is no public way to re-run {@code initEntityAI}) - it
     * comes back on its own the next time the horse is loaded from disk, since
     * {@link MountBondLoadHandler} will no longer recognise it as bonded.
     */
    static void releaseMountOnRiderDeath(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) rider.world;
        Entity mount = world.getEntityFromUuid(mountUUID);
        rider.setMountUUID(null);
        rider.mountState.setMountSnapshotNbt(null);
        if (!(mount instanceof EntityHorse) || mount.isDead) {
            if (rider.mountState.getPendingMountNbt() != null) {
                // Held as data (tied at an unloaded post): it goes with its rider, like offscreen.
                rider.mountState.setParkedMount(null, null);
                return;
            }
            // The horse sits in an unloaded chunk: release (or remove) it the moment it loads again.
            MountReleaseLedger.get(world).add(mountUUID, TravellersConfig.MOUNTS.mountDiesWithRider);
            return;
        }
        if (TravellersConfig.MOUNTS.mountDiesWithRider) {
            killWithRider((EntityHorse) mount);
            return;
        }

        // Uniform for every rider type (plain traveller/raider, non-hired worker, hired worker): the
        // horse just becomes an ordinary loose, orphaned horse exactly where its rider died - never
        // teleported/respawned elsewhere (a nearby groupmate, an owner's Stable). Any nearby recruited
        // worker can notice and bond to it on its own (EntityAIClaimLooseMount), same as a player can
        // by simply walking up and taming it - see OrphanMountCleanup for the despawn rules.
        detachBondFromHorse((EntityHorse) mount);
    }

    /** A rider gone for good (died offscreen) whose horse was not held as data: the horse goes loose
     * where it stands, or when its chunk next loads. */
    static void releaseHorseOfGoneRider(WorldServer world, UUID horseId) {
        Entity mount = world.getEntityFromUuid(horseId);
        boolean dies = TravellersConfig.MOUNTS.mountDiesWithRider;
        if (mount instanceof EntityHorse && !mount.isDead) {
            if (dies) killWithRider((EntityHorse) mount);
            else detachBondFromHorse((EntityHorse) mount);
        } else {
            MountReleaseLedger.get(world).add(horseId, dies);
        }
    }

    /** MOUNTS.mountDiesWithRider: an ordinary death (animation, saddle/armor/loot drops), bond cleared first. */
    static void killWithRider(EntityHorse horse) {
        TravellerMountSupport.setLeashed(horse, null);
        HorseMarkers.clearBondedOwner(horse);
        horse.attackEntityFrom(net.minecraft.util.DamageSource.GENERIC, Float.MAX_VALUE);
        if (horse.getHealth() > 0.0F && !horse.isDead) horse.onKillCommand();
    }

    /** Vanilla's mid-range horse speed roll, used when a horse stops being a bonded mount. */
    static final double DEFAULT_HORSE_SPEED = 0.225D;

    /** Leash off, speed back to vanilla default, bond marker gone, orphan-cleanup timer started -
     * the horse survives as an ordinary tamed horse with nobody to go to. Used by
     * {@link #releaseMountOnRiderDeath} (rider died, no groupmate/Stable to give it to). */
    static void detachBondFromHorse(EntityHorse horse) {
        TravellerMountSupport.setLeashed(horse, null);
        HorseMarkers.clearBondedOwner(horse);
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .setBaseValue(DEFAULT_HORSE_SPEED);
        HorseMarkers.markOrphaned(horse, horse.world.getTotalWorldTime());
        HorseAI.restoreVanilla(horse);
    }

    /**
     * Hard, immediate teardown for an explicit debug wipe ({@code /travellersdebug reset[ workers]}):
     * unlike a natural death, there is no grace period to give a nearby player a chance to notice -
     * the whole point of the command is a clean slate right now. Killing the mount here (instead of
     * leaving it bonded to a rider {@code setDead()} is about to remove without ever running
     * {@link #releaseMountOnRiderDeath}) is what stops a debug reset from leaving orphaned horses
     * behind that the command's own summary claims were "removed".
     */
    static void detachAndKillMountForDebugWipe(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        rider.setMountUUID(null);
        rider.mountState.setMountSnapshotNbt(null);
        boolean heldAsData = rider.mountState.getPendingMountNbt() != null;
        rider.mountState.setParkedMount(null, null);
        if (heldAsData) return;
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return;
        Entity mount = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
        if (!(mount instanceof EntityHorse) || mount.isDead) {
            MountReleaseLedger.get(rider.world).add(mountUUID, true);
            return;
        }
        TravellerMountSupport.setLeashed((EntityHorse) mount, null);
        mount.setDead();
    }

    /**
     * Reconciles a bonded horse found in a chunk that is unloading, when its rider is NOT part of the
     * same unload (see {@code ChunkUnloadVirtualizeHandler}) - the one gap that let a horse become
     * permanently abandoned: its own chunk unloading on a different tick than its rider's silently
     * removes it from {@code World.entitiesByUuid} with nobody having captured it, and
     * {@code captureMountIfBonded}'s own duplicate-avoidance rule then refuses to ever rebuild it (see
     * that method's doc). Called instead of letting the chunk simply save the horse and forget it:
     * <ul>
     *   <li>rider still loaded (elsewhere): {@link #captureMount}+{@link #restoreMount} it right next
     *   to the rider - deliberately NOT a live teleport of the same entity object. By the time this
     *   event fires, {@code Chunk.onUnload()} has already queued every entity that was in this chunk's
     *   own entity lists into {@code World.unloadedEntityList} (see that method), and the next entity
     *   pass removes them from {@code entitiesByUuid} by their chunk membership at that moment -
     *   repositioning the object changes nothing. Killing it (which does work - the exact mechanism
     *   {@link ChunkUnloadVirtualizeHandler}'s own class doc relies on for travellers) and spawning a
     *   fresh one from the same NBT is the only path that actually keeps the pair together.</li>
     *   <li>rider gone virtual: capture the horse's NBT straight into that record, mirroring
     *   {@code VirtualTravellerManager.captureMountIfBonded} exactly, then remove the horse - the
     *   record picks it back up whenever it next materializes.</li>
     *   <li>rider genuinely gone from both (should not normally happen - a real death already goes
     *   through {@link #releaseMountOnRiderDeath}): treat it the same as an unclaimed dead-rider mount
     *   rather than leaving it bonded to nothing forever.</li>
     * </ul>
     */
    static void handleBondedHorseChunkUnload(WorldServer world, EntityHorse horse) {
        UUID riderUUID = HorseMarkers.getBondedOwner(horse);
        if (riderUUID == null) return;

        // A horse mid-death-animation (health already <= 0, vanilla's own removal still ~20 ticks
        // away) is not an unload-orphan, it is a death that should already have cleared the bond -
        // see HorseEvents.onLivingDeath's doc. Capturing its NBT here would freeze a corpse into the record
        // exactly like captureMount's own guard prevents; just clear the bond and let the chunk save
        // the dying horse as-is (deathTime is persisted, so it resumes and finishes dying on its own
        // once the chunk reloads).
        if (horse.getHealth() <= 0.0F) {
            onBondedMountDied(horse);
            return;
        }

        Entity rider = world.getEntityFromUuid(riderUUID);
        if (rider instanceof EntityTraveller && !rider.isDead
                && ((EntityTraveller) rider).mountState.getMountMode() == MountMode.HITCHED
                && ((EntityTraveller) rider).getVillageHitchStop() != null) {
            // Tied at a village post while its rider visits: it stays at its post, as data on the
            // rider, and reappears there when that ground is loaded again (tickParkedMount).
            EntityTraveller r = (EntityTraveller) rider;
            TravellerMountSupport.setLeashed(horse, null);
            NBTTagCompound snapshot = HorseSnapshots.capture(horse);
            BlockPos post = horse.getPosition();
            horse.setDead();
            r.mountState.setParkedMount(snapshot, post);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(r, "mount", "horse at the post kept as data (its chunk unloaded)");
            return;
        }
        if (rider instanceof EntityTraveller && !rider.isDead) {
            EntityTraveller r = (EntityTraveller) rider;
            boolean wasRidden = horse.isBeingRidden();
            if (wasRidden) r.dismountRidingEntity();
            TravellerMountSupport.setLeashed(horse, null);
            NBTTagCompound snapshot = HorseSnapshots.capture(horse);
            // Same-tick capture-then-respawn (unlike an ordinary materialize, which restores from a
            // snapshot taken ticks or minutes earlier): the dying horse would still linger in
            // entitiesByUuid for a moment, so the new one must not reuse its UUID (see CLAUDE.md's
            // "respawned live snapshot" trap) - strip it and let spawnMountFromNbt mint + re-link one.
            HorseSnapshots.stripIdentity(snapshot);
            horse.setDead();
            r.setMountUUID(null);
            MountPersistence.restoreMount(r, snapshot);
            return;
        }

        com.jubitus.travellers.virtual.VirtualTravellerManager vtm =
                com.jubitus.travellers.virtual.VirtualTravellerManager.get(world);
        if (vtm.isVirtual(riderUUID)) {
            TravellerMountSupport.setLeashed(horse, null);
            vtm.captureOrphanedMount(riderUUID, HorseSnapshots.capture(horse));
            horse.setDead();
            return;
        }

        detachBondFromHorse(horse);
    }

    /**
     * The one event that genuinely ends a bond. Everything else - a missing horse, an unloaded chunk,
     * a rider on foot - is recoverable and must never clear it, or the rider silently loses a
     * companion it could have got back (see {@link #resolveMount}).
     */
    static void onBondedMountDied(EntityHorse horse) {
        UUID ownerUUID = HorseMarkers.getBondedOwner(horse);
        if (ownerUUID == null || !(horse.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) horse.world;

        Entity owner = world.getEntityFromUuid(ownerUUID);
        if (owner instanceof EntityTraveller) {
            EntityTraveller rider = (EntityTraveller) owner;
            // The horse isn't actually gone yet (LivingDeathEvent fires ~20 ticks before setDead()),
            // and the rider is still isRiding() until told otherwise. Left alone, isMountedOnBondedMount()
            // flips false the instant mountUUID clears below while isRiding() stays true - every
            // mount-aware system (pathing, setPositionAndUpdate's mount-carry logic) then starts
            // treating the rider as on-foot and moving it, while vanilla's own updatePassenger() keeps
            // snapping its position back onto the dying horse every tick. That fight is what read as
            // "teleports far away, then snaps right back". Dismounting here first ends it immediately.
            if (rider.isRiding()) rider.dismountRidingEntity();
            rider.setMountUUID(null);
            rider.mountState.setMountSnapshotNbt(null);
            rider.mountState.setMountModeRaw(MountMode.MOUNTED);
            return;
        }
        // Rider is offscreen: clear the same thing on its virtual record instead, or it would
        // materialize later still expecting a horse that no longer exists.
        com.jubitus.travellers.virtual.VirtualTravellerManager.get(world)
                .clearRecordMount(ownerUUID);
    }
}
