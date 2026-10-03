package com.jubitus.travellers.unit;

/**
 * The one way to move a unit instantly (rescue hop, landing, waking up, unsticking): its path is
 * dropped and its momentum zeroed, so it neither walks back nor jumps on the next tick.
 * {@link EntityTraveller#setPositionAndUpdate} carries a ridden or led horse along and traces the hop.
 */
public final class Relocate {

    private Relocate() {
    }

    public static void teleport(EntityTraveller unit, double x, double y, double z) {
        unit.getNavigator().clearPath();
        unit.setPositionAndUpdate(x, y, z);
        unit.motionX = 0.0D;
        unit.motionY = 0.0D;
        unit.motionZ = 0.0D;
        unit.velocityChanged = true;
    }
}
