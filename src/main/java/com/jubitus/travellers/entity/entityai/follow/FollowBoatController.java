package com.jubitus.travellers.entity.entityai.follow;

import com.jubitus.travellers.ground.BlockRules;
import com.jubitus.travellers.entity.entityai.travel.TravellerBoats;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerBoat;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Shared boat-following logic: a follower keeps pace with a target that may cross water by boat,
 * either boarding the target's own boat (room permitting) or spawning/steering its own alongside it.
 * Used by both worker escort following ({@link EntityAIFollowLeader}) and traveller/raider
 * group following ({@link EntityAIFollowLeader}) - extracted so the two never diverge on boat
 * behavior instead of maintaining two copies.
 *
 * Lifecycle invariants this class enforces everywhere it dismounts/finishes with a boat:
 * - A boat this controller spawned ({@link EntityTravellerBoat}) is always killed on dismount -
 *   never left behind for the world to find abandoned.
 * - The follower never stays mounted once its own tick logic decides the crossing is over.
 */
final class FollowBoatController {

    private static final int BOAT_MAX_PASSENGERS = 2;
    private static final double BOARD_RADIUS = 2.5D;
    private static final double BOARD_RADIUS_SQ = BOARD_RADIUS * BOARD_RADIUS;
    private static final double SEARCH_RADIUS = 12.0D;
    private static final int SPAWN_COOLDOWN_TICKS = 30;
    private static final double BOAT_SLOT_DISTANCE = 4.0D;
    private static final double BOAT_LANE_OFFSET = 1.25D;
    private static final double BOAT_STOP_RADIUS_SQ = 1.2D * 1.2D;
    private static final double OWN_BOAT_PREFER_DIST = 10.0D;
    private static final double WATER_COMMIT_DIST = 18.0D;

    private static final int STRANDED_TIMEOUT_TICKS = 60;
    // Wider than the live-target shore search (4) since there's no target position to bias the
    // search toward - just look harder for any nearby shore before giving up to the stranded timeout.
    private static final int NO_TARGET_SHORE_SEARCH_RADIUS = 10;

    private final EntityTraveller follower;
    private final double baseSpeed;

    private int boatSpawnCooldown;
    private int boatRepathCooldown;
    // Stagger: delay before a follower starts the boat-boarding sequence.
    // Set when the target first boards a boat so a whole group doesn't fire at once.
    private int boatActivationDelay;
    private boolean targetWasInBoat;
    private int strandedTicks;
    private double lastBoatX, lastBoatZ;

    FollowBoatController(EntityTraveller follower, double baseSpeed) {
        this.follower = follower;
        this.baseSpeed = baseSpeed;
    }

    void reset() {
        boatSpawnCooldown = 0;
        boatRepathCooldown = 0;
        boatActivationDelay = 0;
        targetWasInBoat = false;
        strandedTicks = 0;
        lastBoatX = Double.NaN;
        lastBoatZ = Double.NaN;
    }

    boolean shouldHandle(@Nullable EntityLivingBase target) {
        // The follower's own boat (if any) must always be managed to a safe resolution even without
        // a live target to track (e.g. a group leader that virtualized mid-crossing) - never left as
        // an abandoned entity or the follower stuck riding it indefinitely.
        if (follower.isRiding() && follower.getRidingEntity() instanceof EntityBoat) return true;
        return target != null && target.isRiding() && target.getRidingEntity() instanceof EntityBoat;
    }

