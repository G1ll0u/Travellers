package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.world.ground.BlockRules;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.entityai.flight.ElytraCapability;
import com.jubitus.travellers.entity.entityai.flight.ElytraOpportunityScanner;
import com.jubitus.travellers.entity.entityai.follow.FollowRuntimeConfig;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.MountedLeadController;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.world.pathing.TravellerNavigateGround;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelPathScheduler;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.pathing.route.TravelPlan;
import com.jubitus.travellers.world.pathing.itinerary.Itinerary;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryPlanner;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryState;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.millenaire.common.block.IBlockPath;
import org.millenaire.common.utilities.MillLog;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The travel AI afloat and at the water's edge: boarding (planned by the itinerary, or guessed for
 * units without one), rowing toward the next waypoint, landing, and the stall backstop that only runs
 * while in a boat. Boat gestures themselves are {@link TravellerBoats}.
 */
final class TravelBoatMode {

    private final EntityAITravel ai;
    private final EntityTraveller mob;

    TravelBoatMode(EntityAITravel ai, EntityTraveller mob) {
        this.ai = ai;
        this.mob = mob;
    }

    /** New leg or task (re)start: allow a boat again, no cooldown. */
    void resetLeg() {
        boatRetryCooldown = 0;
        triedBoatThisLeg = false;
    }

    /** A new leg may try a guessed boat again. */
    void allowBoatAgain() {
        triedBoatThisLeg = false;
    }

    void tickCooldown() {
        if (boatRetryCooldown > 0) boatRetryCooldown--;
    }

    void resetStall(double remaining) {
        boatNoProgressTicks = 0;
        boatBestRemaining = remaining;
    }

    static final int BOAT_OCEAN_PROBE = 16;   // how far ahead we scan for water when deciding to use a boat

    static final int BOAT_OCEAN_MIN_WATER = 16;   // how many water samples required to call it an "ocean"

    static final double BOAT_MIN_DISTANCE = 40.0; // only use boats if we’re at least this far from the destination

    static final double BOAT_EXIT_RADIUS = 24.0; // when this close to target village, try to leave boat

    static final double BOAT_SPEED_MULT = 0.85; // boat speed vs normal travel speed

    static final int BOAT_RETRY_CD = 40; // 2 seconds

    int boatRetryCooldown = 0;

    boolean triedBoatThisLeg = false;

    int boatNoProgressTicks = 0;

    double boatBestRemaining = Double.MAX_VALUE;


        // -----------------------------------------
        // Boat handling (keep it simple: steer + dismount when hit shore)
        // -----------------------------------------

    /** One tick afloat; true when the unit is in a boat (the on-foot logic must not run). */
    boolean tickAfloat() {
        EntityTravellerBoat boat = currentBoat();
        if (boat != null) {
            // if boat is colliding and not making progress, bail out
            boolean pinned = boat.collidedHorizontally
                    && (boat.motionX * boat.motionX + boat.motionZ * boat.motionZ) < 0.0004; // ~0.02^2

            if (pinned) {
                handleBoatHitSurface(boat); // dismount + delete + try shore
                return true;
            }
        }

        if (ai.isInBoat()) {

            if (ai.currentWaypoint == null) {
                ai.currentWaypoint = ai.computeNextWaypointToward(ai.targetVillage, ai.stepBlocks);
                mob.setCurrentWaypoint(ai.currentWaypoint);
                ai.debugMark(ai.currentWaypoint);
            }
            steerBoatToWaypoint();

// ---- Boat reached waypoint? pick the next one (THIS WAS MISSING) ----
            if (boat != null && ai.currentWaypoint != null) {
                double d2xz = TravelTerrain.horizDistSq(
                        new BlockPos(MathHelper.floor(boat.posX), 0, MathHelper.floor(boat.posZ)),
                        new BlockPos(ai.currentWaypoint.getX(), 0, ai.currentWaypoint.getZ())
                );

                // Use a slightly larger threshold than 0.25 to avoid “micro-stall”
                if (d2xz <= 2.0 * 2.0) {
                    BlockPos next = ai.computeNextWaypointToward(ai.targetVillage, ai.stepBlocks);
                    if (next != null) {
                        ai.currentWaypoint = next;
                        mob.setCurrentWaypoint(next);
                        ai.debugMark(next);
                    }
                }
            }

// existing shore detection
            if (boat != null && boatHitSurface(boat)) {
                handleBoatHitSurface(boat);
            }
            ai.wasInWater = false;
            tickBoatStallBackstop();
            return true;
        }
        return false;
    }

