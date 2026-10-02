package com.jubitus.travellers.mount;

import com.jubitus.travellers.world.ground.BlockRules;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nullable;

/**
 * The AI-side entry point every driver shares for "a mounted rider must step off and lead its horse
 * in on foot" - water it cannot ford, or (via {@link TravellerMountSupport}) terrain the horse
 * cannot ride. Used identically by {@code EntityAITravel} and both follow AIs
 * ({@code EntityAIFollowLeader}) so the three can never
 * disagree on when a rider dismounts or how it paces while leading.
 *
 * <p>Deliberately tiny. It decides the intent ("un-fordable water ahead => {@link MountMode#LED}")
 * and the rider's own walking pace while leading; it never moves the horse. Everything after the
 * mode flip - swimming, the led-horse pace/tow, remounting once both are back on dry ground - is
 * {@code TravellerMountSupport.reconcileOnFoot}, the sole authority over a bonded horse. No crossing
 * state machine.
 *
 * <p>There is no stop-and-wait: a rider leading on foot only ever <em>slows</em>
 * ({@code MOUNTS.ledRiderPaceFactor}), and the horse is given its own full travel pace to close the
 * gap (see {@code TravellerMountSupport.driveLedHorse}). An earlier full-stop hold deadlocked riders
 * whose horse got stuck just out of catch-up range.
 */
public final class MountedLeadController {

    private MountedLeadController() {
    }

    /** Straight-line probe distance (blocks) for un-fordable water ahead on the bearing to the aim. */
    public static final int LOOKAHEAD = 6;

    /** Ford depth this body can wade without swimming. */
    private static int fordDepth() {
        return Math.max(1, TravellersConfig.MOUNTS.mountedMaxFordDepth);
    }

    /**
     * Probes one column for the surface a body would stand on and how much water sits on top of it.
     * Returns {@code {surfaceY, waterDepth}} (waterDepth 0 = dry), or {@code null} if the column is
     * unloaded or is dry air all the way down. A water column with no floor in range (deep ocean)
     * reports a very large depth.
     */
    @Nullable
    private static int[] columnSurface(EntityTraveller mob, int bx, int bz, int topY, int bottomY) {
        if (!mob.world.isBlockLoaded(new BlockPos(bx, MathHelper.clamp((topY + bottomY) / 2, 1, 255), bz))) {
            return null;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int waterTop = Integer.MIN_VALUE;
        for (int y = topY; y >= bottomY; y--) {
            p.setPos(bx, y, bz);
            IBlockState s = mob.world.getBlockState(p);
            if (BlockRules.isVanillaWater(s)) {
                if (waterTop == Integer.MIN_VALUE) waterTop = y;
                continue;
            }
            AxisAlignedBB bb = s.getCollisionBoundingBox(mob.world, p);
            if (bb == null) continue; // air / non-solid: keep falling
            int surfaceY = y + 1;
            int depth = (waterTop == Integer.MIN_VALUE) ? 0 : (waterTop - y);
            return new int[]{surfaceY, depth};
        }
        if (waterTop != Integer.MIN_VALUE) return new int[]{bottomY, 99};
        return null;
    }

    /**
     * True when un-fordable vanilla water sits on the straight XZ line ({@code dir}, normalised)
     * toward the caller's aim within {@link #LOOKAHEAD} blocks - or the rider is already in water
     * deeper than it can ford. Two samples a block and a half apart must agree, so a puddle beside
     * the route doesn't trip it. {@code dir} may be {@code null} (no aim resolvable) - then only the
     * "already wet" case counts. Never trips while in a boat or in combat.
     */
    public static boolean waterAheadNeedsLeading(EntityTraveller mob, @Nullable double[] dir) {
        if (mob.isRiding() && mob.getRidingEntity() instanceof EntityBoat) return false;
        if (mob.isInCombat()) return false;
        if (dir == null) return mob.isInWater();

        int topY = MathHelper.ceil(mob.posY) + 2;
        int botY = MathHelper.floor(mob.posY) - 10;
        int ford = fordDepth();

        if (mob.isInWater()) {
            int[] here = columnSurface(mob, MathHelper.floor(mob.posX), MathHelper.floor(mob.posZ), topY, botY);
            if (here != null && here[1] > ford) return true;
        }

        for (double d = 1.0; d <= LOOKAHEAD; d += 0.5) {
            int bx = MathHelper.floor(mob.posX + dir[0] * d);
            int bz = MathHelper.floor(mob.posZ + dir[1] * d);
            int[] a = columnSurface(mob, bx, bz, topY, botY);
            if (a == null || a[1] <= ford) continue;
            int cx = MathHelper.floor(mob.posX + dir[0] * (d + 1.5));
            int cz = MathHelper.floor(mob.posZ + dir[1] * (d + 1.5));
            int[] b = columnSurface(mob, cx, cz, topY, botY);
            if (b != null && b[1] > ford) return true;
        }
        return false;
    }

    /**
     * Steps a bonded-mounted rider off into {@link MountMode#LED} for a water crossing and arms the
     * shared time+distance self-heal back to {@link MountMode#MOUNTED} (see
     * {@code TravellerMountSupport.markTerrainFallbackLed} / {@code considerTerrainFallbackRemount}
     * and the sustained strict-dry gate in {@code reconcileOnFoot}). Safe to call every probe tick -
     * a no-op once already in LED.
     */
    public static void enterLedForWater(EntityTraveller mob) {
        if (mob.getMountMode() == MountMode.LED) return;
        com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(mob, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.WATER);
        TravellerMountSupport.setMountMode(mob, MountMode.LED);
        TravellerMountSupport.markTerrainFallbackLed(mob);
        TravellerTracer.event(mob, "mount", "un-fordable water ahead - leading the horse in on foot");
    }

    /**
     * Speed factor for a rider leading its bonded horse on foot over <em>dry</em> ground: hang back
     * ({@code MOUNTS.ledRiderPaceFactor}) so the leash isn't dragging the horse. Returns {@code 1.0}
     * while swimming (there the horse is towed and the rider crosses at full pace), while riding
     * anything, or whenever the rider isn't in a bonded-mount LED spell. Applied identically by
     * {@code EntityAITravel} and both follow AIs.
     */
    public static double ledRiderPaceMultiplier(EntityTraveller mob) {
        if (!isLeadingHorseOnDryFoot(mob)) return 1.0D;
        return TravellersConfig.MOUNTS.ledRiderPaceFactor;
    }

    static boolean isLeadingHorseOnDryFoot(EntityTraveller mob) {
        return !mob.isRiding()
                && !mob.isInWater()
                && mob.getMountUUID() != null
                && mob.getMountMode() == MountMode.LED;
    }
}