    /**
     * @param target the live target (leader) entity, or {@code null} when it isn't loaded - e.g. a
     *               group leader that died or virtualized while this follower was still mid-crossing.
     *               With no live target there is nothing to board, convoy alongside, or steer toward
     *               (steering "toward" a synthetic point derived from the follower's own position was
     *               tried and rejected - the near-zero, physics-noise-dominated direction vector made
     *               the boat, and the rider's head, spin erratically every tick). This only ever
     *               manages a boat the follower is <em>already</em> riding, getting it to the nearest
     *               shore and dismounting - it never starts a brand new crossing without a live target.
     */
    boolean tick(@Nullable EntityLivingBase target) {
        if (boatSpawnCooldown > 0) boatSpawnCooldown--;
        if (boatRepathCooldown > 0) boatRepathCooldown--;

        boolean targetInBoat = target != null && target.isRiding() && target.getRidingEntity() instanceof EntityBoat;

        // When the target JUST boarded a boat, stagger each follower's reaction
        // so a whole group doesn't all scan + pathfind in the same tick.
        if (targetInBoat && !targetWasInBoat) {
            // Followers already in water get a tiny delay; others spread over ~4 seconds.
            int maxDelay = follower.isInWater() ? 10 : 80;
            boatActivationDelay = 5 + Math.abs(follower.getEntityId() * 13) % maxDelay;
        }
        targetWasInBoat = targetInBoat;

        if (boatActivationDelay > 0) {
            boatActivationDelay--;
            // Return false: let the normal follow AI handle this tick (follower keeps walking).
            return false;
        }

        EntityBoat myBoat = follower.isRiding() && follower.getRidingEntity() instanceof EntityBoat
                ? (EntityBoat) follower.getRidingEntity()
                : null;

        if (target == null) {
            if (myBoat == null) return false;
            return handleStrandedBoatNoTarget(myBoat);
        }

        EntityBoat targetBoat = targetInBoat
                ? (EntityBoat) target.getRidingEntity()
                : null;

        if (targetBoat == null) {
            if (myBoat == null) return false;
            return handleBoatWithoutTargetBoat(myBoat, target.posX, target.posZ, target.isInWater());
        }

        if (myBoat == targetBoat) {
            follower.getNavigator().clearPath();
            follower.setSprinting(false);
            return true;
        }

        if (myBoat != null) {
            return steerOwnBoat(target, targetBoat, myBoat);
        }

        // Throttle the expensive boarding logic (AABB scan + 16×16 water scan + pathfind).
        if (boatRepathCooldown > 0) {
            return true;
        }

        return boardOrCreateBoatImmediately(target, targetBoat);
    }

    /**
     * No live target at all to track - just get this already-mounted boat to the nearest shore and
     * dismount+kill it, without ever steering "toward" a synthetic target point. Retries the (wider)
     * shore search every tick; if none is found before {@link #STRANDED_TIMEOUT_TICKS}, force-dismounts
     * regardless (same accepted last-resort as the live-target stranded case below).
     */
    private boolean handleStrandedBoatNoTarget(EntityBoat myBoat) {
        if (!(myBoat instanceof EntityTravellerBoat)) {
            TravellerBoats.leave(follower);
            follower.getNavigator().clearPath();
            return false;
        }

        if (!isBoatOnWater(myBoat)) {
            BlockPos beachPos = new BlockPos(
                    MathHelper.floor(myBoat.posX),
                    MathHelper.floor(myBoat.posY),
                    MathHelper.floor(myBoat.posZ)
            );
            if (!tryDismountNear(beachPos, 3)) {
                TravellerBoats.leave(follower);
            }
            return false;
        }

        BlockPos boatPos = new BlockPos(
                MathHelper.floor(myBoat.posX), MathHelper.floor(myBoat.posY), MathHelper.floor(myBoat.posZ));
        BlockPos shoreSpot = findDrySpotNear(boatPos, NO_TARGET_SHORE_SEARCH_RADIUS);
        if (shoreSpot != null && !follower.world.getBlockState(shoreSpot).getMaterial().isLiquid()) {
            TravellerBoats.leave(follower);
            follower.setLocationAndAngles(
                    shoreSpot.getX() + 0.5D, shoreSpot.getY(), shoreSpot.getZ() + 0.5D,
                    follower.rotationYaw, follower.rotationPitch
            );
            follower.motionX = 0.0D;
            follower.motionY = 0.0D;
            follower.motionZ = 0.0D;
            follower.getNavigator().clearPath();
            follower.setSprinting(false);
            return false;
        }

        // No shore within range yet: let the boat drift to a stop and wait rather than steering
        // toward nothing meaningful. Force-dismount after the same stranded timeout used elsewhere.
        myBoat.motionX *= 0.8D;
        myBoat.motionZ *= 0.8D;

        double dx = myBoat.posX - lastBoatX;
        double dz = myBoat.posZ - lastBoatZ;
        boolean boatMoved = Double.isNaN(lastBoatX) || (dx * dx + dz * dz) > 0.04D;
        lastBoatX = myBoat.posX;
        lastBoatZ = myBoat.posZ;
        if (boatMoved) {
            strandedTicks = 0;
        } else {
            strandedTicks++;
        }
        if (strandedTicks >= STRANDED_TIMEOUT_TICKS) {
            strandedTicks = 0;
            TravellerBoats.leave(follower);
            return false;
        }

        follower.getNavigator().clearPath();
        return true;
    }

