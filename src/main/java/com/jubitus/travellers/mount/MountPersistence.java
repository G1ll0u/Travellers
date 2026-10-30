package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseSnapshots;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;
import javax.annotation.Nullable;
import java.util.UUID;

/** A horse held as data: capture and restore, rebuilding a lost horse, putting a parked horse back. Reached through {@link TravellerMountSupport}. */
final class MountPersistence {

    private MountPersistence() {
    }

    /** Consecutive ticks the bonded horse may be absent before it is considered genuinely lost. Long
     * enough to ride out a chunk boundary or a one-tick removal/re-add by another mod. */
    static final int MOUNT_MISSING_GRACE_TICKS = 60;

    /**
     * Finds this rider's bonded horse, and - the point of the whole method - refuses to accept
     * "it isn't there" as final. A horse can vanish for reasons that have nothing to do with dying:
     * another mod removing entities, a chunk that failed to save, a {@code spawnEntity} someone else
     * cancelled. A real death goes through {@link #onBondedMountDied} instead, which clears the bond
     * outright, so anything reaching the grace period here is by definition a loss rather than a
     * death and is rebuilt from the snapshot, same UUID and all.
     */
    @Nullable
    static EntityHorse resolveMount(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        WorldServer world = (WorldServer) rider.world;

        Entity found = world.getEntityFromUuid(mountUUID);
        if (found instanceof EntityHorse && !found.isDead) {
            rider.mountState.setMountMissingTicks(0);
            return (EntityHorse) found;
        }

        int missing = rider.mountState.getMountMissingTicks() + 1;
        rider.mountState.setMountMissingTicks(missing);
        if (missing < MOUNT_MISSING_GRACE_TICKS) return null;

        NBTTagCompound snapshot = rider.mountState.getMountSnapshotNbt();
        if (snapshot == null || !TravellersConfig.MOUNTS.restoreLostMounts) {
            rider.mountState.setMountMissingTicks(0);
            rider.setMountUUID(null);
            rider.mountState.setMountSnapshotNbt(null);
            return null;
        }

        // A horse missing from the tracked entity list is not necessarily gone: if its last known
        // spot sits in a chunk that simply isn't loaded right now (it fell behind on a leash, was
        // parked HITCHED somewhere the rider then walked away from, or was left on the far bank of a
        // boat crossing), the real horse is still there on disk. Rebuilding regardless would spawn a
        // SECOND EntityHorse with the identical bonded UUID the moment that chunk loads again and the
        // original reappears - a duplicate-identity bug, not a rescue, and the exact shape of "a horse
        // with no rider drags a leash toward wherever the real rider ended up". Only rebuild once that
        // chunk is actually loaded and still doesn't have the horse in it - the one case that is
        // genuinely a loss (another mod removed it, a save got corrupted). Never force-loads the
        // chunk to check (see the class doc's "nothing force-loads chunks" rule) - if it stays
        // unloaded, this just keeps waiting.
        if (!isSnapshotChunkLoaded(world, snapshot)) return null;
        rider.mountState.setMountMissingTicks(0);

        FMLLog.log(Level.INFO, "[Travellers/Mounts] %s's bonded horse went missing without dying - rebuilding it",
                rider.getName());
        return spawnMountFromNbt(rider, snapshot, null);
    }

    /**
     * Puts a horse held as data back into the world. Tied at a village post: only there, and only once
     * that ground is loaded and ticking (so it simply "is there" when a player comes near). Otherwise
     * (the rider untied it to ride on): beside the rider.
     */
    static void tickParkedMount(EntityTraveller rider) {
        if (rider.ticksExisted % 20 != 0) return;
        WorldServer world = (WorldServer) rider.world;
        // The copy that was just removed can linger for a tick under the same identity.
        if (rider.getMountUUID() != null && world.getEntityFromUuid(rider.getMountUUID()) != null) return;
        NBTTagCompound nbt = rider.mountState.getPendingMountNbt();
        BlockPos post = rider.mountState.getPendingMountPos();
        boolean tied = post != null && rider.mountState.getMountMode() == MountMode.HITCHED && rider.getHitchStop() != null;
        if (tied && (!world.isBlockLoaded(post) || com.jubitus.travellers.virtual.VirtualTravellerManager
                .isFrozenByVanillaAt(world, post.getX() + 0.5D, post.getZ() + 0.5D))) {
            return;
        }
        // Cleared first: the join check drops any copy while the rider still holds the horse as data.
        rider.mountState.setParkedMount(null, null);
        spawnMountFromNbt(rider, nbt, tied ? post : null);
    }

