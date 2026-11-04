package com.jubitus.travellers.unit;

import com.jubitus.travellers.ground.Footing;
import net.minecraft.util.math.BlockPos;

/**
 * Sitting on the ground: a cosmetic pose any behaviour may ask for, synced for render and never
 * persisted (a unit always comes back from offscreen standing). The pose holds itself: it ends on
 * its own the moment the unit has anything else to do, so no caller can leave a unit sitting.
 */
public final class TravellerSit {

    /** How far the seated body sits below standing height, in model units of a scale-1 unit. */
    public static final double SEATED_DROP = 8.0D / 16.0D;

    private final EntityTraveller unit;
    // Server-only, like the sleep anchor: re-applied every tick so pushes never slide a sitter.
    private double anchorX, anchorY, anchorZ;
    private long standTick;

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
        if (unit.world.isRemote || !isSitting()) return;
        unit.getDataManager().set(EntityTraveller.SITTING, false);
    }

    /** Server, every tick after the vanilla update: stands up the moment sitting no longer fits, else stays pinned. */
    void tick() {
        if (!isSitting()) return;
        // Anything asking it to walk (a path, or the move helper driven directly) gets it up first.
        if (!canSitNow() || unit.world.getTotalWorldTime() >= standTick || !unit.getNavigator().noPath()
                || unit.moveForward != 0.0F || unit.hurtTime > 0 || unit.isInWater()
                || !Footing.canStandAt(unit.world, new BlockPos(unit))) {
            stand();
            return;
        }
        unit.motionX = 0.0D;
        unit.motionY = 0.0D;
        unit.motionZ = 0.0D;
        if (unit.posX != anchorX || unit.posY != anchorY || unit.posZ != anchorZ) unit.setPosition(anchorX, anchorY, anchorZ);
    }

    private boolean canSitNow() {
        return unit.isEntityAlive() && !unit.isRiding() && !unit.isBeingRidden() && !unit.sleep.isSleeping()
                && !unit.elytraFlight.isActive() && !unit.isInCombat() && unit.getAttackTarget() == null
                && !unit.isHandActive() && !unit.isAutoEating();
    }
}
