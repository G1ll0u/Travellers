package com.jubitus.travellers.pathing;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.util.math.MathHelper;

/**
 * Vanilla's move helper, with one addition: a mounted rider turns at a rate a horse can plausibly
 * turn at.
 *
 * <p>{@code EntityMoveHelper} rotates its entity toward the steering target by up to <b>90 degrees
 * per tick</b> ({@code limitAngle(rotationYaw, target, 90.0F)}). On a 0.6-wide villager that is
 * invisible. On a horse it is not: {@code AbstractHorse.travel()} copies the rider's
 * {@code rotationYaw} onto the horse verbatim, with no smoothing of its own, so a long body can spin
 * a quarter turn between two frames - reported as the horse "doing little turns on the spot" and
 * "not looking where it goes".
 *
 * <p>Capping it here rather than at each steering site is deliberate: the move helper is the single
 * place a traveller's yaw is written while it is moving, so nothing can bypass the cap. On foot this
 * class is exactly vanilla, except during a village visit (a gentler cap, see below).
 */
public class TravellerMoveHelper extends EntityMoveHelper {

    private final EntityTraveller rider;

    public TravellerMoveHelper(EntityTraveller entity) {
        super(entity);
        this.rider = entity;
    }

    @Override
    public void onUpdateMoveHelper() {
        float before = this.entity.rotationYaw;
        super.onUpdateMoveHelper();
        if (!this.rider.isMountedOnBondedMount()) {
            // Strolling in a village: round, calm turns instead of vanilla's quarter turn per tick.
            if (this.rider.isVisitingVillage()) {
                this.entity.rotationYaw = limitAngle(before, this.entity.rotationYaw,
                        (float) TravellersConfig.VILLAGE_VISIT.visitBodyTurnSpeed);
            }
            return;
        }

        // Mid-jump the rider must not steer at all. AbstractHorse.travel() copies the rider's yaw
        // onto the horse every tick, and EntityLivingBase.travel applies a jumping body's air
        // control along its CURRENT facing - so a rider that keeps turning while airborne drags the
        // whole arc round with it. Measured in a walled room: the horse launched at a 2-block wall
        // it could clear by a third of a block, turned through the flight, and came down on the
        // inside every time. The launch direction is the flight direction, full stop.
        float jumpYaw = this.rider.mountState.getMountJumpYaw();
        if (!Float.isNaN(jumpYaw)) {
            this.entity.rotationYaw = jumpYaw;
            return;
        }

        float cap = (float) TravellersConfig.MOUNTS.mountedMaxTurnPerTick;
        float desired = this.entity.rotationYaw;
        this.entity.rotationYaw = limitAngle(before, desired, cap);

        // How far the horse still has to turn to face where it is being steered. `desired` was already
        // clamped to 90 deg/tick by super, so this saturates at 90 for anything sharper.
        float yawError = Math.abs(MathHelper.wrapDegrees(desired - before));

        // Sharp turn (a U-turn around a hole, a hard course change): stop the body and pivot on the
        // spot. A capped turn rate while still moving forward is an arc, and at a pit edge that arc
        // carries the horse over the edge before it has finished turning away from it - reported as
        // "the rotation made it fall in". Cutting moveForward to zero (the yaw above still steps at
        // the cap) turns the horse in place, then rides on once realigned. Hysteresis between enter
        // and release angles keeps it from flickering right at the trigger.
        float enterAngle = (float) TravellersConfig.MOUNTS.mountedPivotInPlaceAngle;
        float releaseAngle = (float) TravellersConfig.MOUNTS.mountedPivotReleaseAngle;
        if (this.pivotingInPlace ? yawError > releaseAngle : yawError > enterAngle) {
            this.pivotingInPlace = true;
            this.entity.setAIMoveSpeed(0.0F);
            return;
        }
        this.pivotingInPlace = false;

        if (yawError > cap) {
            slowForTurn(cap);
        }
    }

    /** True while the horse is stopped and rotating on the spot for a turn too sharp to ride through
     * as a curve - see the pivot block in {@link #onUpdateMoveHelper}. Transient, per move helper. */
    private boolean pivotingInPlace = false;

    /**
     * Slows the horse when it is asking for a sharper turn than the cap allows, so that it can
     * actually converge on its target instead of circling it.
     *
     * <p>A capped turn rate is a <b>turn radius</b>: {@code speed / cap}. If that radius is larger
     * than the distance to the steering target, the target sits inside the circle the horse is
     * describing and it can never be reached - the horse orbits it at constant speed, forever. That
     * is not a hypothetical: at 0.66 blocks per tick and a 10 degree cap the radius is 3.8 blocks,
     * and a trace caught a follower galloping a perfect 3.8-block circle around its own formation
     * slot, yaw stepping by exactly -10.0 degrees every single tick without ever arriving.
     *
     * <p>Capping the turn without touching the speed is therefore incomplete by construction. Cutting
     * the speed instead of loosening the cap is also simply what a rider does: you slow down to take
     * a tight turn, then pick the pace back up. Never slows to a stop - a floor keeps it moving so
     * the turn still resolves.
     */
    private void slowForTurn(float cap) {
        net.minecraft.entity.Entity horse = this.rider.getRidingEntity();
        if (horse == null) return;

        double vx = horse.posX - horse.prevPosX;
        double vz = horse.posZ - horse.prevPosZ;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 1.0E-4D) return;

        double dx = this.posX - this.entity.posX;
        double dz = this.posZ - this.entity.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        double radius = speed / Math.toRadians(cap);
        // Half the distance, so the horse curves inside the target rather than grazing it.
        double maxRadius = distance * 0.5D;
        if (radius <= maxRadius) return;

        float scale = (float) Math.max(MIN_TURN_SPEED_FACTOR, maxRadius / radius);
        this.entity.setAIMoveSpeed(this.entity.getAIMoveSpeed() * scale);
    }

    /** A hard turn never brings a horse below this share of its commanded speed. */
    private static final float MIN_TURN_SPEED_FACTOR = 0.2F;
}