    /** Whether the chunk holding a captured mount snapshot's last known position is currently
     * loaded - see {@link #resolveMount}'s doc on why a rebuild must never happen while it isn't. */
    static boolean isSnapshotChunkLoaded(WorldServer world, NBTTagCompound snapshot) {
        net.minecraft.nbt.NBTTagList pos = snapshot.getTagList("Pos", 6);
        if (pos.tagCount() < 3) return true; // no usable position on record - don't block a rescue on bad data
        double x = pos.getDoubleAt(0);
        double z = pos.getDoubleAt(2);
        return world.getChunkProvider().chunkExists(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4);
    }

    /** Keeps the rebuild-if-lost snapshot current (health, gear, growth) without paying for a full
     * NBT write every tick.
     *
     * <p>Never overwrites the snapshot with a horse mid-death (health {@code <= 0}): vanilla runs a
     * ~20-tick death animation before actually removing the entity ({@code Entity.isDead} only flips
     * once that finishes), so a periodic refresh landing in that window would otherwise persist an
     * already-dying horse as "the last known good state" - the exact snapshot {@link #resolveMount}
     * would later resurrect a corpse from, on a loop, if the death event itself somehow never clears
     * the bond (see {@code HorseEvents}'s doc). Skipping the write here just means the
     * previous, still-healthy snapshot survives instead.
     */
    static void refreshMountSnapshot(EntityTraveller rider, EntityHorse horse) {
        if (horse.getHealth() <= 0.0F) return;
        if (rider.ticksExisted % 200 != 0 && rider.mountState.getMountSnapshotNbt() != null) return;
        rider.mountState.setMountSnapshotNbt(HorseSnapshots.capture(horse));
    }

    /**
     * Captures a bonded mount's full NBT (identity, tame/saddle, attributes, age, color) and removes
     * it from the world, so a rider going virtual takes its horse with it instead of leaving it
     * standing in a chunk nobody will visit again.
     *
     * <p><b>Works whether the horse is being ridden, led or hitched</b> - it is found by UUID when the
     * rider is on foot. Only capturing a ridden one is what used to strand a camping rider's parked
     * horse in the world permanently, with {@code bondNewMount} then refusing to ever give that rider
     * another one. Dismounts first when ridden: {@code Entity.writeToNBT} serializes the passenger
     * list recursively, and capturing while still mounted would embed a full duplicate copy of the
     * rider's own NBT inside the horse's record, which a later {@link #restoreMount} would then spawn
     * as a second, unbonded rider. Returns {@code null} if there is no bonded horse to capture.
     *
     */
    @Nullable
    static NBTTagCompound captureMount(EntityTraveller rider) {
        Entity ride = null;
        if (rider.isMountedOnBondedMount()) {
            ride = rider.getRidingEntity();
        } else {
            // A bonded horse standing next to its rider (led or hitched) must be captured too -
            // otherwise virtualizing an on-foot rider leaves its horse behind in the world with
            // nobody to reclaim it, and bondNewMount refuses to ever give that rider another one.
            UUID mountUUID = rider.getMountUUID();
            if (mountUUID != null && rider.world instanceof WorldServer) {
                Entity found = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
                if (found instanceof EntityHorse && !found.isDead) ride = found;
            }
        }
        if (ride == null) return null;

        // `!isDead` alone reads a horse mid-death-animation (health already <= 0, vanilla's ~20-tick
        // delay before it is actually removed - see HorseEvents.onLivingDeath's doc) as "present and capturable".
        // Writing its NBT and hard-`setDead()`-ing it here would silently finish the removal WITHOUT
        // ever going through onDeath()/LivingDeathEvent, so onBondedMountDied never runs: the bond
        // stays set and the captured NBT is a corpse snapshot that a later restore would resurrect,
        // dying again the same way - the exact loop this class exists to prevent. Route through the
        // one real "this bond is over" handler instead of inventing a second copy of it here.
        if (ride instanceof EntityHorse && ((EntityHorse) ride).getHealth() <= 0.0F) {
            MountLifecycle.onBondedMountDied((EntityHorse) ride);
            return null;
        }

        if (rider.isMountedOnBondedMount()) rider.dismountRidingEntity();
        if (ride instanceof EntityHorse) TravellerMountSupport.setLeashed((EntityHorse) ride, null);
        NBTTagCompound mountNbt = HorseSnapshots.capture((EntityHorse) ride);
        ride.setDead();
        return mountNbt;
    }