    private boolean handleBoatWithoutTargetBoat(EntityBoat myBoat, double targetX, double targetZ, boolean targetInWater) {
        if (!(myBoat instanceof EntityTravellerBoat)) {
            TravellerBoats.leave(follower);
            follower.getNavigator().clearPath();
            return false;
        }

        // If the boat is no longer on water (beached), dismount immediately.
        if (!isBoatOnWater(myBoat)) {
            BlockPos beachPos = new BlockPos(
                    MathHelper.floor(myBoat.posX),
                    MathHelper.floor(myBoat.posY),
                    MathHelper.floor(myBoat.posZ)
            );
            if (!tryDismountNear(beachPos, 3)) {
                TravellerBoats.leave(follower);
            }
            return false;
        }

        // Shore check: if dry land is reachable from the boat, dismount there
        // immediately regardless of how far the target is.
        // Guard: verify the spot is truly above water (findDrySpotNear can return
        // underwater positions via getTopSolidOrLiquidBlock on rivers).
        BlockPos shoreSpot = findDrySpotNear(
                new BlockPos(MathHelper.floor(myBoat.posX), MathHelper.floor(myBoat.posY), MathHelper.floor(myBoat.posZ)),
                4
        );
        if (shoreSpot != null && !follower.world.getBlockState(shoreSpot).getMaterial().isLiquid()) {
            TravellerBoats.leave(follower);
            follower.setLocationAndAngles(
                    shoreSpot.getX() + 0.5D, shoreSpot.getY(), shoreSpot.getZ() + 0.5D,
                    follower.rotationYaw, follower.rotationPitch
            );
            follower.motionX = 0.0D;
            follower.motionY = 0.0D;
            follower.motionZ = 0.0D;
            follower.getNavigator().clearPath();
            follower.setSprinting(false);
            return false;
        }

        double dxT = myBoat.posX - targetX;
        double dzT = myBoat.posZ - targetZ;
        double distSq = dxT * dxT + dzT * dzT;

        // Fallback: near the target on dry ground.
        if (!targetInWater && distSq <= 9.0D * 9.0D) {
            if (tryDismountNear(new BlockPos(targetX, 0, targetZ), 4)) {
                return false;
            }
        }

        if (targetInWater && distSq <= 9.0D * 9.0D) {
            double boatY = myBoat.posY;
            TravellerBoats.leave(follower);
            follower.setLocationAndAngles(targetX, boatY + 0.1D, targetZ, follower.rotationYaw, follower.rotationPitch);
            follower.motionX = 0.0D;
            follower.motionY = 0.0D;
            follower.motionZ = 0.0D;
            return false;
        }

        // Target is on land but boat isn't progressing — force dismount after timeout.
        double dx = myBoat.posX - lastBoatX;
        double dz = myBoat.posZ - lastBoatZ;
        boolean boatMoved = Double.isNaN(lastBoatX) || (dx * dx + dz * dz) > 0.04D;
        lastBoatX = myBoat.posX;
        lastBoatZ = myBoat.posZ;
        if (boatMoved) {
            strandedTicks = 0;
        } else {
            strandedTicks++;
        }
        if (strandedTicks >= STRANDED_TIMEOUT_TICKS) {
            strandedTicks = 0;
            TravellerBoats.leave(follower);
            return false;
        }

        steerBoatTowards(myBoat, targetX, targetZ, 0.55D);
        follower.getNavigator().clearPath();
        return true;
    }

