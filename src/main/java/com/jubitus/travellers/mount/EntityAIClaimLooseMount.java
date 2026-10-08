package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * An unmounted hired worker with nothing more urgent to do notices a nearby loose ex-mount horse -
 * one a dead traveller/raider/worker left behind, already gone wild and not yet claimed by a player
 * or another worker (see {@link OrphanMountCleanup}) - and walks over to bond with it.
 *
 * <p>Lowest priority, sharing the move+look mutex every other worker movement task uses, so travel,
 * follow, duty and combat all pre-empt it for free - this only ever runs when the worker is
 * genuinely idle, with no "is busy" check needed here.
 *
 * <p>Deliberately walks first, mounts second: {@link TravellerMountSupport#bondToLiveHorse} only
 * tags the bond once the worker is already standing next to the horse, so the actual climb-on goes
 * through the same gradual leash-then-remount path every returning bonded rider uses (the
 * {@code MOUNTED} case of {@code MountModes.reconcileOnFoot}) - never a teleport-to-mount.
 */
public class EntityAIClaimLooseMount extends EntityAIBase {

    private static final double MOUNT_RANGE_SQ = 4.0D;
    private static final int REPATH_INTERVAL_TICKS = 10;

    private final EntityTravellerWorker worker;
    private EntityHorse target;

    public EntityAIClaimLooseMount(EntityTravellerWorker worker) {
        this.worker = worker;
        this.setMutexBits(1 | 2);
    }

    @Override
    public boolean shouldExecute() {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        if (!worker.isHiredFollower()) return false;
        if (worker.getMountUUID() != null) return false;
        if (worker.isInCombat()) return false;
        int interval = Math.max(1, TravellersConfig.MOUNTS.looseMountClaimScanIntervalTicks);
        if (worker.ticksExisted % interval != 0) return false;

        target = findNearestLooseMount();
        return target != null;
    }

    @Override
    public boolean shouldContinueExecuting() {
        return target != null && !target.isDead && worker.getMountUUID() == null
                && !worker.isInCombat() && HorseMarkers.isOrphaned(target);
    }

    @Override
    public void startExecuting() {
        moveToward();
    }

    @Override
    public void updateTask() {
        if (worker.getDistanceSq(target) <= MOUNT_RANGE_SQ) {
            TravellerMountSupport.bondToLiveHorse(worker, target);
            return;
        }
        if (worker.ticksExisted % REPATH_INTERVAL_TICKS == 0) moveToward();
    }

    @Override
    public void resetTask() {
        target = null;
    }

    private void moveToward() {
        worker.getNavigator().tryMoveToEntityLiving(target, 1.0D);
    }

    private EntityHorse findNearestLooseMount() {
        World world = worker.world;
        double radius = TravellersConfig.MOUNTS.looseMountClaimRadius;
        AxisAlignedBB box = worker.getEntityBoundingBox().grow(radius);

        EntityHorse best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityHorse h : world.getEntitiesWithinAABB(EntityHorse.class, box)) {
            if (h.isDead || HorseMarkers.isBondedMount(h) || !HorseMarkers.isOrphaned(h)) continue;
            double distSq = worker.getDistanceSq(h);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = h;
            }
        }
        return best;
    }
}