    /** Boards when the feet are wet and a crossing is planned (or, without an itinerary, guessed). */
    void tickEmbark(boolean inWaterNow) {

        if (!ai.isInBoat() && inWaterNow && usesPlannedBoats()) {
            // The itinerary decided the crossing in advance: board as soon as the feet are wet, no hesitation.
            if (!mob.world.isRemote && boatRetryCooldown == 0 && plannedBoatAhead()) {
                embarkAlongItinerary();
            }
        } else if (!ai.isInBoat() && inWaterNow) {
            if (!mob.world.isRemote && boatRetryCooldown == 0 && !triedBoatThisLeg) {

                // Optional: don’t immediately boat if we just stepped into a puddle
                // Only do it if we’re *actually* swimming / stalled / long corridor
                // (pick one or two of these guards)
                boolean stalled = (ai.noMoveTicks >= 20);              // 1s not moving
                boolean noPath = mob.getNavigator().noPath();

                if (stalled || noPath) {
                    BlockPos from = mob.getPosition();
                    BlockPos dest = ai.targetVillage;
                    if (dest != null && shouldUseBoat(from, dest)) {

                        Vec3d dirToDest = TravelTerrain.goalDirXZ(mob, dest);
                        int step = Math.max(16, ai.dynamicStep(from, dest));

                        BlockPos boatWp = pickFirstBoatWaypoint(from, dest, dirToDest, step);
                        if (boatWp != null) {
                            Vec3d boatDir = new Vec3d(
                                    boatWp.getX() + 0.5 - (from.getX() + 0.5),
                                    0,
                                    boatWp.getZ() + 0.5 - (from.getZ() + 0.5)
                            );
                            if (boatDir.lengthSquared() < 1e-6) boatDir = dirToDest;

                            if (tryEnterBoat(from, boatDir.normalize())) {
                                triedBoatThisLeg = true;     // ONLY on success
                                ai.currentWaypoint = boatWp;
                                mob.setCurrentWaypoint(boatWp);
                                ai.debugMark(boatWp);
                                ai.clearNav();
                            } else {
                                boatRetryCooldown = BOAT_RETRY_CD;
                            }
                        } else {
                            boatRetryCooldown = BOAT_RETRY_CD;
                        }
                    }
                }
            }
        }

    }

    /** The next waypoint while afloat, or null to give up for now. */
    @Nullable
    BlockPos afloatWaypoint(BlockPos from, BlockPos dest, Vec3d dir, int step) {
        double distToDest = Math.sqrt(from.distanceSq(dest));

        // 1) If close enough to destination, try to find a shore exit and leave the boat.
        if (distToDest <= BOAT_EXIT_RADIUS) {
            BlockPos shore = ai.terrain.findDrySurfaceNear(dest);
            if (shore != null) {
                // exit boat and resume normal land nav
                ai.dismountAndDeleteBoat();
                triedBoatThisLeg = false; // allow boat again on a future long leg if needed
                return shore;
            }
        }

        // A planned crossing rows along the itinerary; the shore contact ends it.
        if (usesPlannedBoats()) {
            BlockPos c = boatCarrot();
            if (c != null) return ai.picked("itineraryBoat", c, dest);
        }

        // 2) Otherwise, pick a water waypoint using your water-specific helper.
        BlockPos best = null;

        for (int s = step; s >= 16; s -= 8) {
            BlockPos cand = ai.terrain.surfaceAtOffsetWater(from, dir, s);
            if (cand != null) {
                best = cand;
                break;
            }
        }

        // 3) Small detours in water if straight fails
        if (best == null) {
            int detStep = Math.max(16, step / 2);
            int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};
            for (int angleDeg : detAngles) {
                Vec3d r = ai.terrain.rotateY(dir, Math.toRadians(angleDeg));
                BlockPos cand = ai.terrain.surfaceAtOffsetWater(from, r, detStep);
                if (cand != null) {
                    best = cand;
                    break;
                }
            }
        }

        if (best != null) {
            return best; // stay in water between waypoints
        }

        // 4) If we completely fail to find water waypoint, as a last resort try to exit boat
        BlockPos shore = ai.terrain.findDrySurfaceNear(from);
        if (shore != null) {
            ai.dismountAndDeleteBoat();
            triedBoatThisLeg = false;
            return shore;
        }