    private boolean boardOrCreateBoatImmediately(EntityLivingBase target, EntityBoat targetBoat) {
        EntityBoat nearbyBoat = findNearbyFreeBoat();
        if (nearbyBoat != null) {
            moveToBoatAndBoard(nearbyBoat, baseSpeed);
            boatRepathCooldown = 25;
            return true;
        }

        if (!follower.isInWater()) {
            BlockPos waterEntry = findNearestWaterEntry(target);
            if (waterEntry != null) {
                follower.getNavigator().tryMoveToXYZ(
                        waterEntry.getX() + 0.5D,
                        waterEntry.getY(),
                        waterEntry.getZ() + 0.5D,
                        Math.max(baseSpeed, 0.55D)
                );
                boatRepathCooldown = 30;
                return true;
            }
        }

        if (boatSpawnCooldown <= 0 && trySpawnFollowerBoatNearWater(target)) {
            boatSpawnCooldown = SPAWN_COOLDOWN_TICKS;
            boatRepathCooldown = 25;
            return true;
        }

        double distSqToTargetBoat = follower.getDistanceSq(targetBoat);
        if (distSqToTargetBoat <= OWN_BOAT_PREFER_DIST * OWN_BOAT_PREFER_DIST
                && targetBoat.getPassengers().size() < BOAT_MAX_PASSENGERS) {
            moveToBoatAndBoard(targetBoat, baseSpeed);
            boatRepathCooldown = 25;
            return true;
        }

        follower.getNavigator().tryMoveToXYZ(targetBoat.posX, targetBoat.posY, targetBoat.posZ, Math.max(baseSpeed, 0.55D));
        boatRepathCooldown = 30;
        return true;
    }

