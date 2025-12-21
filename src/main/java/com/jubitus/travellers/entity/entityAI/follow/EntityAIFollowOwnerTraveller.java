package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class EntityAIFollowOwnerTraveller extends EntityAIBase {
    private double lastOwnerDist = 0.0D;
    private double smoothedMoveSpeed = 0.0D;

    // --- per-follower reaction delays ---
    private boolean lastOwnerMoving = false;
    private int startMoveDelayTicks = 0; // delay before reacting when owner starts moving
    private int stopMoveDelayTicks  = 0; // delay before going fully idle when owner stops

    // tune these to taste
    private static final int MAX_START_DELAY_TICKS = 8;  // up to ~0.4s
    private static final int MAX_STOP_DELAY_TICKS  = 10; // up to ~0.5s

    // Debounce "owner started moving" → followers wait a bit before chasing again
    private int  ownerMoveDebounceTicks = 0;
    private boolean lastTargetMoving    = false;

    // --- Boat convoy behaviour (followers) ---
    private static final int    BOAT_MAX_PASSENGERS     = 2;        // vanilla boat
    private static final double BOAT_BOARD_RADIUS       = 2.5D;     // default; can also read from config
    private static final double BOAT_CONVOY_SPEED       = 0.9D;     // multiplier for boat motion
    private static final int    BOAT_SPAWN_COOLDOWN_TICKS = 60;     // 3s between spawn attempts
    private static final double BOAT_SHARE_SEARCH_RADIUS  = 8.0D;   // search radius for other boats

    private int boatSpawnCooldown = 0;
    private int boatFollowRepathTicks = 0;


    // --- boat-follow state ---
    private static final double BOAT_MIN_SWIM_RADIUS = 4.0D; // treat as "arrived" if this close while boat is stopped


    private int targetStillTicks = 0;
    private static final double MOTION_EPSILON = 0.0004D;     // ~0.02 blocks/tick
    private static final int IDLE_TICKS_THRESHOLD = 60;       // 1 second
    private static final double IDLE_BAND = 4.0D;            // how close we need to be to idle


    private double lastDirX = 0.0D;
    private double lastDirZ = 1.0D;  // default: facing +Z
    private boolean hasLastDir = false;

    private final EntityTravellerWorker worker;
    private final double speed;
    private final float startDist;   // begin following if beyond this
    private final float stopDist;    // stop following once inside this
    private EntityLivingBase followTarget;
    private int timeToRecalcPath;

    // --- long-range follow / anti-stuck tuning ---

    /** Max distance (blocks) for any single path target segment */
    private static final double SEGMENT_MAX_DIST = 16.0D;

    /** How often (ticks) to check if we’re stuck */
    private static final int STUCK_CHECK_INTERVAL = 20; // 1s

    /** How long (ticks) of being stuck before teleporting */
    private static final int STUCK_TP_THRESHOLD = 20 * 8; // 8s

    private int stuckCheckTicks = 0;
    private int stuckForTicks = 0;
    private double lastPosX;
    private double lastPosZ;

    public EntityAIFollowOwnerTraveller(EntityTravellerWorker worker,
                                        double speed,
                                        float startDist,
                                        float stopDist) {
        this.worker = worker;
        this.speed = speed;
        this.startDist = startDist;
        this.stopDist = stopDist;
        this.setMutexBits(3); // move + look
    }

    @Override
    public boolean shouldExecute() {
        if (!worker.isHiredFollower()) return false;
        if (!worker.isFollowOrderActive()) return false;

        // NEW: allow during RETURNING if worker decided to switch to follow
        if (worker.isJobActive() && !worker.isReturnUsingFollow()) return false;

        EntityLivingBase t = worker.getEffectiveFollowTarget();
        if (t == null || t.isDead) return false;
        if (t instanceof EntityPlayer && ((EntityPlayer) t).isSpectator()) return false;

        this.followTarget = t;
        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (!worker.isHiredFollower()) return false;
        if (!worker.isFollowOrderActive()) return false;

        // NEW: allow during RETURNING if worker decided to switch to follow
        if (worker.isJobActive() && !worker.isReturnUsingFollow()) return false;

        EntityLivingBase current = worker.getEffectiveFollowTarget();
        if (current == null || current.isDead) return false;
        if (current instanceof EntityPlayer && ((EntityPlayer) current).isSpectator()) return false;

        this.followTarget = current;
        return true;
    }

    @Override
    public void startExecuting() {
        this.timeToRecalcPath = 0;
        this.smoothedMoveSpeed = 0.0D;
        this.stuckCheckTicks = 0;
        this.stuckForTicks = 0;
        this.lastPosX = worker.posX;
        this.lastPosZ = worker.posZ;

        this.lastOwnerMoving = false;
        this.startMoveDelayTicks = 0;
        this.stopMoveDelayTicks  = 0;
        if (followTarget != null) {
            this.lastOwnerDist = worker.getDistance(followTarget);
        } else {
            this.lastOwnerDist = 0.0D;
        }
    }

    @Override
    public void resetTask() {
        this.smoothedMoveSpeed = 0.0D;
        this.followTarget = null;
        worker.getNavigator().clearPath();
        worker.setSprinting(false);

        this.stuckCheckTicks = 0;
        this.stuckForTicks = 0;

        this.lastOwnerMoving = false;
        this.startMoveDelayTicks = 0;
        this.stopMoveDelayTicks  = 0;
        this.lastOwnerDist = 0.0D;
    }


    @Override
    public void updateTask() {
        EntityLivingBase current = worker.getEffectiveFollowTarget();
        if (current == null || current.isDead) {
            resetTask();
            return;
        }

        if (current != this.followTarget) {
            // Target changed mid-task: update reference and recalc path immediately
            this.followTarget = current;
            this.stuckForTicks = 0;
            this.stuckCheckTicks = 0;
            this.lastPosX = worker.posX;
            this.lastPosZ = worker.posZ;
            this.timeToRecalcPath = 0;
        }

        if (followTarget == null) {
            return;
        }

        // --- NEW: convoy logic when worker is in a boat ---
        if (TravellersModConfig.followerBoatConvoyEnabled
                && worker.isRiding()
                && worker.getRidingEntity() instanceof EntityBoat) {

            EntityBoat boat = (EntityBoat) worker.getRidingEntity();

            if (boat.getControllingPassenger() != worker) {
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
                return;
            }

            updateBoatConvoyFollow();
            return;
        }

        if (boatSpawnCooldown > 0) {
            boatSpawnCooldown--;
        }

        double distSqToOwner = worker.getDistanceSq(followTarget);
        double distToOwner   = Math.sqrt(distSqToOwner);

        if (distToOwner > 256.0D && worker.getNavigator().noPath()) {
            // ~6 blocks/sec if applied every tick (0.3 blocks/tick is ~6 b/s)
            double step = 0.30D;

            double dx = followTarget.posX - worker.posX;
            double dz = followTarget.posZ - worker.posZ;
            double lenSq = dx*dx + dz*dz;

            if (lenSq > 1e-6) {
                double len = Math.sqrt(lenSq);
                dx /= len; dz /= len;

                double nx = worker.posX + dx * step;
                double nz = worker.posZ + dz * step;

                BlockPos feet = findSafeStandPos(nx, followTarget.posY, nz);
                worker.setLocationAndAngles(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                        worker.rotationYaw, worker.rotationPitch);
                worker.getNavigator().clearPath();
                return;
            }
        }

        double deltaOwnerDist = Math.abs(distToOwner - lastOwnerDist);
        lastOwnerDist = distToOwner;

        if (TravellersModConfig.followerBoatConvoy) {
            // if this returns true, we handled movement already (boat logic)
            if (handleBoatConvoy(distToOwner)) {
                return;
            }
        }

        // --- BOAT FOLLOW INTEGRATION (travellers boat) ---

        // If owner is riding a boat, try to ride with them
        Entity ownerMount = followTarget.getRidingEntity();
        if (ownerMount instanceof EntityTravellerBoat) {
            EntityTravellerBoat ownerBoat = (EntityTravellerBoat) ownerMount;

            // If we're already on this boat, just sit and let the player drive
            if (worker.isRiding() && worker.getRidingEntity() == ownerBoat) {
                worker.getNavigator().clearPath();
                worker.setSprinting(false);

                // reset stuck timers: we don't care while seated
                this.stuckCheckTicks = 0;
                this.stuckForTicks   = 0;
                this.lastPosX        = worker.posX;
                this.lastPosZ        = worker.posZ;
                return; // nothing else to do this tick
            }

            // If there is room on the owner's boat, try to board it.
            if (ownerBoat.getPassengers().size() < 2) {

                double distSqToBoat = worker.getDistanceSq(ownerBoat);

                // Walk/swim to the boat until we are close enough to mount
                if (distSqToBoat > (BOAT_BOARD_RADIUS * BOAT_BOARD_RADIUS)) {
                    // Repath to the boat itself rather than the owner
                    if (boatFollowRepathTicks-- <= 0) {
                        boatFollowRepathTicks = 10; // ~0.5s between repaths
                        worker.getNavigator().tryMoveToEntityLiving(ownerBoat, this.speed);
                    }
                } else {
                    // Close enough: hop in as passenger
                    worker.startRiding(ownerBoat, true);
                    worker.getNavigator().clearPath();
                    worker.setSprinting(false);

                    this.stuckCheckTicks = 0;
                    this.stuckForTicks   = 0;
                    this.lastPosX        = worker.posX;
                    this.lastPosZ        = worker.posZ;
                }

                return; // while owner is in boat, we only care about boarding/staying on it
            }

            // No room on owner's boat -> fall through to normal follow logic
            // (worker will swim alongside / behind the boat)
        }

        // Owner is NOT in a boat, but we might still be riding one from before → dismount.
        if (worker.isRiding() && worker.getRidingEntity() instanceof EntityBoat) {
            worker.dismountRidingEntity();
            worker.getNavigator().clearPath();
        }

// --- Detect movement of follow target with a "still for a while" concept ---

        double mvX = followTarget.posX - followTarget.prevPosX;
        double mvZ = followTarget.posZ - followTarget.prevPosZ;
        double mvLenSq = mvX * mvX + mvZ * mvZ;

        final boolean targetIsPlayer = (followTarget instanceof EntityPlayer);
        final boolean targetIsWorkerLeader = (followTarget instanceof EntityTravellerWorker);

// AI entities often have tiny mv every tick. Require more.
        final double MOVE_EPS_SQ = targetIsPlayer ? 1.0e-5D : 4.0e-4D; // 0.02^2

        boolean targetMoving = (mvLenSq > MOVE_EPS_SQ) || (targetIsPlayer && deltaOwnerDist > 0.02D);

// ownerSpeed just for speed-based scaling later
        double ownerSpeed = Math.sqrt(mvLenSq);

        boolean followTargetSprinting = followTarget.isSprinting();
        boolean followTargetSneaking  = followTarget.isSneaking();

// “Raw moving” = any input / motion at all (including sneaking)
        boolean ownerRawMoving = targetMoving || followTargetSprinting || followTargetSneaking;

// Stillness counter based on ownerRawMoving (not just mvLenSq)
        if (ownerRawMoving) {
            targetStillTicks = 0;
        } else {
            targetStillTicks++;
        }

// Detect start/stop edges and roll random delays

        lastOwnerMoving = ownerRawMoving;

// --- START-MOVE DELAY: follower hesitates a bit before starting to walk/run ---


// --- STOP-MOVE DELAY: follower coasts a bit after owner stops ---
// We'll decrement stopMoveDelayTicks later (once per tick)
        if (!ownerRawMoving && stopMoveDelayTicks > 0) {
            stopMoveDelayTicks--;
        }

// --- HARD IDLE: owner is properly stopped, follower is near ---
        boolean ownerHardIdle = !ownerRawMoving && targetStillTicks >= 20; // ~1s of no movement
        final double idleRadius = 8.0D;

        if (ownerHardIdle && distToOwner <= idleRadius && stopMoveDelayTicks <= 0) {
            worker.setSprinting(false);
            worker.getNavigator().clearPath();

            this.stuckCheckTicks = 0;
            this.stuckForTicks   = 0;
            this.lastPosX        = worker.posX;
            this.lastPosZ        = worker.posZ;

            return;
        }
        // --- Debounce: when owner starts moving, followers wait a bit before chasing ---
        if (targetMoving) {
            if (!lastTargetMoving) {
                // rising edge: owner just started moving
                ownerMoveDebounceTicks = TravellersModConfig.followerOwnerMoveDelayTicks;
            } else if (ownerMoveDebounceTicks > 0) {
                ownerMoveDebounceTicks--;
            }
            lastTargetMoving = true;
        } else {
            lastTargetMoving = false;
            ownerMoveDebounceTicks = 0;
        }

        // If owner sprints, override debounce: react immediately
        if (followTargetSprinting) {
            ownerMoveDebounceTicks = 0;
        }

        // --- Simple "owner idle" detection ---
        int idleTicksThreshold = TravellersModConfig.followerOwnerIdleTicks;
        if (idleTicksThreshold <= 0) idleTicksThreshold = IDLE_TICKS_THRESHOLD; // fallback


//
//        // Only if we're NOT in that idle/debounce mode do we run stuck check + pathing
        tickStuckCheck(distToOwner);

        // Throttle path recalculation
        if (--timeToRecalcPath > 0) {
            return;
        }
        timeToRecalcPath = (distToOwner > 64.0D) ? 4 : 8;

// ~0.03 blocks/tick for AI, ~0.01 for players (tweak)
        final double DIR_EPS_SQ = targetIsPlayer ? 1.0e-4D : 9.0e-4D;

        if (mvLenSq > DIR_EPS_SQ) {
            // moving -> update from motion (good)
            double mvLen = Math.sqrt(mvLenSq);
            mvX /= mvLen;
            mvZ /= mvLen;

            double blend = targetIsPlayer ? 0.25D : 0.10D;
            if (!hasLastDir) {
                lastDirX = mvX;
                lastDirZ = mvZ;
                hasLastDir = true;
            } else {
                lastDirX = lastDirX * (1.0D - blend) + mvX * blend;
                lastDirZ = lastDirZ * (1.0D - blend) + mvZ * blend;

                double len = Math.sqrt(lastDirX * lastDirX + lastDirZ * lastDirZ);
                if (len > 1.0e-4D) { lastDirX /= len; lastDirZ /= len; }
            }
        } else {
            // NOT moving -> DO NOT follow head yaw.
            // Only set direction from yaw if we don't have any direction yet.
            if (!hasLastDir) {
                float bodyYaw = followTarget.renderYawOffset; // better than rotationYaw for "body"
                double yawRad = Math.toRadians(bodyYaw);
                lastDirX = -Math.sin(yawRad);
                lastDirZ =  Math.cos(yawRad);
                hasLastDir = true;
            }
        }


        double forwardX = lastDirX;
        double forwardZ = lastDirZ;

        int hash = worker.getUniqueID().hashCode();

        // --- follower "role": behind, side, or light-front escort ---
        int role = (hash >>> 8) & 3; // 0..3

        double base = TravellersModConfig.followerRingBaseRadius;
        double step = TravellersModConfig.followerRingSlotStep;
        int rSlot   = (hash >>> 1) & 7;

        double behindAngle  = Math.atan2(-forwardZ, -forwardX);
        double forwardAngle = Math.atan2(forwardZ, forwardX);

        double theta;
        double radius;

        // Angle slot for some variety
        int angleSlot = (hash >>> 4) & 15;        // 0..15
        double baseOffset = (angleSlot - 7.5D) * (Math.PI / 20.0D); // slightly narrower spread

        switch (role) {
            default:
            case 0:
                // Classic: behind in a loose arc
                radius = base + rSlot * step;
                theta  = behindAngle + baseOffset * 1.2D;
                break;

            case 1:
                // Side-left escort
                radius = base + (rSlot * step * 0.7D);
                theta  = forwardAngle + (Math.PI / 2.5D) + baseOffset * 0.5D;
                break;

            case 2:
                // Side-right escort
                radius = base + (rSlot * step * 0.7D);
                theta  = forwardAngle - (Math.PI / 2.5D) + baseOffset * 0.5D;
                break;

            case 3:
                // Light front-diagonal escort
                radius = base + 1.0D + (rSlot * step * 0.4D);
                double frontSide = ((hash & 1) == 0) ? +1.0D : -1.0D;
                theta = forwardAngle + frontSide * (Math.PI / 3.0D) + baseOffset * 0.3D;
                break;
        }

        double targetX = followTarget.posX + Math.cos(theta) * radius;
        double targetZ = followTarget.posZ + Math.sin(theta) * radius;
        double targetY = followTarget.posY;

        double dyOwner = followTarget.posY - worker.posY;
        if (dyOwner < -1.5D && distToOwner < 20.0D) {
            // Go much closer so we actually commit to entering the hole
            radius = 2.0D;
            theta  = behindAngle;
            targetX = followTarget.posX + Math.cos(theta) * radius;
            targetZ = followTarget.posZ + Math.sin(theta) * radius;
            targetY = followTarget.posY;
        }

        // --- 3) Small jitter = "not extremely precise" ---

        int phaseOffset   = hash & 255;
        double orbitPhase = (worker.ticksExisted + phaseOffset) * 0.03D;
        double jitterRadius = (followTarget instanceof EntityPlayer) ? 0.8D : 0.0D; // or 0.15D

        targetX += Math.cos(orbitPhase) * jitterRadius;
        targetZ += Math.sin(orbitPhase) * jitterRadius;



        // --- 4) Distance to PERSONAL slot ---

        double dxSlot = worker.posX - targetX;
        double dzSlot = worker.posZ - targetZ;
        double distToSlotSq = dxSlot * dxSlot + dzSlot * dzSlot;
        double distToSlot   = Math.sqrt(distToSlotSq);

        double dySlot       = worker.posY - targetY;
        double distToSlot3D = Math.sqrt(dxSlot * dxSlot + dySlot * dySlot + dzSlot * dzSlot);
        boolean leaderReallyMoving = targetMoving || followTargetSprinting;



        final double SLOT_DEADZONE = targetIsPlayer ? 0.6D : 1.2D;
        if (distToSlot3D < SLOT_DEADZONE && !leaderReallyMoving) {
            worker.getNavigator().clearPath();
            worker.setSprinting(false);
            return;
        }

// Once the owner is idle, and this follower is very close to its personal slot,


        // --- 5) Compute desired speed from owner state + slot distance ---

// --- 5) Compute desired speed from owner state + slot distance ---
        boolean sprint = false;

// Base: match target state
        double stateFactor;
        if (followTargetSprinting)      stateFactor = 1.40D;
        else if (followTargetSneaking)  stateFactor = 0.60D;
        else                            stateFactor = 1.25D;

// Only dampen stateFactor for *worker-leader*, not for player
        if (targetIsWorkerLeader) {
            stateFactor = 1.05D; // mild; don't slow players
        }

// Catch-up based on distance to slot
        double catchupFactor;
        if (distToSlot3D > 128.0D)      catchupFactor = 2.20D;
        else if (distToSlot3D > 64.0D)  catchupFactor = 1.90D;
        else if (distToSlot3D > 32.0D)  catchupFactor = 1.65D;
        else if (distToSlot3D > 16.0D)  catchupFactor = 1.45D;
        else if (distToSlot3D > 8.0D)   catchupFactor = 1.35D;
        else if (distToSlot3D > 4.0D)   catchupFactor = 1.25D;
        else                            catchupFactor = 1.15D;


// Player-only speed scaling from real motion
        double ownerBasedFactor = 1.0D;
        if (targetIsPlayer) {
            ownerBasedFactor = 1.0D + ownerSpeed * 6.5D;
        }

// Stable leader boost ONLY for worker leaders
        double leaderFactor = 1.0D;
        if (targetIsWorkerLeader) {
            leaderFactor = 1.35D;          // tune 1.2–1.6
            if (distToSlot3D > 12.0D) leaderFactor *= 1.10D;
            if (distToSlot3D > 24.0D) leaderFactor *= 1.15D;
        }

        double moveSpeed = this.speed * stateFactor * catchupFactor * ownerBasedFactor * leaderFactor;

// Clamp BEFORE using it
        double maxFollowSpeed = 3.0D;

// allow more when very far from owner
        if (distToOwner > 64.0D) maxFollowSpeed = 4.0D;
        if (distToOwner > 128.0D) maxFollowSpeed = 5.0D;

        if (targetIsWorkerLeader) maxFollowSpeed = 1.10D; // tune 0.85–1.3

        moveSpeed = Math.max(0.08D, Math.min(moveSpeed, maxFollowSpeed));

// Optional smoothing (use it!)
        double alpha = targetIsPlayer ? 0.35D : 0.15D;
        if (distToOwner > 48.0D) alpha = 0.55D;
        if (distToOwner > 96.0D) alpha = 0.75D;

        smoothedMoveSpeed = smoothedMoveSpeed + (moveSpeed - smoothedMoveSpeed) * alpha;

        worker.setSprinting(sprint);
// --- segment target so we never ask pathfinder for huge paths ---
        double sdx = targetX - worker.posX;
        double sdz = targetZ - worker.posZ;
        double sDistSq = sdx * sdx + sdz * sdz;

        if (sDistSq > (SEGMENT_MAX_DIST * SEGMENT_MAX_DIST)) {
            double sDist = Math.sqrt(sDistSq);
            sdx /= sDist;
            sdz /= sDist;

            targetX = worker.posX + sdx * SEGMENT_MAX_DIST;
            targetZ = worker.posZ + sdz * SEGMENT_MAX_DIST;
            targetY = worker.posY; // keep local Y; avoid weird vertical targets
        }

        worker.setSprinting(sprint);
        worker.getNavigator().tryMoveToXYZ(targetX, targetY, targetZ, smoothedMoveSpeed);
    }

    private BlockPos findSafeStandPos(double x, double yHint, double z) {
        World w = worker.world;

        int xi = net.minecraft.util.math.MathHelper.floor(x);
        int zi = net.minecraft.util.math.MathHelper.floor(z);

        // Start from a reasonable Y: try around yHint but clamp
        int startY = net.minecraft.util.math.MathHelper.clamp(
                net.minecraft.util.math.MathHelper.floor(yHint),
                2,
                w.getActualHeight() - 3
        );

        BlockPos base = new BlockPos(xi, startY, zi);

        // Search down first (prefer ground)
        for (int dy = 0; dy <= 32; dy++) {
            BlockPos feet = base.down(dy);
            if (isStandable(feet)) return feet;
        }

        // Then search up (for cliffs/trees)
        for (int dy = 1; dy <= 32; dy++) {
            BlockPos feet = base.up(dy);
            if (isStandable(feet)) return feet;
        }

        // Final fallback: top solid block in the column
        BlockPos top = w.getTopSolidOrLiquidBlock(new BlockPos(xi, 0, zi));
        if (isStandable(top)) return top;

        return base; // worst fallback
    }

    private boolean isStandable(BlockPos feet) {
        World w = worker.world;
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;

        IBlockState below = w.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());

        boolean freeFeet = atFeet.getMaterial().isReplaceable() || atFeet.getCollisionBoundingBox(w, feet) == null;
        boolean freeHead = atHead.getMaterial().isReplaceable() || atHead.getCollisionBoundingBox(w, feet.up()) == null;
        if (!freeFeet || !freeHead) return false;

        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        return shape == BlockFaceShape.SOLID;
    }


    /**
     * Check if we're stuck (barely moving) while still far from the followTarget.
     * If so, after some time, teleport 8 blocks toward the followTarget on surface.
     */
    private void tickStuckCheck(double distTofollowTarget) {
        if (worker.isRiding() && worker.getRidingEntity() instanceof EntityBoat) {
            stuckForTicks = 0;
            stuckCheckTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            return;
        }

        // Only care when we are clearly not "already with" the followTarget
        if (distTofollowTarget <= (double) (stopDist * 2.0F) || followTarget == null) {
            stuckForTicks = 0;
            stuckCheckTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            return;
        }


        if (++stuckCheckTicks < STUCK_CHECK_INTERVAL) {
            return;
        }
        stuckCheckTicks = 0;

        double dx = worker.posX - lastPosX;
        double dz = worker.posZ - lastPosZ;
        double movedSq = dx * dx + dz * dz;

        lastPosX = worker.posX;
        lastPosZ = worker.posZ;

        // If we moved at least ~0.5 blocks in the last second -> definitely not stuck
        if (movedSq > 0.25D) { // 0.5^2
            stuckForTicks = 0;
            return;
        }

        // Barely moved -> accumulate "stuck" time
        stuckForTicks += STUCK_CHECK_INTERVAL;

        if (stuckForTicks < STUCK_TP_THRESHOLD) {
            return;
        }

        // --- STUCK: teleport 8 blocks toward followTarget on surface ---

        stuckForTicks = 0;

        double dirX = followTarget.posX - worker.posX;
        double dirZ = followTarget.posZ - worker.posZ;
        double lenSq = dirX * dirX + dirZ * dirZ;
        if (lenSq < 0.0001D) {
            return; // already basically on the followTarget
        }

        double len = Math.sqrt(lenSq);
        dirX /= len;
        dirZ /= len;

        // Step toward the followTarget, but not all the way
        double step = Math.min(8.0D, distTofollowTarget - 2.0D);
        if (step <= 0.0D) {
            return;
        }

        double tpX = worker.posX + dirX * step;
        double tpZ = worker.posZ + dirZ * step;

        BlockPos ground = findSafeStandPos(tpX, followTarget.posY, tpZ);

        worker.setLocationAndAngles(
                ground.getX() + 0.5D,
                ground.getY(),
                ground.getZ() + 0.5D,
                worker.rotationYaw,
                worker.rotationPitch
        );
        worker.getNavigator().clearPath();
        worker.setSprinting(false);

    }
    /**
     * Boat-convoy behaviour for hired followers:
     * - If owner is in a boat:
     *      * Try to board owner's boat if it has a free seat.
     *      * Otherwise, share boats with other workers.
     *      * As a last resort, spawn our own boat.
     * - If owner is not in a boat but we are, try to converge & dismount.
     *
     * Returns true if movement was fully handled this tick and normal
     * follow logic should be skipped.
     */
    private boolean handleBoatConvoy(double distToOwner) {
        EntityLivingBase owner = followTarget;
        if (owner == null) return false;

        Entity ownerMount = owner.getRidingEntity();
        boolean ownerInBoat = ownerMount instanceof EntityBoat;
        boolean workerInBoat = worker.isRiding() && worker.getRidingEntity() instanceof EntityBoat;

        // --- Case A: owner NOT in boat ---
        if (!ownerInBoat) {
            if (workerInBoat) {
                // We are still in a boat while owner is on foot/swimming.
                EntityBoat ourBoat = (EntityBoat) worker.getRidingEntity();

                // Simple behaviour: if close enough to owner or on land, dismount.
                if (distToOwner <= 4.0D || !isBoatOnWater(ourBoat)) {
                    worker.dismountRidingEntity();
                    worker.getNavigator().clearPath();
                } else {
                    // Steer boat gently toward owner as long as he is still far
                    steerBoatTowardsTarget(ourBoat, owner.posX, owner.posY, owner.posZ);
                    worker.getNavigator().clearPath();
                }
                return true;
            }

            // Neither in boat -> let normal follow logic handle it.
            return false;
        }

        // --- Case B: owner IS in a boat ---
        EntityBoat ownerBoat = (EntityBoat) ownerMount;

        // B1: already in the same boat → do nothing but keep path empty
        if (workerInBoat && worker.getRidingEntity() == ownerBoat) {
            worker.getNavigator().clearPath();
            worker.setSprinting(false);
            stuckCheckTicks = 0;
            stuckForTicks   = 0;
            lastPosX        = worker.posX;
            lastPosZ        = worker.posZ;
            return true;
        }

        // B2: worker already in a DIFFERENT boat → convoy steering
        if (workerInBoat) {
            EntityBoat ourBoat = (EntityBoat) worker.getRidingEntity();
            steerBoatTowardsTarget(ourBoat, owner.posX, owner.posY, owner.posZ);
            worker.getNavigator().clearPath();
            return true;
        }

        // B3: worker on foot / swimming; try to get on owner's boat FIRST.
        int occupied = ownerBoat.getPassengers().size();
        boolean roomOnOwnerBoat = occupied < BOAT_MAX_PASSENGERS;

        if (roomOnOwnerBoat) {
            double distSqToBoat = worker.getDistanceSq(ownerBoat);

            if (distSqToBoat > (BOAT_BOARD_RADIUS * BOAT_BOARD_RADIUS)) {
                // move toward owner's boat
                if (boatFollowRepathTicks-- <= 0) {
                    boatFollowRepathTicks = 10;
                    worker.getNavigator().tryMoveToEntityLiving(ownerBoat, this.speed);
                }
            } else {
                // close enough to board
                worker.startRiding(ownerBoat, true);
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
                stuckCheckTicks = 0;
                stuckForTicks   = 0;
                lastPosX        = worker.posX;
                lastPosZ        = worker.posZ;
            }
            return true;
        }

        // B4: owner's boat is FULL → try to share boats with other followers.
        EntityBoat sharedBoat = findSharedBoatNearWorker();
        if (sharedBoat != null) {
            double d2 = worker.getDistanceSq(sharedBoat);
            if (d2 > (BOAT_BOARD_RADIUS * BOAT_BOARD_RADIUS)) {
                if (boatFollowRepathTicks-- <= 0) {
                    boatFollowRepathTicks = 10;
                    worker.getNavigator().tryMoveToEntityLiving(sharedBoat, this.speed);
                }
            } else {
                worker.startRiding(sharedBoat, true);
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
            }
            return true;
        }

        // B5: no existing boat to share → spawn our own boat, if safe & in water.
        if (boatSpawnCooldown <= 0 && trySpawnFollowerBoat(owner)) {
            boatSpawnCooldown = BOAT_SPAWN_COOLDOWN_TICKS;
            return true;
        }

        // Could not boat; fall back to normal follow (swim behind).
        return false;
    }
    private void steerBoatTowardsTarget(EntityBoat boat, double tx, double ty, double tz) {
        Vec3d pos = new Vec3d(boat.posX, boat.posY, boat.posZ);
        Vec3d target = new Vec3d(tx, boat.posY, tz); // keep same Y; boat handles bobbing
        Vec3d delta = target.subtract(pos);

        double d2 = delta.lengthSquared();
        if (d2 < 0.25D) {
            boat.motionX = 0.0D;
            boat.motionZ = 0.0D;
            return;
        }

        Vec3d dir = delta.normalize();
        double boatSpeed = this.speed * TravellersModConfig.followerBoatConvoySpeed; // or BOAT_CONVOY_SPEED

        boat.motionX = dir.x * boatSpeed;
        boat.motionZ = dir.z * boatSpeed;

        float yaw = yawFromDirection(dir);
        boat.setLocationAndAngles(boat.posX, boat.posY, boat.posZ, yaw, boat.rotationPitch);
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;

        // keep worker oriented with the boat
        worker.rotationYaw = yaw;
        worker.rotationYawHead = yaw;
        worker.renderYawOffset = yaw;

        worker.getNavigator().clearPath();
    }

    private float yawFromDirection(Vec3d dir) {
        double yawRad = Math.atan2(dir.z, dir.x);
        return (float) (yawRad * (180D / Math.PI) - 90.0D);
    }
    @Nullable
    private EntityBoat findSharedBoatNearWorker() {
        World w = worker.world;

        double r = TravellersModConfig.followerBoatSearchRadius;
        AxisAlignedBB box = worker.getEntityBoundingBox().grow(r, 4.0D, r);

        List<EntityBoat> boats = w.getEntitiesWithinAABB(EntityBoat.class, box, b -> {
            if (b == null || b.isDead) return false;
            // Only boats that are already in water; prevents weird land boats
            if (!isBoatOnWater(b)) return false;

            int passengers = b.getPassengers().size();
            return passengers < BOAT_MAX_PASSENGERS;
        });

        if (boats.isEmpty()) return null;

        // Prefer boats that already have at least 1 passenger (shared boats)
        EntityBoat best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (EntityBoat b : boats) {
            int occupants = b.getPassengers().size();
            double d2 = worker.getDistanceSq(b);

            double score = 0.0;
            if (occupants > 0) score += 5.0;      // strong bias for an already-occupied boat
            score -= Math.sqrt(d2) * 0.1;         // prefer closer

            if (score > bestScore) {
                bestScore = score;
                best = b;
            }
        }

        return best;
    }
    private boolean trySpawnFollowerBoat(EntityLivingBase owner) {
        if (worker.world.isRemote) return false;

        // Only bother if worker is in/very near water
        if (!worker.isInWater()) {
            BlockPos feet = new BlockPos(worker.posX, worker.posY - 0.1D, worker.posZ);
            IBlockState sFeet = worker.world.getBlockState(feet);
            if (!isVanillaWater(sFeet)) {
                return false;
            }
        }

        // Direction toward owner (for boat yaw & possibly column choice)
        Vec3d dir = new Vec3d(owner.posX - worker.posX, 0, owner.posZ - worker.posZ);
        if (dir.lengthSquared() < 1e-6) {
            dir = new Vec3d(0, 0, 1);
        } else {
            dir = dir.normalize();
        }

        BlockPos from = new BlockPos(worker.posX, worker.posY, worker.posZ);
        BlockPos spawnPos = findBoatSurfaceSpawnPos(from, dir);
        if (spawnPos == null) {
            return false; // no safe surface water with air above
        }

        double sx = spawnPos.getX() + 0.5D;
        double sy = spawnPos.getY() + 0.0D;   // this is already the AIR block above water
        double sz = spawnPos.getZ() + 0.5D;

        float yaw = yawFromDirection(dir);
        World w = worker.world;

        // If you have a custom boat, use that here:
        EntityTravellerBoat boat = new EntityTravellerBoat(w, sx, sy, sz);
        boat.setLocationAndAngles(sx, sy, sz, yaw, boat.rotationPitch);
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;

        w.spawnEntity(boat);

        worker.startRiding(boat, true);
        worker.getNavigator().clearPath();
        worker.setSprinting(false);

        return true;
    }
    @Nullable
    private BlockPos findBoatSurfaceSpawnPos(BlockPos from, @Nullable Vec3d dir) {
        World w = worker.world;

        // Decide which XZ column to use: current or one step in front
        BlockPos columnXZ = from;
        IBlockState feet = w.getBlockState(from);
        IBlockState below = w.getBlockState(from.down());

        if (!isVanillaWater(feet) && !isVanillaWater(below) && dir != null) {
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

        // 1) Scan upwards: find the highest continuous vanilla water block in this column
        for (int y = startY; y <= maxY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            if (!w.isBlockLoaded(p)) break;
            IBlockState s = w.getBlockState(p);

            if (isVanillaWater(s)) {
                topWater = p; // keep updating as we go up
            } else if (topWater != null) {
                // left the water column - topWater is the surface water
                break;
            }
        }

        // 2) If we never saw water above, scan a bit downward
        if (topWater == null) {
            for (int y = startY - 1; y >= minY; y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (!w.isBlockLoaded(p)) break;
                IBlockState s = w.getBlockState(p);
                if (isVanillaWater(s)) {
                    topWater = p;
                    break;
                }
            }
        }

        if (topWater == null) {
            // No vanilla water in this column -> don't spawn a boat
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


    private boolean isBoatOnWater(EntityBoat boat) {
        World w = boat.world;
        BlockPos pos = new BlockPos(
                net.minecraft.util.math.MathHelper.floor(boat.posX),
                net.minecraft.util.math.MathHelper.floor(boat.posY - 0.2D),
                net.minecraft.util.math.MathHelper.floor(boat.posZ)
        );
        if (!w.isBlockLoaded(pos)) return false;

        IBlockState feet  = w.getBlockState(pos);
        IBlockState below = w.getBlockState(pos.down());
        return isVanillaWater(feet) || isVanillaWater(below);
    }

    // Simple vanilla-water check for 1.12.2
    private static boolean isVanillaWater(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }
    private void updateBoatConvoyFollow() {
        if (!(worker.getRidingEntity() instanceof EntityBoat)) {
            return;
        }
        EntityBoat boat = (EntityBoat) worker.getRidingEntity();
        if (boat.getControllingPassenger() != worker) {
            // just in case – passenger boats are not steered here
            worker.getNavigator().clearPath();
            worker.setSprinting(false);
            return;
        }

        EntityLivingBase owner = this.followTarget;
        if (owner == null || owner.isDead) {
            return; // fallback: you could also tryDismountAtShore(boat, false) here
        }

        double distToOwnerSq = boat.getDistanceSq(owner);
        boolean ownerInBoat = owner.isRiding() && owner.getRidingEntity() instanceof EntityBoat;
        boolean ownerOnLand = !ownerInBoat && !owner.isInWater();


        // --- 1) We are close to owner AND owner is on land → try to dismount onto shore near owner ---
        if (ownerOnLand && distToOwnerSq < 12.0D * 12.0D) {
            if (tryDismountAtShore(boat, true)) {
                return; // now on land; normal follow AI will take over next tick
            }
        }

        // --- 2) We are very close to solid ground in front while owner is not boating much ---
        // This is optional extra safeguard: if desired, you can also check for "boatHitShore"
        // and call tryDismountAtShore(boat, false) in that case.

        // --- 3) Otherwise, steer convoy as usual (column behaviour etc.) ---

        // Example minimal convoy steering (adjust to your existing code):

        // get owner's boat position if they are in one, otherwise owner position
        Vec3d targetPos;
        if (ownerInBoat) {
            EntityBoat ownerBoat = (EntityBoat) owner.getRidingEntity();
            targetPos = new Vec3d(ownerBoat.posX, ownerBoat.posY, ownerBoat.posZ);
        } else {
            targetPos = new Vec3d(owner.posX, owner.posY, owner.posZ);
        }

        Vec3d myPos = new Vec3d(boat.posX, boat.posY, boat.posZ);
        Vec3d offset = targetPos.subtract(myPos);
        double dist = offset.length();
        if (dist < 1e-4) return;

        // column behaviour: each follower gets a "slot" behind the owner
        int hash = worker.getUniqueID().hashCode();
        int columnIndex = (hash >>> 3) & 3; // 0..3
        double spacing = 3.0D;              // 3 blocks between boats

        // direction from owner toward us
        Vec3d forward = offset.normalize();

        // target slot behind owner boat
        double slotDist = (columnIndex + 1) * spacing;
        Vec3d slotPos = targetPos.subtract(forward.scale(slotDist));

        // small lateral jitter to avoid perfect overlap
        int lane = (hash & 1) == 0 ? -1 : 1;
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        slotPos = slotPos.add(right.scale(lane * 0.7D));

        Vec3d toSlot = slotPos.subtract(myPos);
        double slotD2 = toSlot.lengthSquared();
        if (slotD2 < 0.25D) {
            // close enough to slot: gently slow down
            boat.motionX *= TravellersModConfig.followerBoatConvoyBrakeNear; // e.g. 0.6
            boat.motionZ *= TravellersModConfig.followerBoatConvoyBrakeNear;
        } else {
            Vec3d dir = toSlot.normalize();

            // small "thrust" toward target slot
            double accel = TravellersModConfig.followerBoatConvoyAccel;    // e.g. 0.04
            double maxSpeed = TravellersModConfig.followerBoatConvoyMaxSpeed; // e.g. 0.45

            boat.motionX += dir.x * accel;
            boat.motionZ += dir.z * accel;

            // optional friction so they don't keep accelerating forever if accel is big
            boat.motionX *= 0.96D;
            boat.motionZ *= 0.96D;

            // --- HARD CAP ---
            double v2 = boat.motionX * boat.motionX + boat.motionZ * boat.motionZ;
            double maxV2 = maxSpeed * maxSpeed;
            if (v2 > maxV2) {
                double scale = maxSpeed / Math.sqrt(v2);
                boat.motionX *= scale;
                boat.motionZ *= scale;
            }
        }

// Rotate boat to face motion
        double v2Now = boat.motionX * boat.motionX + boat.motionZ * boat.motionZ;
        if (v2Now > 1.0E-4D) {
            float yaw = (float) (Math.atan2(boat.motionZ, boat.motionX) * 180.0D / Math.PI - 90.0D);
            boat.setLocationAndAngles(boat.posX, boat.posY, boat.posZ, yaw, boat.rotationPitch);
            boat.rotationYaw = yaw;
            boat.prevRotationYaw = yaw;

            worker.rotationYaw = yaw;
            worker.rotationYawHead = yaw;
            worker.renderYawOffset = yaw;
        }

// while boating, path is irrelevant
        worker.getNavigator().clearPath();
    }

// --- BOAT / SHORE HELPERS ---

    /**
     * Try to find a dry standable block near a reference column (boat or owner).
     * Similar to your travel AI's findDrySurfaceNear(..), but trimmed for followers.
     */
    @Nullable
    private BlockPos findDrySurfaceNearColumn(BlockPos guess) {
        World world = worker.world;
        if (!world.isBlockLoaded(guess)) return null;

        // Start at top solid/liquid at this XZ
        BlockPos top = world.getTopSolidOrLiquidBlock(guess);

        // Scan down a bit looking for "feet" with solid support + free head
        BlockPos p = top;
        for (int dy = 0; dy < 12 && p.getY() > 1; dy++) {
            if (isDryStandableFollower(p)) {
                return p;
            }
            p = p.down();
        }

        // Small lateral nudge if we landed on edge water / half blocks
        final int[][] OFF = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : OFF) {
            BlockPos n = new BlockPos(top.getX() + d[0], top.getY(), top.getZ() + d[1]);
            if (!world.isBlockLoaded(n)) continue;
            BlockPos downScan = n;
            for (int dy = 0; dy < 8 && downScan.getY() > 1; dy++) {
                if (isDryStandableFollower(downScan)) {
                    return downScan;
                }
                downScan = downScan.down();
            }
        }

        return null;
    }

    /**
     * Very small "can stand here" check for followers exiting boats.
     * Reuses your usual solid-below + free-feet + free-head pattern.
     */
    private boolean isDryStandableFollower(BlockPos feet) {
        World w = worker.world;
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;

        IBlockState below = w.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        IBlockState atFeet = w.getBlockState(feet);
        IBlockState atHead = w.getBlockState(feet.up());

        boolean freeFeet =
                atFeet.getMaterial().isReplaceable()
                        || atFeet.getCollisionBoundingBox(w, feet) == null;
        boolean freeHead =
                atHead.getMaterial().isReplaceable()
                        || atHead.getCollisionBoundingBox(w, feet.up()) == null;

        if (!freeFeet || !freeHead) return false;

        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        boolean solidBelow =
                shape == BlockFaceShape.SOLID
                        || below.getBlock() == Blocks.SNOW_LAYER
                        || below.getBlock() == Blocks.GRASS_PATH
                        || below.getBlock() == Blocks.ICE
                        || below.getBlock() == Blocks.PACKED_ICE;

        return solidBelow;
    }

    /**
     * Try to dismount this worker from its boat onto a nearby shore tile.
     * If 'preferOwnerShore' is true, we try to exit near the owner instead of near the boat.
     */
    private boolean tryDismountAtShore(EntityBoat boat, boolean preferOwnerShore) {
        EntityLivingBase owner = this.followTarget;
        World world = worker.world;

        BlockPos ref;
        if (preferOwnerShore && owner != null) {
            ref = new BlockPos(owner.posX, owner.posY, owner.posZ);
        } else {
            ref = new BlockPos(boat.posX, boat.posY, boat.posZ);
        }

        BlockPos exit = findDrySurfaceNearColumn(ref);
        if (exit == null) {
            // fallback: try around the boat itself
            ref = new BlockPos(boat.posX, boat.posY, boat.posZ);
            exit = findDrySurfaceNearColumn(ref);
            if (exit == null) return false;
        }

        double dist2 = exit.distanceSq(boat.posX, exit.getY(), boat.posZ);
        if (dist2 > 16.0D) {
            // we found something but it's too far ( > 4 blocks ) → don't dismount yet
            return false;
        }

        // Dismount and snap to shore
        worker.dismountRidingEntity();
        worker.setLocationAndAngles(
                exit.getX() + 0.5D,
                exit.getY(),
                exit.getZ() + 0.5D,
                worker.rotationYaw,
                worker.rotationPitch
        );
        worker.getNavigator().clearPath();
        worker.setSprinting(false);

        // If this is one of our convoy boats, you probably want to delete it.
        // (If you use EntityTravellerBoat, change the instanceof accordingly.)
        if (boat instanceof EntityTravellerBoat) {
            boat.setDead();
        }

        return true;
    }

}