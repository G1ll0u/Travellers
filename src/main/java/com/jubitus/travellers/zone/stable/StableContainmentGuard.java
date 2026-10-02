package com.jubitus.travellers.zone.stable;

import net.minecraft.util.math.AxisAlignedBB;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.zone.ZonePlacement;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.BlockPos;

/**
 * Hard backstop for horse containment, independent of AI intent (see {@link EntityAIStableGraze}
 * for the "chooses not to leave" half). Whatever the cause - combat knockback, water drift, a shove
 * from another mob - a tracked horse found outside its zone's box is nudged back, or if far enough
 * out, teleported straight to the zone's center. This is what makes "never leaves the zone" a
 * guarantee rather than best-effort: it doesn't matter why the horse got out.
 */
public final class StableContainmentGuard {

    private StableContainmentGuard() {
    }

    /** How far outside the (margin-expanded) box a horse must drift before a nudge escalates to an
     * outright teleport, rather than just being pushed back toward center. */
    private static final double TELEPORT_ESCALATION_BLOCKS = 4.0D;

    public static void enforce(EntityHorse horse, PlayerZoneRecord zone) {
        // Never move a horse someone is riding or leading: the rider or holder would be dragged along.
        if (horse.isBeingRidden() || horse.getLeashed()) return;
        BlockPos center = zone.center;
        AxisAlignedBB box = zone.box(TravellersConfig.STABLES.containmentMarginBlocks);
        double minX = box.minX, maxX = box.maxX, minZ = box.minZ, maxZ = box.maxZ;

        double x = horse.posX, z = horse.posZ;
        double outX = x < minX ? minX - x : (x > maxX ? x - maxX : 0);
        double outZ = z < minZ ? minZ - z : (z > maxZ ? z - maxZ : 0);
        if (outX <= 0 && outZ <= 0) return;

        double overshoot = Math.max(outX, outZ);
        if (overshoot >= TELEPORT_ESCALATION_BLOCKS) {
            horse.motionX = 0;
            horse.motionY = 0;
            horse.motionZ = 0;
            // Land on a real standable column near the pen centre, not the raw centre block - a
            // Stable centre can sit inside a wall or on a fence post and drop the horse into suffocation.
            BlockPos safe = ZonePlacement.findStandableNear(horse.world, center, 3);
            if (safe == null) safe = center;
            horse.setPositionAndUpdate(safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
            return;
        }

        // Gentle nudge back toward the zone center - handles the common case (grazing drifted a
        // little past the line) without a jarring teleport for something the horse's own next graze
        // cycle would correct anyway.
        double dx = center.getX() + 0.5D - x;
        double dz = center.getZ() + 0.5D - z;
        double len = Math.max(1.0E-4D, Math.hypot(dx, dz));
        horse.motionX += (dx / len) * 0.15D;
        horse.motionZ += (dz / len) * 0.15D;
    }
}