    /**
     * Symmetric counterpart to {@link #captureMount}: restores the exact same horse (same UUID, same
     * tame/saddle/attributes/age/color) from previously-captured NBT next to {@code rider} and
     * re-mounts it. Returns {@code false} (and clears the rider's {@code mountUUID}, since it would
     * otherwise dangle forever - see {@link #bondNewMount}) if the horse fails to spawn, e.g.
     * cancelled by another mod's claim/protection system.
     */
    static boolean restoreMount(EntityTraveller rider, NBTTagCompound mountNbt) {
        return spawnMountFromNbt(rider, mountNbt, null) != null;
    }

    /**
     * Rebuilds a bonded horse from stored NBT beside its rider and leaves {@link #tickMount} to put
     * the pair back into whatever {@link MountMode} the rider is in - the same single transition path
     * a mode change goes through, so a restore can't produce a state no other code path can.
     * A rider whose horse was parked outside its camp shelter ({@link #resolveMountSpawnPos}) is
     * switched to {@link MountMode#HITCHED} rather than being yanked out of its shelter onto it.
     * Returns {@code null} (and clears the bond, which would otherwise dangle forever - see
     * {@link #bondNewMount}) if the horse fails to spawn, e.g. cancelled by another mod.
     *
     * <p>The single choke point for every caller that rebuilds a horse from stored NBT
     * ({@link #resolveMount}'s "went missing without dying" rebuild, {@link #restoreMount}'s
     * chunk-unload/materialize restore) - refusing a corpse snapshot here covers both instead of
     * needing the same check at each call site. A corpse snapshot comes from a capture during vanilla's death animation.
     */
    @Nullable
    static EntityHorse spawnMountFromNbt(EntityTraveller rider, NBTTagCompound mountNbt, @Nullable BlockPos parkAt) {
        World world = rider.world;
        if (!(world instanceof WorldServer)) return null;

        if (HorseSnapshots.isCorpse(mountNbt)) {
            rider.setMountUUID(null);
            rider.mountState.setMountSnapshotNbt(null);
            return null;
        }

        EntityHorse horse = new EntityHorse(world);
        horse.readFromNBT(mountNbt);
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID != null) horse.setUniqueId(mountUUID);
        MountBonding.MountSpawnPos pos = parkAt != null ? MountBonding.parkedSpawnPos((WorldServer) world, horse, parkAt) : MountBonding.resolveMountSpawnPos(rider, horse);
        horse.setLocationAndAngles(pos.x, pos.y, pos.z, rider.rotationYaw, 0.0F);
        horse.motionX = 0;
        horse.motionY = 0;
        horse.motionZ = 0;
        MountBonding.stampNewGeneration(rider, horse);
        MountBonding.applyMountedGroundSpeed(horse);
        MountBonding.applyBondInvariants(horse);

        // Linked before the spawn (a no-op for the ordinary restore, load-bearing when the caller
        // cleared mountUUID to avoid a same-tick UUID collision, see handleBondedHorseChunkUnload):
        // the join check (MountBondLoadHandler) must see the rider claim this exact horse.
        rider.setMountUUID(horse.getUniqueID());
        if (!world.spawnEntity(horse)) {
            rider.setMountUUID(null);
            return null;
        }

        if (pos.parked && pos.camping && rider.mountState.getMountMode() == MountMode.MOUNTED) {
            rider.mountState.setMountModeRaw(MountMode.HITCHED);
        } else if (!pos.parked && rider.mountState.getMountMode() == MountMode.MOUNTED
                && horse.getDistanceSq(rider) <= 4.0D
                && rider.onGround && !rider.isInWater()
                && horse.onGround && !horse.isInWater()) {
            // The horse fit right where the rider stands (a materialize / restore resolved the pair
            // to horse-sized ground): climb on now instead of a tick of "rider on foot beside its
            // horse" while reconcileOnFoot's sustained remount gate counts up.
            TravellerMountSupport.setLeashed(horse, null);
            rider.startRiding(horse, true);
            // Seated now, not on the horse's next tick: a horse on non-ticking ground never moves its rider,
            // and the pair would be saved split across a chunk border.
            horse.updatePassenger(rider);
        }
        return horse;
    }
}
