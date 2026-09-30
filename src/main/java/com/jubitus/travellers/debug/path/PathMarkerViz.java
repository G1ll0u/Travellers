package com.jubitus.travellers.debug.path;

import net.minecraft.entity.EntityAreaEffectCloud;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Drops a tiny stationary particle marker at a point, for {@code /travellersdebug path show}.
 * Same technique as {@code EntityAITravel}'s own (private) waypoint marker: a near-zero-radius
 * {@link EntityAreaEffectCloud}, a normal world entity that ticks down and removes itself - so a
 * player disconnecting or the server restarting never leaves anything behind to clean up.
 */
public final class PathMarkerViz {

    private PathMarkerViz() {
    }

    /** How long a marker lingers, in ticks. Matches the mod's existing waypoint debug marker. */
    private static final int DURATION_TICKS = 5000;

    public static void mark(World world, Vec3d pos, EnumParticleTypes particle) {
        if (world.isRemote) return;
        EntityAreaEffectCloud cloud = new EntityAreaEffectCloud(world, pos.x, pos.y, pos.z);
        cloud.setDuration(DURATION_TICKS);
        cloud.setWaitTime(0);
        cloud.setRadius(0.15F);
        cloud.setRadiusPerTick(0.0F);
        cloud.setParticle(particle);
        world.spawnEntity(cloud);
    }

    public static void mark(World world, BlockPos pos, EnumParticleTypes particle) {
        mark(world, new Vec3d(pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5), particle);
    }
}
