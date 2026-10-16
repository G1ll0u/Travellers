package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZonePlacement;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import static com.jubitus.travellers.zone.stable.StableHandSupport.*;

/** Palfrenier break, night and idle flavour phases of {@link StableHandTask}. */
final class StableAmbience {

    private static final double SLEEP_ARRIVE_SQ = 4.0D;
    private static final double SLEEP_TELEPORT_MAX_SQ = 14.0D * 14.0D;
    private static final long SLEEP_APPROACH_TIMEOUT = 100L;
    private static final double BREAK_LOOK_RANGE = 10.0D;

    private final StableHandSupport support;

    StableAmbience(StableHandSupport support) {
        this.support = support;
    }

    /** ON_BREAK: amble to a fresh spot in the pen now and then, and look about naturally - at a
     * nearby living thing if there is one, otherwise the horizon. No chores, no chest, no work scan. */
    void tickBreak(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           PalfrenierState st, long now) {
        support.clearHeldItem(worker);
        if (now >= st.breakNextMoveTick) {
            int iv = TravellersConfig.STABLES.breakWanderIntervalTicks;
            st.breakNextMoveTick = now + iv + worker.getRNG().nextInt(Math.max(1, iv));
            BlockPos spot = randomStandableInZone(world, zone, worker);
            if (spot != null) {
                worker.getNavigator().tryMoveToXYZ(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, com.jubitus.travellers.unit.UnitSpeed.nav(com.jubitus.travellers.unit.UnitSpeed.STROLL_PERCENT));
            }
            double angle = (Math.PI * 2D) * worker.getRNG().nextDouble();
            st.idleLookX = Math.cos(angle);
            st.idleLookZ = Math.sin(angle);
            EntityLivingBase look = worker.getRNG().nextBoolean() ? nearestLookable(world, worker) : null;
            st.breakLookUUID = look != null ? look.getUniqueID() : null;
        }
        Entity look = st.breakLookUUID != null ? world.getEntityFromUuid(st.breakLookUUID) : null;
        if (look instanceof EntityLivingBase && look.isEntityAlive()
                && worker.getDistanceSq(look) <= BREAK_LOOK_RANGE * BREAK_LOOK_RANGE) {
            worker.getLookHelper().setLookPositionWithEntity(look, 30.0F, 30.0F);
        } else {
            worker.getLookHelper().setLookPosition(worker.posX + st.idleLookX,
                    worker.posY + worker.getEyeHeight(), worker.posZ + st.idleLookZ,
                    (float) worker.getHorizontalFaceSpeed(), (float) worker.getVerticalFaceSpeed());
        }
    }

    BlockPos randomStandableInZone(WorldServer world, PlayerZoneRecord zone, EntityTravellerWorker worker) {
        for (int tries = 0; tries < 6; tries++) {
            double rx = zone.center.getX() + 0.5D + (worker.getRNG().nextDouble() * 2.0D - 1.0D) * zone.halfWidth;
            double rz = zone.center.getZ() + 0.5D + (worker.getRNG().nextDouble() * 2.0D - 1.0D) * zone.halfDepth;
            BlockPos spot = ZonePlacement.findHitboxStandableNear(world, rx, zone.center.getY(), rz, worker);
            if (spot != null) return spot;
        }
        return null;
    }

    EntityLivingBase nearestLookable(WorldServer world, EntityTravellerWorker worker) {
        AxisAlignedBB box = worker.getEntityBoundingBox().grow(BREAK_LOOK_RANGE, 3.0D, BREAK_LOOK_RANGE);
        EntityLivingBase best = null;
        double bestSq = Double.MAX_VALUE;
        for (EntityLivingBase e : world.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (e == worker || !e.isEntityAlive()) continue;
            double d = worker.getDistanceSq(e);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    void tickNight(EntityTravellerWorker worker, PalfrenierState st, BlockPos bed, long now) {
        if (worker.sleep.isSleeping()) {
            st.phase = PalfrenierState.Phase.SLEEP;
            return;
        }
        if (st.phase != PalfrenierState.Phase.GO_TO_BED) {
            st.phase = PalfrenierState.Phase.GO_TO_BED;
            st.bedApproachStartTick = now;
            st.targetHorseUUID = null;
            support.clearHeldItem(worker);
            support.logHand(worker, "heading to bed");
        }
        worker.getLookHelper().setLookPosition(bed.getX() + 0.5D, bed.getY() + 0.5D, bed.getZ() + 0.5D, 30.0F, 30.0F);
        double dsq = worker.getDistanceSq(bed.getX() + 0.5D, bed.getY() + 0.5D, bed.getZ() + 0.5D);
        if (dsq <= SLEEP_ARRIVE_SQ) {
            if (worker.sleep.trySleepIn(bed)) {
                st.phase = PalfrenierState.Phase.SLEEP;
                worker.getNavigator().clearPath();
            }
            return;
        }
        // Beds have partial collision and are usually boxed in - pathfinding often stalls just short,
        // or returns a best-effort route that ends outside the bedroom wall. After the timeout, lie
        // down anyway if within teleport range (trySleepInBed snaps to the bed's own spot).
        if (now - st.bedApproachStartTick > SLEEP_APPROACH_TIMEOUT && dsq <= SLEEP_TELEPORT_MAX_SQ) {
            if (worker.sleep.trySleepIn(bed)) {
                st.phase = PalfrenierState.Phase.SLEEP;
                worker.getNavigator().clearPath();
            }
            return;
        }
        if (worker.getNavigator().noPath()) {
            worker.getNavigator().tryMoveToXYZ(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, com.jubitus.travellers.unit.UnitSpeed.travelNav());
        }
    }

    void ambientPetHorse(EntityTravellerWorker worker, PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(worker.world instanceof WorldServer ? (WorldServer) worker.world : null, st);
        if (horse == null) {
            support.reset(st);
            return;
        }
        if (st.approachStartTick >= 0L && now - st.approachStartTick > APPROACH_TIMEOUT * 2
                && worker.getDistanceSq(horse) > INTERACT_RANGE_SQ) {
            support.reset(st);
            return;
        }
        if (!support.approachWorkSpot(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        if (st.phaseEndTick == 0L) {
            st.phaseEndTick = now + 20L + worker.getRNG().nextInt(20);
            return;
        }
        if (now < st.phaseEndTick) return;
        worker.doInteractSwing(EnumHand.MAIN_HAND);
        support.reset(st);
    }

    /** A genuine break beat: stand still and look off in a random direction, exactly like vanilla's
     * own {@code EntityAILookIdle} (which never runs for a worker on duty). Re-set every tick. */
    void ambientIdlePause(EntityTravellerWorker worker, PalfrenierState st, long now) {
        if (st.phaseEndTick == 0L) {
            double angle = (Math.PI * 2D) * worker.getRNG().nextDouble();
            st.idleLookX = Math.cos(angle);
            st.idleLookZ = Math.sin(angle);
            st.phaseEndTick = now + 40L + worker.getRNG().nextInt(60);
        }
        worker.getLookHelper().setLookPosition(worker.posX + st.idleLookX, worker.posY + worker.getEyeHeight(),
                worker.posZ + st.idleLookZ, (float) worker.getHorizontalFaceSpeed(), (float) worker.getVerticalFaceSpeed());
        if (now < st.phaseEndTick) return;
        support.reset(st);
    }
}