        // give up for now
        return null;
    }

    /** Boats come from the itinerary (planned crossings) whenever this unit follows one. */
    boolean usesPlannedBoats() {
        return TravellersConfig.ROUTING.enablePlannedRoutes && mob.isItineraryDriven();
    }

    /** True when the itinerary crosses water by boat here: on a BOAT segment, or one starts just ahead. */
    boolean plannedBoatAhead() {
        if (ai.targetVillage == null) return false;
        ItineraryState st = mob.getItineraryState();
        if (!st.isFor(ai.targetVillage)) return false;
        Itinerary it = st.get();
        st.cursor.advance(it, mob.posX, mob.posZ);
        return st.cursor.distanceToKind(it, Itinerary.BOAT, TravellersConfig.ROUTING.itineraryCarrotBlocks) >= 0.0;
    }

    /** The itinerary carrot at the boat's own height: steering is manual, no ground to resolve on water. */
    @Nullable
    BlockPos boatCarrot() {
        if (ai.targetVillage == null) return null;
        ItineraryState st = mob.getItineraryState();
        if (!st.isFor(ai.targetVillage)) return null;
        Itinerary it = st.get();
        st.cursor.advance(it, mob.posX, mob.posZ);
        st.cursor.computeCarrot(it, TravellersConfig.ROUTING.itineraryCarrotBlocks);
        return new BlockPos(MathHelper.floor(st.cursor.carrotX), MathHelper.floor(mob.posY),
                MathHelper.floor(st.cursor.carrotZ));
    }

    void embarkAlongItinerary() {
        BlockPos wp = boatCarrot();
        if (wp == null) return;
        Vec3d dir = new Vec3d(wp.getX() + 0.5 - mob.posX, 0, wp.getZ() + 0.5 - mob.posZ);
        if (dir.lengthSquared() < 1e-6) dir = TravelTerrain.goalDirXZ(mob, ai.targetVillage);
        if (!tryEnterBoat(mob.getPosition(), dir.normalize())) {
            boatRetryCooldown = BOAT_RETRY_CD;
            return;
        }
        com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path", "boat embark (planned)");
        ai.currentWaypoint = wp;
        mob.setCurrentWaypoint(wp);
        ai.debugMark(wp);
        ai.clearNav();
    }

    /**
     * The stall backstop for the boat branch, which returns before the on-foot one below ever runs.
     *
     * <p>Everything that normally rescues a stuck traveller lives past that early return: the
     * stuck ladder is not even ticked while afloat, so neither it nor {@code tryVirtualizeStuckAtEdge()} could ever fire. And a traveller
     * in a boat cannot be retired by {@code checkVirtualize} either - riding one fails the ordinary
     * eligibility gate, and only the frozen-by-vanilla branch resolves that. A boat whose next waypoint
     * stops resolving (routinely, at the edge of loaded terrain: waypoints are only ever placed on
     * loaded ground) therefore sat in open water indefinitely with nothing in the mod able to notice.
     * That is the "packet of travellers stuck in the water near the chunk border".
     *
     * <p>Its own counter rather than the shared one on purpose: the on-foot ladder's state describes a
     * walking body and must not be half-advanced by a boat leg it knows nothing about.
     */
    void tickBoatStallBackstop() {
        if (mob.world.isRemote || ai.targetVillage == null) return;

        double rem = ai.progressDist(); // along the itinerary, which may head away from the target
        if (rem < boatBestRemaining - ai.PROGRESS_IMPROVE_BLOCKS) {
            boatBestRemaining = rem;
            boatNoProgressTicks = 0;
            return;
        }
        if (++boatNoProgressTicks < TravellersConfig.TRAVEL.edgeStuckVirtualizeGraceTicks) return;
        boatNoProgressTicks = 0;
        // Hands off to the offscreen simulation, which dismounts and removes the boat on the way out
        // (and refuses outright while a player is close enough to see it happen).
        mob.tryVirtualizeStuckAtEdge(ai.targetVillage);
    }


    @Nullable
    EntityTravellerBoat currentBoat() {
        if (!ai.isInBoat()) return null;
        Entity r = mob.getRidingEntity();
        return (r instanceof EntityTravellerBoat) ? (EntityTravellerBoat) r : null;
    }

    /**
     * True if it's worth spawning a boat: long-ish distance and a mostly-water corridor ahead.
     */
    boolean shouldUseBoat(BlockPos from, BlockPos dest) {
        double dist = Math.sqrt(from.distanceSq(dest));

        boolean catchup = mob.isCatchupTravel();

        double min = catchup ? 12.0 : BOAT_MIN_DISTANCE;  // smaller for catchup
        if (dist < min) return false;

        Vec3d dir = new Vec3d(dest.getX() + 0.5 - (from.getX() + 0.5), 0, dest.getZ() + 0.5 - (from.getZ() + 0.5));
        if (dir.lengthSquared() < 1e-6) return false;
        dir = dir.normalize();

        return ai.terrain.isMostlyWaterAhead(from, dir, BOAT_OCEAN_PROBE, BOAT_OCEAN_MIN_WATER);
    }

    boolean tryEnterBoat(BlockPos from, Vec3d dir) {
        if (mob.world.isRemote) return false;
        if (ai.isInBoat()) return true; // already in one

        // Find a surface spawn pos (air just above top water)
        BlockPos spawnPos = findBoatSurfaceSpawnPos(from, dir);
        if (spawnPos == null) {
            return false; // no suitable water column here
        }

        Vec3d spawnDir = (dir.lengthSquared() > 1e-6) ? dir.normalize() : new Vec3d(0, 0, 1);
        if (TravellerBoats.spawnAndBoard(mob, spawnPos.getX() + 0.5, spawnPos.getY() + 0.02D, spawnPos.getZ() + 0.5,
                ai.terrain.yawFromDirection(spawnDir)) == null) {
            boatRetryCooldown = BOAT_RETRY_CD;
            return false;
        }
        ai.clearNav();
        return true;
    }


    void steerBoatToWaypoint() {
        EntityTravellerBoat boat = currentBoat();
        if (boat == null || ai.currentWaypoint == null) return;

        Vec3d pos = boat.getPositionVector();
        Vec3d target = new Vec3d(
                ai.currentWaypoint.getX() + 0.5,
                boat.posY, // stay roughly at current Y, boat handles vertical bobbing
                ai.currentWaypoint.getZ() + 0.5
        );
        Vec3d delta = target.subtract(pos);
        double d2 = delta.lengthSquared();
        if (d2 < 0.75 * 0.75) {
            // close enough; let updateTask reseed the next waypoint
            return;
        }


        TravellerBoats.steer(mob, boat, target.x, target.z, ai.getActiveSpeed() * BOAT_SPEED_MULT);
        ai.clearNav();
    }

    boolean boatHitSurface(EntityTravellerBoat boat) {
        World w = mob.world;

        BlockPos boatCol = new BlockPos(
                MathHelper.floor(boat.posX),
                MathHelper.floor(boat.posY - 0.2D),
                MathHelper.floor(boat.posZ)
        );
        if (!w.isBlockLoaded(boatCol)) return false;

        IBlockState feet = w.getBlockState(boatCol);
        IBlockState below = w.getBlockState(boatCol.down());

        boolean feetWater = BlockRules.isVanillaWater(feet);
        boolean belowWater = BlockRules.isVanillaWater(below);

        // 1) If we’re not over water at all, we beached / hit land/ice
        if (!feetWater && !belowWater) {
            return true;
        }

        // 2) Otherwise check a bit ahead at the “waterline”
        Vec3d dir = new Vec3d(boat.motionX, 0, boat.motionZ);

        // If barely moving, aim toward waypoint (or fallback to yaw)
        if (dir.lengthSquared() < 1.0e-4) {
            if (ai.currentWaypoint != null) {
                dir = new Vec3d(
                        ai.currentWaypoint.getX() + 0.5 - boat.posX,
                        0,
                        ai.currentWaypoint.getZ() + 0.5 - boat.posZ
                );
            }
            if (dir.lengthSquared() < 1.0e-6) {
                float yaw = boat.rotationYaw;
                double yawRad = Math.toRadians(yaw);
                dir = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
            }
        }

        dir = dir.normalize();

        // sample 1–2 blocks ahead, at waterline
        double sx = boat.posX + dir.x * 1.6D;
        double sz = boat.posZ + dir.z * 1.6D;
        double sy = boat.posY - 0.2D;

        BlockPos ahead = new BlockPos(
                MathHelper.floor(sx),
                MathHelper.floor(sy),
                MathHelper.floor(sz)
        );
        if (!w.isBlockLoaded(ahead)) return false;

        IBlockState aFeet = w.getBlockState(ahead);
        IBlockState aBelow = w.getBlockState(ahead.down());

        boolean aheadWater = BlockRules.isVanillaWater(aFeet) || BlockRules.isVanillaWater(aBelow);

        // If ahead column is not water, we’re about to grind shoreline
        return !aheadWater;
    }

    void handleBoatHitSurface(EntityTravellerBoat boat) {
        World w = mob.world;
        BlockPos boatPos = new BlockPos(
                MathHelper.floor(boat.posX),
                MathHelper.floor(boat.posY),
                MathHelper.floor(boat.posZ)
        );

        // Try to find a dry standable block near where we hit
        BlockPos shore = ai.terrain.findDrySurfaceNear(boatPos);
        // 1) Dismount + delete boat no matter what
        ai.dismountAndDeleteBoat();
        ai.wasInWater = false;
        triedBoatThisLeg = false;

        if (usesPlannedBoats()) {
            // A planned crossing followed the itinerary, the cursor is still on it. The cooldown keeps
            // a shore bump from re-boarding on the spot; a real island still gets a fresh boat after it.
            boatRetryCooldown = BOAT_RETRY_CD;
            com.jubitus.travellers.debug.trace.TravellerTracer.event(mob, "path", "boat landed (planned)");
        } else {
            boatRetryCooldown = 0;
            // The boat crossed straight over water, possibly past the itinerary's forward window: replan
            // from the shore, or the cursor could aim back at a vertex now behind (U-turn into the river).
            ai.nextItineraryReplanTick = 0;
            ai.replanItineraryFromHere("landed from boat");
        }
        ai.resetStuckState();

        // 2) If we found a shore tile, snap next waypoint to it
        if (shore != null) {
            ai.currentWaypoint = shore;
            mob.setCurrentWaypoint(shore);
            ai.debugMark(shore);
            ai.clearNav();
            ai.pushPathToWaypoint();
        } else {
            // fallback: just let travellers swim / walk from wherever he ends up
            ai.clearNav();
        }
    }

    @Nullable
    BlockPos pickFirstBoatWaypoint(BlockPos from, BlockPos dest, Vec3d dir, int step) {
        BlockPos best = null;

        // Straight water candidates
        for (int s = step; s >= 16; s -= 16) {
            BlockPos cand = ai.terrain.surfaceAtOffsetWater(from, dir, s);
            if (cand != null && ai.terrain.isWaterCorridorClear(from, cand)) {
                best = cand;
                break;
            }
        }

        if (best != null) return best;

        // Small detours if straight failed
        int detStep = Math.max(16, step / 2);
        int[] detAngles = new int[]{30, -30, 60, -60, 90, -90};

        for (int angleDeg : detAngles) {
            Vec3d r = ai.terrain.rotateY(dir, Math.toRadians(angleDeg));
            BlockPos cand = ai.terrain.surfaceAtOffsetWater(from, r, detStep);
            if (cand != null && ai.terrain.isWaterCorridorClear(from, cand)) {
                return cand;
            }
        }

        return null;
    }

    @Nullable
    BlockPos findBoatSurfaceSpawnPos(BlockPos from, Vec3d dir) {
        World w = mob.world;

        // Decide which XZ column to use: current or one step in front
        BlockPos columnXZ = from;
        IBlockState feet = w.getBlockState(from);
        IBlockState below = w.getBlockState(from.down());

        if (!BlockRules.isVanillaWater(feet) && !BlockRules.isVanillaWater(below) && dir != null) {
            int stepX = (int) Math.signum(dir.x);
            int stepZ = (int) Math.signum(dir.z);
            if (stepX != 0 || stepZ != 0) {
                columnXZ = new BlockPos(from.getX() + stepX, from.getY(), from.getZ() + stepZ);
            }
        }

        int x = columnXZ.getX();
        int z = columnXZ.getZ();
        int startY = columnXZ.getY();
        int maxY = w.getActualHeight() - 1;
        int minY = 1;

        BlockPos topWater = null;

        // 1) Scan upwards: find the highest continuous vanilla water block
        for (int y = startY; y <= maxY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!w.isBlockLoaded(p)) break;
            IBlockState s = w.getBlockState(p);

            if (BlockRules.isVanillaWater(s)) {
                topWater = p; // keep updating as we go up
            } else if (topWater != null) {
                // we left the water column; topWater is the surface water
                break;
            }
        }

        // 2) If we never saw water above, scan a bit downward
        if (topWater == null) {
            for (int y = startY - 1; y >= minY; y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (!w.isBlockLoaded(p)) break;
                IBlockState s = w.getBlockState(p);
                if (BlockRules.isVanillaWater(s)) {
                    topWater = p;
                    break;
                }
            }
        }

        if (topWater == null) {
            // No vanilla water in this column → don't spawn a boat at all
            return null;
        }

        // We want the AIR block directly above the topmost water
        BlockPos spawnPos = topWater.up();

        // Safety: don't spawn inside a solid ceiling (e.g. underwater cave roof)
        IBlockState head = w.getBlockState(spawnPos);
        if (!head.getMaterial().isReplaceable()
                && head.getCollisionBoundingBox(w, spawnPos) != null) {
            return null;
        }

        return spawnPos;
    }
}
