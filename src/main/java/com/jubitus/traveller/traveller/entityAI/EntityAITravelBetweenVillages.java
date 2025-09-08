package com.jubitus.traveller.traveller.entityAI;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

public class EntityAITravelBetweenVillages extends EntityAIBase {
    private static final double VILLAGE_RADIUS = 9;
    private static final double WAYPOINT_STEP = 24;
    private static final double ROAM_RADIUS = 8;
    private static final double MAX_VILLAGE_DISTANCE = 2000.0D;
    private final EntityTraveller entity;
    private final List<BlockPos> villages;
    private final double speed = 0.7D;
    private final Random rand = new Random();
    private int stuckTicks = 0;
    private BlockPos lastPosStuckCheck = null;


    private enum TravelState {CRUISING, SLOWING, PAUSING, ACCELERATING}

    private TravelState travelState = TravelState.CRUISING; // mutable
    // Travel control
    private double currentSpeed = speed;

    private int travelPauseTicks = 0;   // How long to pause during travel
    private static final double MIN_SPEED = 0.7D;
    private static final double MAX_SPEED = 1.0D;
    private static final int PAUSE_CHANCE = 2000; // 1 in 200 ticks chance to pause
    private static final int PAUSE_DURATION_MIN = 200; // 1s pause
    private static final int PAUSE_DURATION_MAX = 600; // 4s pause
    private final BlockPos lastPos = null;
    // At the top of your AI class or somewhere accessible
    private final Map<BlockPos, Integer> villageTownhallY = new HashMap<>();


    public EntityAITravelBetweenVillages(EntityTraveller entity, List<BlockPos> villages) {
        this.entity = entity;
        this.villages = villages;
        this.setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        return !villages.isEmpty();
    }

    @Override
    public boolean shouldContinueExecuting() {
        return !villages.isEmpty();
    }

    @Override
    public void updateTask() {

        if (villages.isEmpty()) return;

        // Pick a target village if none
        if (entity.getTargetVillage() == null) {
            pickNewTargetVillage();
        }
        if (entity.getCurrentWaypoint() == null) {
            entity.setCurrentWaypoint(nextWaypoint());
        }
// Check if the entityAI is stuck
// --- STUCK CHECK ---
        if (lastPosStuckCheck == null) {
            lastPosStuckCheck = new BlockPos(entity);
        } else {
            double movedSq = lastPosStuckCheck.distanceSq(entity.posX, entity.posY, entity.posZ);
            if (movedSq < 0.25D) { // moved less than 0.5 blocks
                stuckTicks++;
            } else {
                stuckTicks = 0;
                lastPosStuckCheck = new BlockPos(entity);
            }
        }

// If stuck more than 60 ticks (~3 sec)
        if (stuckTicks > 60) {
            boolean recovered = false;

            for (int i = 0; i < 8 && !recovered; i++) { // try up to 8 random escape attempts
                BlockPos escape = pickRandomSideOrBackGround(new BlockPos(entity), 4, 12);

                if (escape != null) {
                    if (entity.getNavigator().tryMoveToXYZ(
                            escape.getX() + 0.5,
                            escape.getY(),
                            escape.getZ() + 0.5,
                            0.8D)) {
                        recovered = true;
                        entity.setCurrentWaypoint(null); // force recalculation
                        // Don’t reset stuckTicks until we actually move
                    }
                }
            }

            if (!recovered) {
                // As a last resort, teleport a little bit forward
                BlockPos forward = straightLineWaypoint(entity.getTargetVillage());
                if (forward != null) {
                    entity.setPosition(forward.getX() + 0.5, forward.getY(), forward.getZ() + 0.5);
                    entity.getNavigator().clearPath();
                }
            }
        }
// --- STUCK CHECK END ---
        moveTowardWaypoint(currentSpeed);


        BlockPos targetVillage = entity.getTargetVillage();
        if (targetVillage != null) {

            // If within village radius
            if (entity.getDistanceSq(targetVillage) <= VILLAGE_RADIUS * VILLAGE_RADIUS) {

                if (entity.getPauseTicks() <= 0) {
                    entity.setPauseTicks(600 + rand.nextInt(300));
                    entity.setCurrentWaypoint(null);
                } else {
                    entity.setPauseTicks(entity.getPauseTicks() - 1);
                    roamWithinVillage(); // Implement waypoint roaming inside village

                    if (entity.getPauseTicks() <= 0) {
                        entity.setTargetVillage(null);
                        entity.setCurrentWaypoint(null);
                    }
                }
            } else {
                // Move toward the target village using pathfinding
                if (entity.getNavigator().noPath()) {
                    entity.getNavigator().tryMoveToXYZ(
                            targetVillage.getX() + 0.5,
                            targetVillage.getY(),
                            targetVillage.getZ() + 0.5,
                            1.0 // movement speed
                    );
                }
            }
        }


        // Vary speed and sometimes pause
        handleTravelSpeed();

        // Move toward waypoint at currentSpeed
        moveTowardWaypoint(currentSpeed);
    }

