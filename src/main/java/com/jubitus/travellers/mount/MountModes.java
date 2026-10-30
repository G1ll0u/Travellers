package com.jubitus.travellers.mount;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;
import javax.annotation.Nullable;
import java.util.UUID;

/** The MOUNTED / LED / HITCHED intent: mode changes, the fallback-to-LED entry, remounting, and reconciling a rider on foot. Reached through {@link TravellerMountSupport}. */
final class MountModes {

    private MountModes() {
    }

    /**
     * Everything that happens while the rider is on foot with its horse still bonded. All three cases
     * end in the same guarantee - the pair stays together - and differ only in how:
     * MOUNTED wants back in the saddle, LED walks the horse along on a leash, HITCHED leaves it be.
     */
    static void reconcileOnFoot(EntityTraveller rider, EntityHorse horse) {
        double distSq = horse.getDistanceSq(rider);
        double leadMax = TravellersConfig.MOUNTS.leadMaxDistance;

        switch (rider.mountState.getMountMode()) {
            case HITCHED:
                TravellerMountSupport.setLeashed(horse, null);
                BlockPos hitchStop = rider.getHitchStop();
                if (hitchStop != null) {
                    // Tied at a village edge while its rider visits: never recalled, the rider comes
                    // back for it. A visit that ended some other way (the trip moved on, a reload
                    // elsewhere) hands the horse back to the normal walk-over-and-remount.
                    if (!rider.isOnStay() && !hitchStop.equals(rider.getUpcomingStayStop())) {
                        rider.setHitchStop(null);
                        setMountMode(rider, MountMode.MOUNTED);
                    }
                    return;
                }
                // Standing where it was left is the whole point, so it is only ever recalled when
                // the rider has gone so far that the pair would otherwise be separated for good.
                if (distSq > (leadMax * 2.0D) * (leadMax * 2.0D)) LedHorseDriver.teleportMountNear(rider, horse, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_RECALL_HITCHED);
                return;

            case LED:
                TravellerMountSupport.setLeashed(horse, rider);
                LedHorseDriver.driveLedHorse(rider, horse, distSq);
                if (distSq > leadMax * leadMax) LedHorseDriver.teleportMountNear(rider, horse, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_RECALL_LED);
                else if (LedHorseDriver.considerLedStuckRecall(rider, horse, distSq)) return;
                // Orphaned LED: the terrain-fallback recovery timer isn't armed. For a traveller LED
                // is *only* ever a transient fallback (terrain, or the water-dismount below), so an
                // untracked one must not be left forever (seen in the field after a reload: the mode
                // persisted, its transient timer did not, and the rider led its horse around with the
                // horse standing right next to it). Flip back to MOUNTED and let that branch's own
                // sustained safe-to-remount gate decide when; arm tracking if the horse is far.
                if (rider.mountState.getLedFallbackSinceTick() < 0 && !rider.isRiding()) {
                    double remountDist = TravellersConfig.MOUNTS.remountDistance;
                    if (distSq <= remountDist * remountDist * 4.0D && !isMountTooHurtToRide(horse)
                            && !isNearVillageRequiringLed(rider)) {
                        rider.mountState.setMountModeRaw(MountMode.MOUNTED);
                    } else {
                        markTerrainFallbackLed(rider);
                    }
                    return;
                }
                considerTerrainFallbackRemount(rider, horse);
                return;

            case MOUNTED:
            default:
                // Someone else is already in the saddle (a player briefly slipping past HorseTheftGuard,
                // or one tick of race before it cancels the interaction) - never force-mount over an
                // existing rider. Entity.startRiding(force=true) below bypasses the single-passenger
                // check entirely and would just stack the bonded rider on as a second passenger instead
                // of evicting the first, which read as the player getting mounted then instantly kicked
                // off. Wait it out; the guard's own EntityMountEvent cancel is what actually stops this
                // from happening in the first place.
                if (horse.isBeingRidden()) return;

                // Wants to ride but isn't: it stepped off in water, or its horse is waiting where it
                // was hitched. Leash it so it walks over on its own and climb back on only once the
                // pair has been dry, grounded and close together for a sustained window - a single
                // tick of the horse touching a shelving river bed must not trigger a remount that
                // dunks it straight back under (the on-and-off flicker at the shoreline).
                TravellerMountSupport.setLeashed(horse, rider);
                LedHorseDriver.driveLedHorse(rider, horse, distSq);

                double remountDist = TravellersConfig.MOUNTS.remountDistance;
                boolean remountReady = distSq <= remountDist * remountDist && canRemountHere(rider, horse);
                int readyTicks = remountReady ? rider.mountState.getRemountReadyTicks() + 1 : 0;
                rider.mountState.setRemountReadyTicks(readyTicks);
                if (readyTicks >= TravellersConfig.MOUNTS.remountStableTicks) {
                    rider.mountState.setRemountReadyTicks(0);
                    rider.mountState.setRemountTicks(0);
                    TravellerMountSupport.setLeashed(horse, null);
                    rider.startRiding(horse, true);
                    return;
                }

                // The timeout only means "the horse can't get here by itself" - a horse already
                // standing (or swimming) right next to a rider that simply can't mount yet is not
                // stuck, and teleporting it on the spot would be pure noise.
                int waited = rider.mountState.getRemountTicks() + 1;
                rider.mountState.setRemountTicks(waited);
                boolean horseIsFarAway = distSq > 8.0D * 8.0D;
                if ((waited > TravellersConfig.MOUNTS.remountTimeoutTicks && horseIsFarAway)
                        || distSq > leadMax * leadMax) {
                    rider.mountState.setRemountTicks(0);
                    LedHorseDriver.teleportMountNear(rider, horse, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_RECALL_REMOUNT);
                }
        }
    }

