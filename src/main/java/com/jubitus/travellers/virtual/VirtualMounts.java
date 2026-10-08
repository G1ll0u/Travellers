package com.jubitus.travellers.virtual;

import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.nbt.NBTTagCompound;

/** Moves a unit's bonded horse into its virtual record when it goes offscreen, and back when it reappears. */
final class VirtualMounts {

    /** A tied horse farther than this from its rider going offscreen is left standing at its post. */
    private static final double HORSE_LEFT_AT_POST_BLOCKS = 8.0;

    private VirtualMounts() {
    }

    /**
     * Captures a bonded horse's full state (identity, tame/saddle, attributes, age, color) into the
     * record and removes it from the world, mirroring how {@code entity} itself goes virtual instead
     * of despawning. Restored by the matching branch in {@code VirtualTravellerManager.materializeEntity}. No-op if the
     * entity isn't currently riding its bonded mount.
     */
    static void captureIfBonded(EntityTraveller entity, VirtualTravellerRecord r) {
        // A mount already captured off-body for a solo boat crossing (EntityAITravel) takes priority -
        // it must be carried into the record instead of silently lost, however long entity itself
        // now stays virtual before that crossing would have finished.
        MountMode mode = entity.mountState.getMountMode();
        boolean tiedAtVillage = mode == MountMode.HITCHED && entity.getVillageHitchStop() != null && r.visit != null;
        NBTTagCompound pending = entity.mountState.getPendingMountNbt();
        if (pending != null) {
            // Held as data already (tied at an unloaded post): it stays tied there, as the record's data.
            r.mountNbt = pending;
            r.mounted = true;
            r.mountMode = (mode == MountMode.LED ? MountMode.MOUNTED : mode).name();
            if (tiedAtVillage && entity.mountState.getPendingMountPos() != null) r.visit.hitchPos = entity.mountState.getPendingMountPos();
            entity.mountState.setParkedMount(null, null);
            r.entityNbt.removeTag("PendingMountNbt");
            r.entityNbt.removeTag("PendingMountPos");
            return;
        }

        if (tiedAtVillage) {
            net.minecraft.entity.passive.EntityHorse horse = TravellerMountSupport.getBondedHorse(entity);
            if (horse != null && horse.getHealth() > 0.0F) {
                r.visit.hitchPos = horse.getPosition();
                r.mounted = true;
                r.mountMode = MountMode.HITCHED.name();
                if (horse.getDistanceSq(entity) > HORSE_LEFT_AT_POST_BLOCKS * HORSE_LEFT_AT_POST_BLOCKS) {
                    // Standing at its post, maybe in view: it stays there. The record comes back past
                    // the post before leaving (VirtualVillageVisits.tick), and gets it as data if the post's
                    // chunk unloads first (handleBondedHorseChunkUnload -> VirtualTravellerManager.captureOrphanedMount).
                    r.visit.horseAtPost = true;
                    return;
                }
            }
        }

        NBTTagCompound mountNbt = TravellerMountSupport.captureMount(entity);
        if (mountNbt == null) {
            // The horse isn't loaded (its chunk unloaded on another tick, or it was never reloaded
            // after a restart). The bond is kept: MountBondLoadHandler hands that horse to this record
            // the moment it loads, and the bond generation keeps any rebuilt copy from becoming a twin.
            return;
        }
        r.mountNbt = mountNbt;
        if (r.visit != null) r.visit.horseAtPost = false;
        // LED is a transient loaded-only reconciler state (terrain fallback, boat/water crossing);
        // its recovery tracking never persists and there is no terrain to path offscreen anyway, so
        // freezing a record as LED is what strands a materialized rider on foot forever. Record
        // MOUNTED; if the ground where it materializes really is impassable, the loaded AI re-derives
        // LED on the next failed path. Mirrors EntityTraveller.writeEntityToNBT.
        r.mountMode = (mode == MountMode.LED ? MountMode.MOUNTED : mode).name();
        // Covers mounts bonded outside the spawn-roll path too (e.g. /travellersdebug mount give),
        // where r.mounted was never rolled true - riding a bonded mount is definitive either way.
        r.mounted = true;
    }

    /**
     * Symmetric counterpart to {@link #captureIfBonded}: restores the exact same horse (same
     * UUID, same tame/saddle/attributes/age/color) from {@code r.mountNbt} if one was captured, or
     * bonds a brand new one via {@link TravellerMountSupport#bondNewMount} for a record that rolled
     * mounted at spawn (Phase 1) but never actually went virtual while mounted before. No-op if
     * neither applies. Must run after {@code entity} is already spawned into {@code world}.
     */
    static void restoreOrBond(EntityTraveller entity, VirtualTravellerRecord r) {
        if (r.mountMode != null) {
            entity.mountState.setMountModeRaw(com.jubitus.travellers.mount.MountMode.byName(r.mountMode));
        }
        com.jubitus.travellers.village.visit.VillageVisitState visit = r.visit;
        if (visit != null && visit.hitchPos != null && (r.mountNbt != null || visit.horseAtPost)) {
            // Tied at the village edge: the horse belongs at its post, not beside the rider. Held as
            // data until that ground is loaded (TravellerMountSupport.tickParkedMount), or already
            // standing there.
            entity.mountState.setMountModeRaw(MountMode.HITCHED);
            entity.setVillageHitchStop(visit.stop);
            if (r.mountNbt != null) entity.mountState.setParkedMount(r.mountNbt, visit.hitchPos);
            return;
        }
        if (r.mountNbt != null) {
            // TravellerMountSupport.restoreMount re-syncs speed to the live config and strips the
            // horse's own AI itself - see its doc for why both matter on every restore, not just a
            // fresh bond.
            TravellerMountSupport.restoreMount(entity, r.mountNbt);
        } else if (r.mounted) {
            TravellerMountSupport.bondNewMount(entity);
        }
    }
}
