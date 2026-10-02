package com.jubitus.travellers.entity.entityai.gaze;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityBodyHelper;
import net.minecraft.util.math.MathHelper;

/**
 * Keeps the body under the head, on both sides. Standing, the body turns after the head once it has
 * twisted far enough, and server-side {@code rotationYaw} follows it: that is the only body heading a
 * client receives, and vanilla snaps the body back onto it (clamping the head to 75 degrees) at the
 * slightest movement - which left a traveller looking over its shoulder at a player behind it.
 */
public class TravellerBodyHelper extends EntityBodyHelper {

    private static final float NECK_LIMIT = 75.0F;
    private static final float SETTLED_ANGLE = 8.0F;

    private final EntityTraveller mob;
    private boolean turning;

    public TravellerBodyHelper(EntityTraveller mob) {
        super(mob);
        this.mob = mob;
    }

    @Override
    public void updateRenderAngles() {
        if (mob.isRiding() && !mob.world.isRemote && mob.isMountedOnBondedMount()) {
            turnMountTowardFocus();
            super.updateRenderAngles();
            return;
        }
        if (mob.isRiding() || mob.isElytraFlying() || mob.isSleepingInBed()) {
            turning = false;
            super.updateRenderAngles();
            return;
        }
        double dx = mob.posX - mob.prevPosX;
        double dz = mob.posZ - mob.prevPosZ;
        if (dx * dx + dz * dz > 2.5E-7D) {
            turning = false;
            mob.renderYawOffset = mob.rotationYaw;
            mob.rotationYawHead = clampAround(mob.renderYawOffset, mob.rotationYawHead);
            return;
        }

        TravellersConfig.Gaze cfg = TravellersConfig.GAZE;
        float diff = MathHelper.wrapDegrees(mob.rotationYawHead - mob.renderYawOffset);
        if (Math.abs(diff) > cfg.bodyFollowAngle) turning = true;
        if (turning) {
            mob.renderYawOffset = EntityTraveller.rotlerp(mob.renderYawOffset, mob.rotationYawHead, (float) cfg.bodyTurnSpeed);
            if (Math.abs(MathHelper.wrapDegrees(mob.rotationYawHead - mob.renderYawOffset)) < SETTLED_ANGLE) turning = false;
        }
        // The neck never twists past its limit: past it the body is dragged along.
        float rel = MathHelper.wrapDegrees(mob.rotationYawHead - mob.renderYawOffset);
        if (rel > NECK_LIMIT) mob.renderYawOffset = mob.rotationYawHead - NECK_LIMIT;
        else if (rel < -NECK_LIMIT) mob.renderYawOffset = mob.rotationYawHead + NECK_LIMIT;

        // With no path the move helper doesn't own the heading: hand the body's to the clients.
        if (!mob.world.isRemote && mob.getNavigator().noPath()) mob.setBodyYaw(mob.renderYawOffset);
    }

    /**
     * A halted rider swings its horse round toward what it is attending to, since the horse copies
     * the rider's {@code rotationYaw} every tick. It settles a little to one side rather than square
     * on, so the horse's own head doesn't block the rider's view. Never for a passing glance.
     */
    private void turnMountTowardFocus() {
        Entity horse = mob.getRidingEntity();
        if (horse == null || !mob.getNavigator().noPath()
                || horse.motionX * horse.motionX + horse.motionZ * horse.motionZ > 0.0004D) {
            turning = false;
            return;
        }
        TravellerGaze gaze = mob.getGaze();
        TravellerGaze.Kind k = gaze.kind();
        if (k == TravellerGaze.Kind.GLANCE) return;
        if (k != TravellerGaze.Kind.INTEREST && k != TravellerGaze.Kind.TALK
                && k != TravellerGaze.Kind.POINT && k != TravellerGaze.Kind.TASK) {
            turning = false;
            return;
        }
        TravellersConfig.Gaze cfg = TravellersConfig.GAZE;
        float diff = MathHelper.wrapDegrees(mob.rotationYawHead - mob.rotationYaw);
        if (Math.abs(diff) > cfg.bodyFollowAngle) turning = true;
        if (!turning) return;
        // Keep the target on the side it is already on.
        float desired = mob.rotationYawHead - Math.signum(diff) * (float) cfg.mountedSideOffset;
        if (Math.abs(MathHelper.wrapDegrees(desired - mob.rotationYaw)) < 3.0F) {
            turning = false;
            return;
        }
        mob.rotationYaw = EntityTraveller.rotlerp(mob.rotationYaw, desired, (float) cfg.mountedTurnSpeed);
    }

    private static float clampAround(float body, float head) {
        float rel = MathHelper.wrapDegrees(head - body);
        if (rel > NECK_LIMIT) return body + NECK_LIMIT;
        if (rel < -NECK_LIMIT) return body - NECK_LIMIT;
        return head;
    }
}