    private BlockPos pickRandomSideOrBackGround(BlockPos origin, int minRange, int maxRange) {
        // Facing angle in radians
        float facingYaw = entity.rotationYaw;
        double facingRad = Math.toRadians(facingYaw);

        for (int i = 0; i < 10; i++) {
            // Generate angle offset between 90° and 270° to avoid the front
            double offsetAngle = Math.toRadians(90 + rand.nextInt(181)); // 90 to 270 inclusive
            double angle = facingRad + offsetAngle;

            double dist = minRange + rand.nextDouble() * (maxRange - minRange);

            int x = origin.getX() + (int) (Math.cos(angle) * dist);
            int z = origin.getZ() + (int) (Math.sin(angle) * dist);
            int y = entity.world.getHeight(x, z);

            BlockPos p = new BlockPos(x, y, z);
            if (entity.world.getBlockState(p.down()).getMaterial().isSolid()) {
                return p;
            }
        }
        return null;
    }


    private void handleTravelSpeed() {
        if (travelPauseTicks > 0) {
            travelPauseTicks--;
            entity.getNavigator().clearPath();
            travelState = TravelState.PAUSING;
            return;
        }

        travelState = TravelState.CRUISING;
        currentSpeed = MIN_SPEED + rand.nextDouble() * (MAX_SPEED - MIN_SPEED);

        if (rand.nextInt(PAUSE_CHANCE) == 0) {
            travelPauseTicks = PAUSE_DURATION_MIN + rand.nextInt(PAUSE_DURATION_MAX - PAUSE_DURATION_MIN + 1);
            entity.getNavigator().clearPath();
            travelState = TravelState.PAUSING;
        }
    }

    private void pickNewTargetVillage() {
        entity.setTargetVillage(pickRandomNearbyVillage());
        entity.setCurrentWaypoint(nextWaypoint());
        entity.setRoamTarget(null);

        if (entity.getCurrentWaypoint() != null) {
            entity.getNavigator().tryMoveToXYZ(
                    entity.getCurrentWaypoint().getX() + 0.5,
                    entity.getCurrentWaypoint().getY(),
                    entity.getCurrentWaypoint().getZ() + 0.5,
                    speed
            );
        }
    }

    private BlockPos pickRandomNearbyVillage() {
        BlockPos here = new BlockPos(entity);
        List<BlockPos> candidates = new ArrayList<>();

        for (BlockPos village : villages) {
            if (village.equals(entity.getTargetVillage())) continue;
            double dist = here.distanceSq(village);
            if (dist <= MAX_VILLAGE_DISTANCE * MAX_VILLAGE_DISTANCE) {
                candidates.add(village);
            }
        }

        if (candidates.isEmpty()) return villages.get(rand.nextInt(villages.size()));
        return candidates.get(rand.nextInt(candidates.size()));
    }

