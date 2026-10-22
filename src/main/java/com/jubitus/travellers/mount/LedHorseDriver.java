package com.jubitus.travellers.mount;

import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;
import javax.annotation.Nullable;

/** Drives a led horse along its rider's trail and recalls it when it falls behind. Reached through {@link TravellerMountSupport}. */
final class LedHorseDriver {

    private LedHorseDriver() {
    }

    /**
     * The single driver of a led horse - vanilla's own leash steering is cancelled outright for
     * bonded mounts (see {@code MixinEntityCreature}). Before that cancel existed, vanilla re-pathed
     * the horse toward its holder <em>every tick</em> under 6 blocks and added spring impulses
     * between 6 and 10, in parallel with this method's own navigation - three steering sources
     * fighting over one body was the "erratic led horse" report.
     *
     * <p><b>Single-file trail follow, steered every tick.</b> The horse is driven straight at a
     * point on the rider's own recent footsteps ({@link EntityTraveller#leadTrailPointBehind}),
     * {@code ledFollowLeadGapBlocks} back - so it treads exactly the ground the rider just cleared
     * and never cuts a corner or weaves. The old model - the horse's own A* re-planned to the
     * rider's <em>current</em> position on a ~10-tick throttle - made it chase a point stale by up
     * to that many ticks, arriving under its own feet and pivoting to the next stale target: the
     * reported staircase / zig-zag / spinning.
     *
     * <p><b>Pace mirrors the rider's own current move-speed</b> ({@code getAIMoveSpeed()}, smoothed),
     * divided back out of the horse's fixed MOVEMENT_SPEED attribute so the two run identical
     * moveForward and therefore identical terminal speed, whatever task is driving the rider. A
     * bounded catch-up factor ({@code ledFollowCatchupMaxSpeedFactor}) on top only for closing a
     * real gap.
     *
     * <p>The horse's own pathfinder is the fallback, not the default: a genuine jam against terrain
     * the straight step cannot clear ({@code collidedHorizontally} and going nowhere for
     * {@code ledFollowObstacleTicks}) hands it to A* for {@code ledFollowPathfindTicks}, then it
     * resumes stepping. The straight-line tow is a second exception - only past
     * {@code ledFollowTowDistance} and only with no ground route (swimming, or behind a boat).
     */
    static void driveLedHorse(EntityTraveller rider, EntityHorse horse, double distSq) {
        double dist = Math.sqrt(distSq);

        net.minecraft.entity.ai.attributes.IAttributeInstance attr =
                horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        double base = UnitSpeed.ATTRIBUTE;
        if (Math.abs(attr.getBaseValue() - base) > 1.0E-4D) attr.setBaseValue(base);
        double attrVal = Math.max(attr.getAttributeValue(), 1.0E-3D);

        // Mirror the rider's own current move-speed (moveForward-level, i.e. the same half of the
        // speed product a navigator-driven horse also runs on) into the horse's move-helper speed
        // param, smoothed - horse and rider run identical ground physics, so identical moveForward
        // gives identical terminal speed whatever task is driving the rider. Using the rider's raw
        // per-tick displacement here instead would be off by the ground-friction factor.
        double riderMove = Math.max(rider.getAIMoveSpeed(), 0.0F);
        double smoothed = rider.mountState.getLedPacerSmoothedRiderSpeed() * 0.8D + riderMove * 0.2D;
        rider.mountState.setLedPacerSmoothedRiderSpeed(smoothed);

        double stop = TravellersConfig.MOUNTS.ledFollowStopDistance;
        double leadGap = TravellersConfig.MOUNTS.ledFollowLeadGapBlocks;
        // A capped catch-up factor for closing a gap; a travel-pace floor so a lagging horse still
        // makes ground even when the rider itself has slowed to a crawl.
        double pace = Math.max(MountRiding.MIN_MOUNTED_SPEED, smoothed / attrVal);
        if (dist > stop + leadGap) {
            double over = dist - (stop + leadGap);
            double catchUp = Math.min(1.0D + over * 0.15D, TravellersConfig.MOUNTS.ledFollowCatchupMaxSpeedFactor);
            // Ground speed grows with the square of the move speed: sqrt keeps catchUp a multiple of the pace.
            pace = Math.max(pace, UnitSpeed.aiSpeed(UnitSpeed.travelPercent()) / attrVal) * Math.sqrt(catchUp);
        }

        // Deep water / no ground to tread: tow it straight (behind a swimming rider or a boat).
        if (dist > TravellersConfig.MOUNTS.ledFollowTowDistance
                && (MountModes.isSwimming(horse) || horse.getNavigator().noPath())) {
            double dx = (rider.posX - horse.posX) / dist;
            double dy = (rider.posY - horse.posY) / dist;
            double dz = (rider.posZ - horse.posZ) / dist;
            horse.motionX += dx * Math.abs(dx) * 0.4D;
            horse.motionY += dy * Math.abs(dy) * 0.4D;
            horse.motionZ += dz * Math.abs(dz) * 0.4D;
            haltLedHorseMoveHelper(horse);
            return;
        }

        // At heel: stand still, no jitter. The move helper keeps its last MOVE_TO order until told
        // otherwise (it never reverts to WAIT on its own), so command the stop explicitly or the
        // horse drifts on to the stale aim and orbits it.
        if (dist <= stop) {
            if (!horse.getNavigator().noPath()) horse.getNavigator().clearPath();
            haltLedHorseMoveHelper(horse);
            rider.mountState.setLedPacerObstacleTicks(0);
            return;
        }

        // Obstacle fallback window: let the horse's own pathfinder route around whatever the
        // straight step is jammed against.
        //
        // Budgeted on the world-wide mount row of PathBudget. Without this, a horse whose search keeps failing (routine on rough
        // terrain, exactly where LED triggers most) re-ran a full A* EVERY tick for the whole
        // ledFollowPathfindTicks window - with many mounted followers jamming at once on hard
        // terrain, that is the single biggest cost in a large mounted group (profiled: tens of
        // percent of server tick time). A denied tick just skips the search and retries next tick;
        // the horse stands still that one tick rather than spending it on a doomed search.
        if (rider.ticksExisted < rider.mountState.getLedPacerNavUntilTick()) {
            if (horse.getNavigator().noPath()
                    && com.jubitus.travellers.pathing.PathBudget.tryAcquire(horse.world, com.jubitus.travellers.pathing.PathBudget.Client.MOUNT, rider.getEntityId(), 1)) {
                horse.getNavigator().tryMoveToEntityLiving(rider, pace);
            }
            return;
        }

        // Normal case: step straight after the rider along its own footsteps.
        Vec3d aim = rider.mountState.leadTrailPointBehind(leadGap);
        double ax = aim != null ? aim.x : rider.posX;
        double ay = aim != null ? aim.y : rider.posY;
        double az = aim != null ? aim.z : rider.posZ;
        if (!horse.getNavigator().noPath()) horse.getNavigator().clearPath();
        horse.getMoveHelper().setMoveTo(ax, ay, az, pace);

        double horseDispSq = (horse.posX - horse.prevPosX) * (horse.posX - horse.prevPosX)
                + (horse.posZ - horse.prevPosZ) * (horse.posZ - horse.prevPosZ);
        if (horse.collidedHorizontally && horseDispSq < 0.0016D) {
            int t = rider.mountState.getLedPacerObstacleTicks() + 1;
            rider.mountState.setLedPacerObstacleTicks(t);
            if (t >= TravellersConfig.MOUNTS.ledFollowObstacleTicks) {
                rider.mountState.setLedPacerObstacleTicks(0);
                // Arm the window regardless of whether a search is actually granted this exact tick -
                // a denied search here is picked up by the budgeted retry above on a later tick, the
                // jam signal itself must not be lost.
                rider.mountState.setLedPacerNavUntilTick(rider.ticksExisted + TravellersConfig.MOUNTS.ledFollowPathfindTicks);
                if (com.jubitus.travellers.pathing.PathBudget.tryAcquire(horse.world, com.jubitus.travellers.pathing.PathBudget.Client.MOUNT, rider.getEntityId(), 1)) {
                    horse.getNavigator().tryMoveToEntityLiving(rider, pace);
                }
                com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LED_HORSE_JAMMED);
                com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                        "led horse jammed - pathfinding around");
            }
        } else if (rider.mountState.getLedPacerObstacleTicks() > 0) {
            rider.mountState.setLedPacerObstacleTicks(rider.mountState.getLedPacerObstacleTicks() - 1);
        }
    }

    /** Commands a full stop on a led horse's move helper - it never reverts to WAIT on its own, so a
     * stale MOVE_TO order left in place keeps it walking (and then orbiting) the last aim point. */
    static void haltLedHorseMoveHelper(EntityHorse horse) {
        horse.getMoveHelper().setMoveTo(horse.posX, horse.posY, horse.posZ, 0.0D);
    }

    /**
     * Quick reunite for a rider leading on foot whose horse is snagged on terrain well behind it but
     * not yet at the full {@code leadMaxDistance} recall gap. Without this the rider - which now only
     * ever slows, never stops, to lead (see {@link MountedLeadController}) - walks its whole route
     * with the horse stuck {@code ledStuckRecallDistance}..{@code leadMaxDistance} blocks back: too
     * far for the horse to catch up on its own, too near to trigger the leadMax recall, so the pair
     * only reunites when some unrelated AI drags the rider far enough. Progress is measured as the
     * gap actually shrinking; mere jostling doesn't reset it. The recall drops the horse back onto
     * the rider's own trail a step behind it (see {@link #resolveMountSpawnPos}). Returns
     * {@code true} when it recalled the horse this tick.
     */
    static boolean considerLedStuckRecall(EntityTraveller rider, EntityHorse horse, double distSq) {
        double recallDist = TravellersConfig.MOUNTS.ledStuckRecallDistance;
        if (distSq <= recallDist * recallDist) {
            rider.mountState.setLedHorseStuckTicks(0);
            rider.mountState.setLedHorseBestGapSq(distSq);
            return false;
        }
        double best = rider.mountState.getLedHorseBestGapSq();
        if (distSq < best - 1.0D) {            // gap genuinely closing
            rider.mountState.setLedHorseBestGapSq(distSq);
            rider.mountState.setLedHorseStuckTicks(0);
            return false;
        }
        int stuck = rider.mountState.getLedHorseStuckTicks() + 1;
        rider.mountState.setLedHorseStuckTicks(stuck);
        if (stuck < TravellersConfig.MOUNTS.ledStuckRecallTicks) return false;

        rider.mountState.setLedHorseStuckTicks(0);
        rider.mountState.setLedHorseBestGapSq(Double.MAX_VALUE);
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LED_HORSE_SNAG_RECALL);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                "led horse snagged behind and not catching up - bringing it to the rider");
        teleportMountNear(rider, horse, null);
        return true;
    }

    /** Brings a horse back beside its rider, on a spot that is actually clear - the fallback every
     * mode escalates to rather than ever letting the pair drift apart permanently. */
    static void teleportMountNear(EntityTraveller rider, EntityHorse horse,
                                          @Nullable com.jubitus.travellers.debug.profile.MovementAnomaly reason) {
        MountBonding.MountSpawnPos pos = MountBonding.resolveMountSpawnPos(rider, horse);
        if (TravellersConfig.MOUNTS.debugMounts) {
            FMLLog.log(Level.INFO, "[Travellers/Mounts] %s recalling its horse from %.1f blocks away (mode=%s)",
                    rider.getName(), Math.sqrt(horse.getDistanceSq(rider)), rider.mountState.getMountMode());
        }
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_TELEPORT_RECALL);
        // The snag recall counts itself (LED_HORSE_SNAG_RECALL) before calling here.
        if (reason != null) com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, reason);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "recall horse from %.1f blocks (mode=%s)",
                        Math.sqrt(horse.getDistanceSq(rider)), rider.mountState.getMountMode()));
        horse.motionX = 0;
        horse.motionY = 0;
        horse.motionZ = 0;
        horse.setPositionAndUpdate(pos.x, pos.y, pos.z);
    }
}