    /**
     * Genuinely swimming, as opposed to merely standing in shallow water: a horse fording an
     * ankle-deep stream stays on the ground and keeps carrying its rider, which is what a real one
     * does. {@code isInWater()} alone is true for both, hence the second half.
     */
    static boolean isSwimming(EntityHorse horse) {
        return horse.isInWater()
                && (!horse.onGround || horse.isInsideOfMaterial(Material.WATER));
    }

    /** Whether this horse is hurt enough that its rider should be leading it, not riding it - see
     * {@code MOUNTS.mountLowHealthLedThresholdPercent}. 0 turns the whole feature off. */
    static boolean isMountTooHurtToRide(EntityHorse horse) {
        int thresholdPercent = TravellersConfig.MOUNTS.mountLowHealthLedThresholdPercent;
        if (thresholdPercent <= 0) return false;
        float max = horse.getMaxHealth();
        return max > 0 && horse.getHealth() / max < (thresholdPercent / 100.0F);
    }

    /** Hitches the horse where it stands once its rider is inside the grounds of the settlement it is
     * about to visit - never inside or on a building, never in water or mid-jump (it just retries). */
    static void tryHitchAtVillageEdge(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.hitchMountInVillages || rider.isInCombat()) return;
        BlockPos stop = rider.getUpcomingStayStop();
        if (stop == null) return;
        com.jubitus.travellers.stay.StayPlace place = com.jubitus.travellers.stay.StayPlace.at(rider.world, stop);
        if (place == null || !place.contains(rider.posX, rider.posZ)) return;
        if (!horse.onGround || horse.isInWater() || isSwimming(horse)) return;
        if (com.jubitus.travellers.village.MillFootprints.isForbiddenLanding(rider.world,
                horse.posX, horse.posY, horse.posZ)) return;
        setMountMode(rider, MountMode.HITCHED);
        rider.setHitchStop(stop);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "hitched at village edge %.1f,%.1f,%.1f", horse.posX, horse.posY, horse.posZ));
    }

    /** Ticks between two settlement-proximity rescans - see {@code nextVillageProximityCheckTick}. */
    static final int VILLAGE_PROXIMITY_CHECK_INTERVAL = 10;

    /** Whether this rider should currently be leading its horse rather than riding it because it is
     * on a village or lone building's own grounds - see {@code MOUNTS.mountedDismountNearVillage}.
     * A hired worker actively escorting its owner is exempt: it needs to keep pace with a player who
     * may itself be mounted, and villages are exactly where an escort would otherwise fall behind.
     * Throttled per rider ({@link EntityTraveller#getNextVillageProximityCheckTick()}) since the
     * settlement scan itself is too heavy to redo unconditionally every tick for every mounted rider
     * in the world, mounted or not, near a village or not. */
    static boolean isNearVillageRequiringLed(EntityTraveller rider) {
        if (!TravellersConfig.MOUNTS.mountedDismountNearVillage) return false;
        if (rider instanceof EntityTravellerWorker && ((EntityTravellerWorker) rider).isHiredFollower()) {
            return false;
        }
        if (rider.ticksExisted < rider.mountState.getNextVillageProximityCheckTick()) {
            return rider.mountState.isCachedNearVillage();
        }
        rider.mountState.setNextVillageProximityCheckTick(rider.ticksExisted + VILLAGE_PROXIMITY_CHECK_INTERVAL);
        boolean near = com.jubitus.travellers.village.MillSettlements.isInside(
                rider.world, rider.getPosition());
        rider.mountState.setCachedNearVillage(near);
        return near;
    }

    /**
     * The instantaneous half of the remount test (the other half is the sustained-tick window in
     * {@code reconcileOnFoot}'s MOUNTED branch). Both the rider and the horse must be on solid ground
     * and clear of water: vanilla ejects a rider whose head goes underwater, and remounting a horse
     * still standing in the river it hasn't climbed out of just dunks the rider again. "Dry on the
     * far bank" is the whole rule the user asked for - never in the water, never while the horse is
     * still on the bank it left.
     */
    static boolean canRemountHere(EntityTraveller rider, EntityHorse horse) {
        if (rider.isRiding()) return false; // already in something else (a boat crossing)
        if (!rider.onGround || rider.isInWater()) return false;
        return horse.onGround && !horse.isInWater();
    }

    /**
     * Tells the owning player that their worker's horse gave up trying to ride through and is being
     * led on foot instead - the dismount is otherwise silent to the player, logged only to the debug
     * log and trace file. Called from the two places that switch to {@link MountMode#LED} because the
     * horse genuinely cannot make it, not from the routine cases (boats, villages, camps) where a
     * dismount is expected and not worth a toast.
     *
     * <p>Hired workers only: travellers/raiders have no owning player to tell.
     */
    static void notifyMountLedFallback(EntityTraveller rider,
                                                com.jubitus.travellers.notify.NotificationDef def) {
        if (!TravellersConfig.MOUNTS.notifyOnMountLedFallback) return;
        if (!(rider instanceof EntityTravellerWorker) || !(rider.world instanceof WorldServer)) return;
        UUID ownerId = ((EntityTravellerWorker) rider).getFollowOwnerId();
        if (ownerId == null) return;
        com.jubitus.travellers.notify.NotificationService.notifyOfflineAt(
                ((WorldServer) rider.world).getMinecraftServer(), ownerId, def,
                com.jubitus.travellers.notify.NoticePlace.of(rider), null, rider.getName());
    }

    /**
     * Sets what the rider wants to do with its horse. The transition itself (getting off onto solid
     * ground, leashing, climbing back on) is left to {@link #tickMount}, which runs every tick and
     * can wait for the right moment - a mode change asked for mid-river or mid-jump would otherwise
     * have to either fail or drop the rider somewhere silly.
     */
    static void setMountMode(EntityTraveller rider, MountMode mode) {
        if (rider.mountState.getMountMode() == mode) return;
        if (TravellersConfig.MOUNTS.debugMounts) {
            FMLLog.log(Level.INFO, "[Travellers/Mounts] %s mount mode %s -> %s (riding=%b)",
                    rider.getName(), rider.mountState.getMountMode(), mode, rider.isMountedOnBondedMount());
        }
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                "mode " + rider.mountState.getMountMode() + " -> " + mode);
        if (rider.mountState.getMountMode() == MountMode.LED) com.jubitus.travellers.debug.profile.MovementProfiler.ledEnded(rider);
        rider.mountState.setMountModeRaw(mode);
        rider.mountState.setRemountTicks(0);
        rider.mountState.setRemountReadyTicks(0);
        // Leaving LED for any reason retires the terrain-fallback self-heal timer, whatever armed
        // it - a stale timer/position surviving into a later, unrelated LED spell (say, a boat
        // crossing towing the horse alongside) would otherwise fire instantly on old data. See
        // markTerrainFallbackLed/considerTerrainFallbackRemount.
        if (mode != MountMode.LED) {
            rider.mountState.setLedFallbackSinceTick(-1);
        }
        // The led-stuck quick-recall tracker and the trail-pacer state only mean anything while
        // leading on foot.
        rider.mountState.setLedHorseStuckTicks(0);
        rider.mountState.setLedHorseBestGapSq(Double.MAX_VALUE);
        rider.mountState.setLedPacerObstacleTicks(0);
        rider.mountState.setLedPacerNavUntilTick(0);
        rider.mountState.setLedPacerSmoothedRiderSpeed(0.0D);
        if (mode != MountMode.MOUNTED && rider.isMountedOnBondedMount()) {
            // Vanilla already places a dismounting rider beside a horse rather than inside it
            // (EntityLivingBase.dismountEntity has a dedicated AbstractHorse branch that steps off to
            // the side and lifts out of any block it would land in).
            rider.dismountRidingEntity();
        }
    }

    /**
     * Arms the shared time+distance self-heal back to {@link MountMode#MOUNTED}, checked every tick
     * from {@link #reconcileOnFoot}. Call right after switching to {@link MountMode#LED} for a "the
     * horse genuinely cannot make it through here" reason - not for a routine LED (a boat crossing
     * towing the horse alongside), which restores its own mode explicitly once the crossing ends and
     * has no need of this.
     *
     * <p>Two separate places dismount a rider for terrain reasons: a footprint-blocked route
     * ({@code EntityAITravel.considerTerrainDismount}) and giving up on sidesteps against a wedge
     * ({@link #trySidestepIfWedged}, above). Both call this. A rider left permanently on foot after
     * giving up on a wedge - no self-heal at all - was a real bug: only the route-check path used to
     * arm any recovery, so a rider that ended up in LED via the other one simply never got back on.
     */
    static void markTerrainFallbackLed(EntityTraveller rider) {
        rider.mountState.setLedFallbackSinceTick(rider.ticksExisted);
        rider.mountState.setLedFallbackPos(rider.posX, rider.posZ);
    }

    /**
     * Climbs back on once the pair is clear of whatever forced them off, judged by time <em>and</em>
     * distance covered rather than by re-testing whether a mounted route exists - re-testing right
     * next to the obstacle would keep answering "no" at the exact spot the rider just walked clear
     * of. Having both conditions is what stops the pair from mounting and dismounting on alternating
     * attempts; if the terrain ahead is still too tight, the next failed route or wedge simply arms
     * this again.
     */
    static void considerTerrainFallbackRemount(EntityTraveller rider, EntityHorse horse) {
        int since = rider.mountState.getLedFallbackSinceTick();
        if (since < 0) return;
        if (rider.ticksExisted - since < TravellersConfig.MOUNTS.terrainRemountAfterTicks) return;
        // Only ever remount onto dry, solid footing - not while the rider is still swimming a river
        // or mid-jump. The MOUNTED reconcile's own canRemountHere covers this once the mode flips,
        // but flipping the mode mid-water first drops a tick of the rider standing on the water.
        if (!rider.onGround || rider.isInWater()) return;
        // A horse led for being badly wounded must stay led regardless of elapsed time/distance -
        // those two alone are the terrain-recovery signal, not "has this horse healed".
        if (isMountTooHurtToRide(horse)) return;
        // Likewise: still on a village's own grounds means still not remounting, whatever the
        // elapsed time/distance since dismount says - a village bigger than terrainRemountDistance
        // would otherwise remount the rider mid-street.
        if (isNearVillageRequiringLed(rider)) return;

        double dx = rider.posX - rider.mountState.getLedFallbackX();
        double dz = rider.posZ - rider.mountState.getLedFallbackZ();
        double need = TravellersConfig.MOUNTS.terrainRemountDistance;
        if (dx * dx + dz * dz < need * need) return;

        setMountMode(rider, MountMode.MOUNTED);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                "clear of the tight ground - remounting");
    }

    /** Steps the rider down to lead its horse because the horse cannot carry on here: counted by the
     * profiler, LED, the remount self-heal armed, and the owner told when {@code notice} is set. */
    static void enterFallbackLed(EntityTraveller rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason reason,
                                 @Nullable com.jubitus.travellers.notify.NotificationDef notice) {
        com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(rider, reason);
        setMountMode(rider, MountMode.LED);
        markTerrainFallbackLed(rider);
        if (notice != null) notifyMountLedFallback(rider, notice);
    }
}
