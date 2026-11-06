package com.jubitus.travellers.unit;

import com.jubitus.travellers.ground.Footing;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

/**
 * Sitting on the ground: a cosmetic pose any behaviour may ask for, synced for render and never
 * persisted (a unit always comes back from offscreen standing). The pose holds itself: it ends on
 * its own the moment the unit has anything else to do, so no caller can leave a unit sitting
 * (except the debug photo pose, which holds it until released).
 */
public final class TravellerSit {

    /** How far the seated body sits below standing height, in model units of a scale-1 unit. */
    public static final double SEATED_DROP = 8.0D / 16.0D;
    // How far a posed unit still follows a player with its eyes.
    private static final double POSE_WATCH_RANGE = 32.0D;

    private final EntityTraveller unit;
    // Server-only, like the sleep anchor: re-applied every tick so pushes never slide a sitter.
    private double anchorX, anchorY, anchorZ;
    private long standTick;
    // Server: a photo pose (/travellersdebug pose) keeps it seated until released, whatever else asks.
    private boolean held;

    TravellerSit(EntityTraveller unit) {
        this.unit = unit;
    }

    public boolean isSitting() {
        return unit.getDataManager().get(EntityTraveller.SITTING);
    }

    /** The heading the body keeps while sitting. */
    public float getYaw() {
        return unit.getDataManager().get(EntityTraveller.SIT_YAW);
    }

    /** Sits down where the unit stands, facing {@code yaw}, for {@code ticks}. False when it can't sit here now. */
    public boolean sitFor(float yaw, int ticks) {
        if (unit.world.isRemote || isSitting() || !canSitNow()) return false;
        if (!unit.onGround || !Footing.canStandAt(unit.world, new BlockPos(unit))) return false;
        unit.getNavigator().clearPath();
        unit.motionX = 0.0D;
        unit.motionZ = 0.0D;
        anchorX = unit.posX;
        anchorY = unit.posY;
        anchorZ = unit.posZ;
        standTick = unit.world.getTotalWorldTime() + ticks;
        unit.getDataManager().set(EntityTraveller.SIT_YAW, yaw);
        unit.getDataManager().set(EntityTraveller.SITTING, true);
        return true;
    }

    public void stand() {
        if (unit.world.isRemote || !isSitting() || held) return;
        unit.getDataManager().set(EntityTraveller.SITTING, false);
    }

    /** Photo pose: sits down here facing {@code yaw} until {@link #releaseHold}. False when it can't sit here now. */
    public boolean hold(float yaw) {
        if (held) {
            unit.getDataManager().set(EntityTraveller.SIT_YAW, yaw);
            return true;
        }
        if (!sitFor(yaw, Integer.MAX_VALUE)) return false;
        held = true;
        return true;
    }

    public void releaseHold() {
        if (!held) return;
        held = false;
        stand();
    }

    public boolean isHeld() {
        return held;
    }

    /** Server, every tick after the vanilla update: stands up the moment sitting no longer fits, else stays pinned. */
    void tick() {
        if (!isSitting()) return;
        // Anything asking it to walk (a path, or the move helper driven directly) gets it up first.
        if (!held && (!canSitNow() || unit.world.getTotalWorldTime() >= standTick || !unit.getNavigator().noPath()
                || unit.moveForward != 0.0F || unit.hurtTime > 0 || unit.isInWater()
                || !Footing.canStandAt(unit.world, new BlockPos(unit)))) {
            stand();
            return;
        }
        if (held) watchNearestPlayer();
        unit.motionX = 0.0D;
        unit.motionY = 0.0D;
        unit.motionZ = 0.0D;
        if (unit.posX != anchorX || unit.posY != anchorY || unit.posZ != anchorZ) unit.setPosition(anchorX, anchorY, anchorZ);
    }

    // The photo pose runs with AI off, so nothing else updates the look helper: drive it here.
    private void watchNearestPlayer() {
        EntityPlayer player = unit.world.getClosestPlayerToEntity(unit, POSE_WATCH_RANGE);
        if (player != null) unit.getLookHelper().setLookPositionWithEntity(player, 30.0F, 30.0F);
        unit.getLookHelper().onUpdateLook();
    }

    private boolean canSitNow() {
        return unit.isEntityAlive() && !unit.isRiding() && !unit.isBeingRidden() && !unit.sleep.isSleeping()
                && !unit.elytraFlight.isActive() && !unit.isInCombat() && unit.getAttackTarget() == null
                && !unit.isHandActive() && !unit.isAutoEating();
    }
}
