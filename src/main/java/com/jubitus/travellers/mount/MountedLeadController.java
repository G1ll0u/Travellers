package com.jubitus.travellers.mount;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nullable;

/**
 * The rider's own walking pace while leading its bonded horse on foot, shared by
 * {@code EntityAITravel} and {@code EntityAIFollowLeader} so they never disagree. It never moves the
 * horse: that is {@code MountModes.reconcileOnFoot}, the sole authority over a bonded horse.
 *
 * <p>There is no stop-and-wait: a rider leading on foot only ever <em>slows</em>
 * ({@code MOUNTS.ledRiderPaceFactor}), and the horse is given its own full travel pace to close the
 * gap (see {@code LedHorseDriver.driveLedHorse}).
 */
public final class MountedLeadController {

    private MountedLeadController() {
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
                && mob.mountState.getMountMode() == MountMode.LED;
    }
}