    @Nullable
    private BlockPos findNearestWaterEntry(EntityLivingBase target) {
        BlockPos center = new BlockPos(follower.posX, follower.posY, follower.posZ);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                BlockPos p = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (!follower.world.isBlockLoaded(p)) continue;

                IBlockState feet = follower.world.getBlockState(p);
                IBlockState above = follower.world.getBlockState(p.up());
                if (!BlockRules.isVanillaWater(feet)) continue;
                if (!(above.getMaterial().isReplaceable() || above.getCollisionBoundingBox(follower.world, p.up()) == null)) continue;

                double distFollowerSq = p.distanceSq(center);
                if (distFollowerSq > WATER_COMMIT_DIST * WATER_COMMIT_DIST) continue;

                double distTargetSq = p.distanceSq(new BlockPos(target.posX, target.posY, target.posZ));
                double score = distFollowerSq + distTargetSq * 0.35D;

                if (score < bestScore) {
                    bestScore = score;
                    best = p.up();
                }
            }
        }

        return best;
    }

    private void moveToBoatAndBoard(EntityBoat boat, double speed) {
        double distSq = follower.getDistanceSq(boat);

        if (distSq <= BOARD_RADIUS_SQ) {
            BlockPos pos = new BlockPos(boat.posX, boat.posY, boat.posZ);
            if (follower.world.isBlockLoaded(pos)) {
                TravellerBoats.board(follower, boat);
            }
            return;
        }

        boatRepathCooldown = 6;
        follower.getNavigator().tryMoveToEntityLiving(boat, Math.max(speed, 0.55D));
    }

    private boolean steerOwnBoat(EntityLivingBase target, EntityBoat targetBoat, EntityBoat myBoat) {
        if (myBoat.getControllingPassenger() != follower) {
            follower.getNavigator().clearPath();
            follower.setSprinting(false);
            return true;
        }

        Vec3d forward = getDirectionFromMotionOrYaw(targetBoat.motionX, targetBoat.motionZ, targetBoat.rotationYaw);
        Vec3d right = new Vec3d(-forward.z, 0.0D, forward.x);

        int side = (follower.getUniqueID().hashCode() & 1) == 0 ? -1 : 1;

        double slotX = targetBoat.posX - forward.x * BOAT_SLOT_DISTANCE + right.x * BOAT_LANE_OFFSET * side;
        double slotZ = targetBoat.posZ - forward.z * BOAT_SLOT_DISTANCE + right.z * BOAT_LANE_OFFSET * side;

        double dx = myBoat.posX - slotX;
        double dz = myBoat.posZ - slotZ;
        double slotDistSq = dx * dx + dz * dz;

        if (slotDistSq <= BOAT_STOP_RADIUS_SQ) {
            myBoat.motionX *= 0.75D;
            myBoat.motionZ *= 0.75D;
            follower.getNavigator().clearPath();
            return true;
        }

        double speed = MathHelper.clamp(baseSpeed * com.jubitus.travellers.config.TravellersConfig.SPEED.followerBoatConvoySpeed, 0.25D, 0.9D);
        steerBoatTowards(myBoat, slotX, slotZ, speed);
        follower.getNavigator().clearPath();

        if (!target.isRiding() && !target.isInWater() && myBoat.getDistanceSq(target) <= 10.0D * 10.0D) {
            if (tryDismountNear(target.getPosition(), 4)) {
                return false;
            }
        }

        return true;
    }

    private void steerBoatTowards(EntityBoat boat, double targetX, double targetZ, double speed) {
        TravellerBoats.steer(follower, boat, targetX, targetZ, speed);
    }

    @Nullable
    private EntityBoat findNearbyFreeBoat() {
        AxisAlignedBB box = follower.getEntityBoundingBox().grow(SEARCH_RADIUS, 4.0D, SEARCH_RADIUS);
        List<EntityBoat> boats = follower.world.getEntitiesWithinAABB(EntityBoat.class, box, boat ->
                boat != null
                        && !boat.isDead
                        && boat.getPassengers().size() < BOAT_MAX_PASSENGERS
                        && isBoatOnWater(boat)
        );

        EntityBoat best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (EntityBoat boat : boats) {
            double distSq = follower.getDistanceSq(boat);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = boat;
            }
        }

        return best;
    }

    private boolean trySpawnFollowerBoatNearWater(EntityLivingBase target) {
        if (follower.world.isRemote) return false;

        BlockPos spawnPos = findWaterSurfaceNear(new BlockPos(follower.posX, follower.posY, follower.posZ), 5);
        if (spawnPos == null) return false;

        double dx = target.posX - follower.posX;
        double dz = target.posZ - follower.posZ;
        double lenSq = dx * dx + dz * dz;

        if (lenSq < 1.0E-6D) {
            dx = 0.0D;
            dz = 1.0D;
        } else {
            double len = Math.sqrt(lenSq);
            dx /= len;
            dz /= len;
        }

        return TravellerBoats.spawnAndBoard(follower, spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D,
                TravellerBoats.yawToward(dx, dz)) != null;
    }

    private boolean tryDismountNear(BlockPos center, int radius) {
        BlockPos best = findDrySpotNear(center, radius);
        if (best == null) return false;

        TravellerBoats.leave(follower);
        follower.setLocationAndAngles(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D, follower.rotationYaw, follower.rotationPitch);
        follower.motionX = 0.0D;
        follower.motionY = 0.0D;
        follower.motionZ = 0.0D;
        follower.getNavigator().clearPath();
        follower.setSprinting(false);
        return true;
    }

    private boolean isBoatOnWater(EntityBoat boat) {
        return TravellerBoats.isOnWater(follower.world, boat);
    }

    @Nullable
    private BlockPos findWaterSurfaceNear(BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = new BlockPos(center.getX() + dx, center.getY(), center.getZ() + dz);
                if (!follower.world.isBlockLoaded(pos)) continue;

                IBlockState state = follower.world.getBlockState(pos);
                if (!BlockRules.isVanillaWater(state)) continue;
                if (!follower.world.isAirBlock(pos.up())) continue;

                double distSq = pos.distanceSq(center);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = pos.up();
                }
            }
        }

        return best;
    }

    @Nullable
    private BlockPos findDrySpotNear(BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos column = new BlockPos(center.getX() + dx, 0, center.getZ() + dz);
                if (!follower.world.isBlockLoaded(column)) continue;

                BlockPos top = follower.world.getTopSolidOrLiquidBlock(column);
                if (!isStandableFast(top)) continue;

                double distSq = top.distanceSq(center);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = top;
                }
            }
        }

        return best;
    }

    private Vec3d getDirectionFromMotionOrYaw(double motionX, double motionZ, float yaw) {
        double motionSq = motionX * motionX + motionZ * motionZ;
        if (motionSq > 1.0E-4D) {
            double len = Math.sqrt(motionSq);
            return new Vec3d(motionX / len, 0.0D, motionZ / len);
        }

        double yawRad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(yawRad), 0.0D, Math.cos(yawRad));
    }

    private boolean isStandableFast(BlockPos feet) {
        return TerrainStandableCache.isStandable(follower.world, feet);
    }

}