    private void roamWithinVillage() {
        if (entity.getVillageIdleTicks() > 0) {
            entity.setVillageIdling(true);
            entity.setVillageIdleTicks(entity.getVillageIdleTicks() - 1);
            return; // standing still intentionally
        }
        entity.setVillageIdling(false);

        // Only pick a new target if none exists or we're close to it
        if (entity.getRoamTarget() == null || entity.getDistanceSq(entity.getRoamTarget()) < 2.0D || entity.getNavigator().noPath()) {
            boolean goInside = rand.nextBoolean();

            if (goInside) {
                List<BlockPos> entrances = new ArrayList<>();
                for (BlockPos pos : BlockPos.getAllInBoxMutable(
                        entity.getTargetVillage().add(-VILLAGE_RADIUS, -3, -VILLAGE_RADIUS),
                        entity.getTargetVillage().add(VILLAGE_RADIUS, 3, VILLAGE_RADIUS))) {

                    IBlockState state = entity.world.getBlockState(pos);
                    Block block = state.getBlock();

                    if (block instanceof BlockDoor || block instanceof BlockFenceGate) {
                        entrances.add(pos);
                    }
                }

                if (!entrances.isEmpty()) {
                    BlockPos entrance = entrances.get(rand.nextInt(entrances.size()));
                    EnumFacing facing = EnumFacing.NORTH;
                    if (entity.world.getBlockState(entrance).getProperties().containsKey(BlockHorizontal.FACING)) {
                        facing = entity.world.getBlockState(entrance).getValue(BlockHorizontal.FACING);
                    }

                    BlockPos inside = entrance.offset(facing);
                    entity.setRoamTarget(inside);
                }
            } else {
                for (int i = 0; i < 10; i++) {
                    double angle = rand.nextDouble() * Math.PI * 2;
                    double dist = 3 + rand.nextDouble() * (ROAM_RADIUS - 3);
                    int x = entity.getTargetVillage().getX() + (int) (Math.cos(angle) * dist);
                    int z = entity.getTargetVillage().getZ() + (int) (Math.sin(angle) * dist);
                    int y = entity.world.getHeight(x, z);
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (entity.world.getBlockState(candidate.down()).getMaterial().isSolid()) {
                        entity.setRoamTarget(candidate);
                        break;
                    }
                }
            }

            if (entity.getRoamTarget() != null) {
                entity.getNavigator().tryMoveToXYZ(
                        entity.getRoamTarget().getX() + 0.5,
                        entity.getRoamTarget().getY(),
                        entity.getRoamTarget().getZ() + 0.5,
                        0.45D
                );
                entity.setVillageIdleTicks(40 + rand.nextInt(100));
            }
        }
    }

    private BlockPos nextWaypoint() {
        BlockPos target = entity.getTargetVillage();
        if (target == null) {
            return null;  // This was the missing return
        }

        // Always use a fixed small step forward
        return straightLineWaypoint(target);
    }


    private BlockPos straightLineWaypoint(BlockPos target) {
        BlockPos current = new BlockPos(entity);
        Vec3d dir = new Vec3d(
                target.getX() - current.getX(),
                0,
                target.getZ() - current.getZ()
        ).normalize();

        double step = WAYPOINT_STEP; // force fixed distance

        int waypointX = current.getX() + (int) (dir.x * step);
        int waypointZ = current.getZ() + (int) (dir.z * step);
        // Estimate ground height for new waypoint
        int y = entity.world.getHeight(waypointX, waypointZ);

        BlockPos waypoint = new BlockPos(waypointX, y, waypointZ);
        return adjustToStandable(waypoint);
    }

//

    private BlockPos adjustToStandable(BlockPos pos) {
        World world = entity.world;
        int x = pos.getX();
        int z = pos.getZ();
        int y = pos.getY();

        // Move down until a solid block is found
        while (y > 0 && !world.getBlockState(new BlockPos(x, y - 1, z)).getMaterial().isSolid()) y--;

        // Move up until air block is found
        while (!world.isAirBlock(new BlockPos(x, y, z)) && y < world.getActualHeight()) y++;

        return new BlockPos(x, y, z);
    }


    private void moveTowardWaypoint(double speed) {
        BlockPos waypoint = entity.getCurrentWaypoint();
        if (waypoint == null) {
            return;
        }

        // If close enough to the current waypoint, pick the next one
        if (entity.getDistanceSq(waypoint) < 4.0D) {
            entity.setCurrentWaypoint(nextWaypoint());
            return;  // skip moving to old waypoint
        }

        // Now move to the current waypoint
        entity.getNavigator().tryMoveToXYZ(
                waypoint.getX() + 0.5,
                waypoint.getY(),
                waypoint.getZ() + 0.5,
                speed
        );
    }
}