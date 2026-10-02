package com.jubitus.travellers.mount;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.server.SPacketSetPassengers;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Repairs "the rider and its horse disagree" from the world tick, in both directions: a server state
 * no entity tick can fix, and a client that never learned about the pairing.
 *
 * <p><b>Why a rider ends up looking frozen.</b> While an entity {@code isRiding()}, vanilla's
 * {@code EntityTrackerEntry} deliberately sends it no position packets at all - only rotation and
 * metadata ({@code EntityTrackerEntry:194}) - because the client is meant to derive a passenger's
 * position from its vehicle. So any disagreement about the pairing renders as a body standing
 * perfectly still at its last known spot, head still turning, while its horse walks away on its own.
 * That single mechanism produces every symptom of this bug, whichever side is actually wrong.
 *
 * <p><b>Why it has to run here and not from {@code tickMount}.</b> {@link TravellerMountSupport#tickMount}
 * is and remains the sole authority for mounting, dismounting, leashing and moving a bonded horse -
 * this class never does any of that. It only resolves states {@code tickMount} can never observe,
 * because they are precisely the states in which the rider is not being ticked: vanilla runs
 * {@code updateRidden()} (and nothing else) for a riding entity, and skips it entirely for one sitting
 * within 32 blocks of unloaded terrain. Same argument as
 * {@code VirtualTravellerManager.checkVirtualize} - a unit that stopped ticking cannot rescue itself.
 *
 * <p><b>Job 1 - a rider left on a vehicle that no longer exists.</b> Vanilla's only cure for this is
 * the {@code entity.isDead} branch inside {@code Entity.updateRidden}, which a non-ticking rider never
 * reaches. It then stays {@code isRiding()} forever: no AI, no position packets, permanently frozen.
 * A bonded horse can legitimately be removed out from under a live rider (its own chunk unloading on a
 * different tick, a Stable reclaiming it, a capture), so this is not a hypothetical.
 *
 * <p><b>Job 2 - the client never learned the pairing.</b> {@code SPacketSetPassengers} is sent once,
 * on change, and never repeated; a single one dropped (it is discarded outright when it names an
 * entity the client has not been sent yet - the two are tracked independently and can arrive in
 * either order) leaves the client wrong for good, since the server-side list it is diffed against is
 * already correct and will not change again. Re-asserting it on a slow interval removes that
 * "wrong once, wrong forever" property without needing to win the race that caused it. Re-applying an
 * already-correct link is a no-op client-side (the dismount half of it does not reposition anything
 * off-server), so this is safe to repeat.
 */
public final class MountSyncReconciler {

    private MountSyncReconciler() {
    }

    public static void tick(WorldServer world, long now) {
        int interval = TravellersConfig.MOUNTS.riderSyncIntervalTicks;
        if (interval <= 0) return;
        // Phase off the world clock rather than a remembered tick: this is idempotent and its exact
        // cadence doesn't matter, and a static "last run" would let whichever dimension ticks first
        // claim the slot for all the others.
        if (now % interval != 0L) return;

        // Collected first: dismounting mutates the vehicle's passenger list, and a stray horse's
        // removal mutates the entity list being walked.
        List<EntityTraveller> riders = null;
        for (Entity e : world.loadedEntityList) {
            if (e.isDead || !(e instanceof EntityTraveller)) continue;
            if (!e.isRiding()) continue;
            if (riders == null) riders = new ArrayList<>();
            riders.add((EntityTraveller) e);
        }
        if (riders == null) return;

        for (EntityTraveller rider : riders) {
            Entity vehicle = rider.getRidingEntity();
            if (vehicle == null) continue;

            if (isGone(world, vehicle, rider)) {
                rider.dismountRidingEntity();
                TravellerTracer.event(rider, "mount", "reconciler: dismounted from a vehicle that no longer exists");
                com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.MOUNT_ORPHAN_VEHICLE);
                if (TravellersConfig.GENERAL.debug) {
                    FMLLog.log.info("[Travellers/Mounts] {} at ({}, {}) was riding a vehicle that no "
                                    + "longer exists - dismounted",
                            rider.getName(), (int) rider.posX, (int) rider.posZ);
                }
                continue;
            }

            // Still a real pairing: re-tell whoever can see it. sendToTracking is itself a no-op when
            // nobody is tracking the vehicle, so an offscreen pair costs one packet object and nothing
            // on the wire.
            world.getEntityTracker().sendToTracking(vehicle, new SPacketSetPassengers(vehicle));
        }
    }

    /**
     * True when the vehicle cannot carry anyone any more. {@code isDead} alone is not enough: an
     * entity removed from the world (captured into a virtual record, reclaimed by a Stable) is gone
     * from {@code loadedEntityList} on the same tick, and one whose passenger list no longer contains
     * this rider is a half-broken link that would otherwise persist just as silently.
     */
    private static boolean isGone(WorldServer world, Entity vehicle, Entity rider) {
        if (vehicle.isDead) return true;
        if (world.getEntityByID(vehicle.getEntityId()) != vehicle) return true;
        return !vehicle.getPassengers().contains(rider);
    }
}
