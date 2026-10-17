package com.jubitus.travellers.follow;

import com.jubitus.travellers.unit.EntityTravellerBoat;
import com.jubitus.travellers.unit.LandingSpots;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The server-side hops of a hands-off follow: boarding the unit's boat, leaving it, and the
 * last-resort rescue when the player cannot catch up. The session stays active after each hop.
 */
final class PlayerFollowTransport {

    private static final int BOAT_SEATS = 2;
    private static final double RESCUE_MIN_DISTANCE = 12.0D;
    private static final double PROGRESS_BLOCKS = 1.0D;
    private static final long RESCUE_AFTER_TICKS = 300L;
    private static final int LANDING_RADIUS = 4;
    private static final int LANDING_VERTICAL = 2;

    private PlayerFollowTransport() {
    }

    /** Progress bookkeeping for one session; {@link PlayerFollowSessions} owns the instance. */
    static final class Progress {
        private double bestDistance = Double.MAX_VALUE;
        private long lastProgressTick;
    }

    static void tick(EntityPlayerMP player, Entity target, Progress progress, long now) {
        Entity targetBoat = target.getRidingEntity() instanceof EntityTravellerBoat ? target.getRidingEntity() : null;
        Entity playerRide = player.getRidingEntity();

        if (targetBoat != null) {
            if (playerRide != targetBoat && targetBoat.getPassengers().size() < BOAT_SEATS) {
                if (playerRide != null) player.dismountRidingEntity();
                player.startRiding(targetBoat);
                resetProgress(progress, now);
            }
            return;
        }
        if (playerRide instanceof EntityTravellerBoat) {
            player.dismountRidingEntity();
            hopBeside(player, target);
            resetProgress(progress, now);
            return;
        }
        if (playerRide != null) return; // on something else (own horse, own boat): not ours to move

        double dist = player.getDistance(target);
        if (dist <= RESCUE_MIN_DISTANCE || dist < progress.bestDistance - PROGRESS_BLOCKS) {
            progress.bestDistance = dist;
            progress.lastProgressTick = now;
        } else if (now - progress.lastProgressTick >= RESCUE_AFTER_TICKS) {
            hopBeside(player, target);
            resetProgress(progress, now);
        }
    }

    private static void resetProgress(Progress progress, long now) {
        progress.bestDistance = Double.MAX_VALUE;
        progress.lastProgressTick = now;
    }

    private static void hopBeside(EntityPlayerMP player, Entity target) {
        World world = player.world;
        BlockPos spot = LandingSpots.nearest(world, target.getPosition(), LANDING_RADIUS, LANDING_VERTICAL,
                pos -> fitsPlayer(world, player, pos));
        if (spot == null) return; // nowhere safe yet: try again next check
        player.setPositionAndUpdate(spot.getX() + 0.5D, LandingSpots.feetY(world, spot), spot.getZ() + 0.5D);
    }

    private static boolean fitsPlayer(World world, EntityPlayerMP player, BlockPos feet) {
        if (!LandingSpots.isSafe(world, feet, null, false, false)) return false;
        double y = LandingSpots.feetY(world, feet);
        AxisAlignedBB box = player.getEntityBoundingBox()
                .offset(feet.getX() + 0.5D - player.posX, y - player.posY, feet.getZ() + 0.5D - player.posZ);
        return world.getCollisionBoxes(player, box).isEmpty() && !LandingSpots.touchesHazard(world, box);
    }
}
