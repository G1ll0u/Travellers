package com.jubitus.travellers.gaze;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityLookHelper;
import net.minecraft.util.math.MathHelper;

/**
 * The only thing that turns a traveller's head each tick. An AI task asking for a look this tick
 * (vanilla {@code setLookPosition}) wins; otherwise {@link TravellerGaze} picks the target. The head
 * eases in and out of a turn instead of snapping, and pitch is kept between ticks (vanilla resets it).
 */
public class TravellerLookHelper extends EntityLookHelper {

    private static final float EASE = 0.35F;
    private static final float ACCEL = 0.5F;
    private static final float WALK_HEAD_LIMIT = 70.0F;
    private static final float PITCH_LIMIT = 50.0F;

    private final EntityTraveller mob;
    private final TravellerGaze gaze;

    private boolean requested;
    private double reqX, reqY, reqZ;
    private float reqYawSpeed, reqPitchSpeed;

    private float yawVel;
    private float pitchVel;

    public TravellerLookHelper(EntityTraveller mob, TravellerGaze gaze) {
        super(mob);
        this.mob = mob;
        this.gaze = gaze;
    }

    @Override
    public void setLookPositionWithEntity(Entity e, float yawSpeed, float pitchSpeed) {
        double y = e instanceof EntityLivingBase
                ? e.posY + e.getEyeHeight()
                : (e.getEntityBoundingBox().minY + e.getEntityBoundingBox().maxY) / 2.0D;
        setLookPosition(e.posX, y, e.posZ, yawSpeed, pitchSpeed);
    }

    @Override
    public void setLookPosition(double x, double y, double z, float yawSpeed, float pitchSpeed) {
        requested = true;
        reqX = x;
        reqY = y;
        reqZ = z;
        reqYawSpeed = yawSpeed;
        reqPitchSpeed = pitchSpeed;
    }

    @Override
    public void onUpdateLook() {
        boolean task = requested;
        requested = false;
        if (mob.world.isRemote || mob.sleep.isSleeping()) return;

        boolean combat = mob.getAttackTarget() != null || mob.isInCombat();
        // Something already turned the head directly this tick (bow, fishing...): leave it be.
        if (!task && (combat || mob.hasHeadWriterThisTick())) return;

        TravellersConfig.Gaze cfg = TravellersConfig.GAZE;
        float speed = (float) cfg.headTurnSpeed;
        double x, y, z;
        boolean relax = false;
        if (task) {
            gaze.noteTask();
            x = reqX;
            y = reqY;
            z = reqZ;
            if (combat) speed = Math.max(speed, reqYawSpeed);
            else speed = Math.min(reqYawSpeed, speed * 2.0F);
        } else if (gaze.resolve()) {
            x = gaze.tx;
            y = gaze.ty;
            z = gaze.tz;
            TravellerGaze.Kind k = gaze.kind();
            if (k == TravellerGaze.Kind.GLANCE || k == TravellerGaze.Kind.INTEREST) speed *= 1.5F;
        } else {
            relax = true;
            x = y = z = 0.0D;
            speed *= 0.6F;
        }

        float yaw;
        float pitch;
        if (relax) {
            yaw = mob.renderYawOffset;
            pitch = 0.0F;
        } else {
            double dx = x - mob.posX;
            double dy = y - (mob.posY + mob.getEyeHeight());
            double dz = z - mob.posZ;
            double flat = MathHelper.sqrt(dx * dx + dz * dz);
            if (flat < 0.05D && Math.abs(dy) < 0.05D) return;
            yaw = (float) (MathHelper.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
            pitch = MathHelper.clamp((float) -(MathHelper.atan2(dy, flat) * (180.0D / Math.PI)), -PITCH_LIMIT, PITCH_LIMIT);
        }

        float yawStep = MathHelper.clamp(MathHelper.wrapDegrees(yaw - mob.rotationYawHead) * EASE, -speed, speed);
        yawVel += (yawStep - yawVel) * ACCEL;
        mob.rotationYawHead += yawVel;

        float pitchStep = MathHelper.clamp((pitch - mob.rotationPitch) * EASE, -speed, speed);
        pitchVel += (pitchStep - pitchVel) * ACCEL;
        mob.rotationPitch += pitchVel;

        // Walking or riding, the head can't twist further than a person's neck allows.
        if (mob.isRiding() || !mob.getNavigator().noPath()) {
            float rel = MathHelper.wrapDegrees(mob.rotationYawHead - mob.rotationYaw);
            if (rel > WALK_HEAD_LIMIT) mob.rotationYawHead = mob.rotationYaw + WALK_HEAD_LIMIT;
            else if (rel < -WALK_HEAD_LIMIT) mob.rotationYawHead = mob.rotationYaw - WALK_HEAD_LIMIT;
        }
        mob.noteHeadWriter("gaze");
    }
}
