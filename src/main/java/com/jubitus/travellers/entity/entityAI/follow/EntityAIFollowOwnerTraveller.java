package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.TravellerNavigateGround;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class EntityAIFollowOwnerTraveller extends EntityAIBase {


    // Only TP if far
    private static final double STUCK_TP_MIN_DIST = 24.0D;
    private static final double STUCK_TP_MIN_DIST_SQ = STUCK_TP_MIN_DIST * STUCK_TP_MIN_DIST;
    // How long with no progress before TP (not in LOS vs in LOS)
    private static final int NO_PROGRESS_TP_TICKS_NO_LOS = 40;   // 2s
    private static final int NO_PROGRESS_TP_TICKS_IN_LOS = 120;  // 6s "if possible" avoid popping in view
    // How much closer do we need to get per tick to count as progress
    private static final double PROGRESS_EPS = 0.06D;
    // TP step
    private static final double STUCK_TP_STEP = 5.0D;
    // Cooldown so we don't TP every tick if still trapped
    private static final int STUCK_TP_COOLDOWN = 60; // 3s
    private static final int NO_PROGRESS_TP_TICKS = 40;  // 2 seconds
    private static final double MAX_GOAL_STEP_PER_TICK = 0.45D; // clamp how fast the target moves
    // --- GROUP FORMATION (clusters) ---
    private static final int GROUPING_MIN_SIZE = 12;   // only cluster when 12+ followers
    private static final int GROUPS_SIZE_HINT = 8;    // ~8 followers per group
    // Prefer behind/sides; last ones add some front variety.
// Order matters: we use the first N offsets.
    private static final double[] GROUP_ANGLE_OFFSETS = new double[]{
            Math.PI,                 // behind
            3.0 * Math.PI / 4.0,     // behind-left
            -3.0 * Math.PI / 4.0,    // behind-right
            Math.PI / 2.0,           // left
            -Math.PI / 2.0,          // right
            Math.PI / 4.0,           // front-left
            -Math.PI / 4.0,          // front-right
            0.0                      // front
    };
    // --- cached group size per owner (updated at most once per second) ---
    private static final java.util.Map<java.util.UUID, GroupCache> GROUP_CACHE = new java.util.HashMap<>();
    private static final double DIR_SAMPLE_STEP = 0.20D; // blocks moved before updating formation heading
    private static final double MAX_TURN_RAD_PER_TICK = 4.5D * (Math.PI / 180.0D); // ~4.5°/tick
    // --- Path reuse thresholds ---
    private static final double GOAL_CHANGE_EPS_SQ = 4.5D * 4.5D;
    private static final double MIN_REPATH_DIST_SQ = 3.5D * 3.5D;   // was 2.5^2
    // When owner is idle, we stop once we're "close enough" to the locked slot
    private static final double IDLE_APPROX_STOP_RADIUS = 4.0D; // blocks
    private static final double IDLE_APPROX_STOP_RADIUS_SQ = IDLE_APPROX_STOP_RADIUS * IDLE_APPROX_STOP_RADIUS;
    private static final int OFFSCREEN_HOP_CD = 20;     // once per second
    private static final double OFFSCREEN_HOP_DIST_SQ = 20.0D * 20.0D; // only hop if far
    private static final double UNDERGROUND_FOLLOW_RADIUS = 3.0D; // stop ~3 blocks away
    private static final double BOAT_STUCK_MOVE_EPS_SQ = 0.02D * 0.02D; // ~0.02 blocks/tick
    private static final int BOAT_STUCK_TICKS = 60;  // 3s of not moving while trying
    private static final int BOAT_BEACHED_TICKS = 20; // 1s on land/beached
    // movement threshold + time before forcing dismount
    private static final double BOAT_STILL_EPS_SQ = 0.0025D; // (0.05 blocks)^2
    private static final int BOAT_STILL_FORCE_TICKS = 20;    // 1s
    private static final double OWNER_NOT_NEAR_SQ = 8.0D * 8.0D; // tune
    // tune these to taste
    private static final int MAX_START_DELAY_TICKS = 8;  // up to ~0.4s
    private static final int MAX_STOP_DELAY_TICKS = 10; // up to ~0.5s
    // --- Boat convoy behaviour (followers) ---
    private static final int BOAT_MAX_PASSENGERS = 2;        // vanilla boat
    private static final double BOAT_BOARD_RADIUS = 2.5D;     // gilles; can also read from config
    private static final double BOAT_CONVOY_SPEED = 0.9D;     // multiplier for boat motion
    private static final int BOAT_SPAWN_COOLDOWN_TICKS = 60;     // 3s between spawn attempts
    private static final double BOAT_SHARE_SEARCH_RADIUS = 8.0D;   // search radius for other boats
    // --- boat-follow state ---
    private static final double BOAT_MIN_SWIM_RADIUS = 4.0D; // treat as "arrived" if this close while boat is stopped
    private static final double MOTION_EPSILON = 0.0004D;     // ~0.02 blocks/tick
    private static final int IDLE_TICKS_THRESHOLD = 60;       // 1 second
    private static final double IDLE_BAND = 4.0D;            // how close we need to be to idle
    // boat “is moving” threshold
    private static final double BOAT_MOVE_EPS_SQ = 0.0004D; // tune if needed
    /**
     * Max distance (blocks) for any single path target segment
     */
    private static final double SEGMENT_MAX_DIST = 32.0D;
    /**
     * How often (ticks) to check if we’re stuck
     */
    private static final int STUCK_CHECK_INTERVAL = 20; // 1s
    /**
     * How long (ticks) of being stuck before teleporting
     */
    private static final int STUCK_TP_THRESHOLD = 20 * 8; // 8s
    private final EntityTravellerWorker worker;
    private final double speed;
    private final float startDist;   // begin following if beyond this
    private final float stopDist;    // stop following once inside this
    private double lastDistToOwnerProg = Double.NaN;
    private int noProgressTicksProg = 0;
    private int stuckTpCooldownTicks = 0;
    private double lastDistToOwner = Double.NaN;
    // --- smooth goal so it doesn't jump -> fewer repaths, less zigzag ---
    private boolean hasSmoothGoal = false;
    private double smoothGoalX, smoothGoalY, smoothGoalZ;
    // --- stable cluster assignment per owner ---
    private java.util.UUID lastGroupOwner = null;
    private int stableGroupId = -1;
    private int stableGroupCount = 2;
    private long stableGroupTick = 0;
    // --- MOVING SLOT STABILITY (precompute + smooth heading) ---
    private boolean slotInit = false;
    private int slotRole;           // 0..3 (behind/left/right/front)
    private int slotRSlot;          // 0..7
    private double slotBaseOffset;  // fixed per worker
    // Smoothed formation forward direction (what the ring is oriented around)
    private double formDirX = 0.0D;
    private double formDirZ = 1.0D;
    private boolean hasFormDir = false;
    // Only update direction when owner actually advanced (prevents turn-in-place swing)
    private double ownerMoveAccum = 0.0D;
    private double lastOwnerSampleX = Double.NaN;
    private double lastOwnerSampleZ = Double.NaN;
    private int waitingForPathTicks = 0; // prevents stuck teleport while budget-limited
    private boolean prevOwnerRawMoving = false;
    private final double idleHoldRadius = 0.0D; // radius to keep while owner is idle
    // --- extra owner movement tracking (fixes player-walk not detected) ---
    private double lastSeenOwnerX = Double.NaN;
    private double lastSeenOwnerZ = Double.NaN;
    // --- IDLE SLOT LOCK (prevents ping-pong near cliffs) ---
    private boolean idleSlotLocked = false;
    private double idleSlotX, idleSlotY, idleSlotZ;
    // --- OFFSCREEN FOLLOW (no pathfinding) ---
    private int offscreenHopCooldown = 0;
    // --- boat stuck detection ---
    private Vec3d lastBoatPosStill = Vec3d.ZERO;
    private Vec3d lastBoatPosStuck = Vec3d.ZERO;
    private int boatCollideTicks = 0;
    private int boatNotMovingTicks = 0;
    private int boatBeachedTicks = 0;
    private int boatStillTicks = 0;
    private double lastOwnerDist = 0.0D;
    private double smoothedMoveSpeed = 0.0D;
    // --- per-follower reaction delays ---
    private boolean lastOwnerMoving = false;
    private int startMoveDelayTicks = 0; // delay before reacting when owner starts moving
    private int stopMoveDelayTicks = 0; // delay before going fully idle when owner stops
    // Debounce "owner started moving" → followers wait a bit before chasing again
    private int ownerMoveDebounceTicks = 0;
    private boolean lastTargetMoving = false;
    private int boatSpawnCooldown = 0;
    private int boatFollowRepathTicks = 0;
    private int targetStillTicks = 0;
    private final double lastDirX = 0.0D;
    private final double lastDirZ = 1.0D;  // gilles: facing +Z
    private final boolean hasLastDir = false;
    private EntityLivingBase followTarget;
    private int timeToRecalcPath;
    // --- Owner boat heading cache (prevents circling / flipping when passing) ---
    private double lastOwnerBoatDirX = 0.0D;

    // --- long-range follow / anti-stuck tuning ---
    private double lastOwnerBoatDirZ = 1.0D;
    private boolean hasOwnerBoatDir = false;
    private int stuckCheckTicks = 0;
    private int stuckForTicks = 0;
    // --- "no progress toward owner" escape (pit/hole fix) ---
    private double lastDistToOwnerForProgress = Double.NaN;
    private double lastPosX;
    private double lastPosZ;
    private double cachedGoalX, cachedGoalY, cachedGoalZ;
    private boolean hasCachedGoal = false;
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

    // Simple vanilla-water check for 1.12.2
    private static boolean isVanillaWater(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }

    private static boolean isFoliageLike(Block b) {
        // 1.12.2 safe checks (covers modded leaves/logs too)
        return (b instanceof net.minecraft.block.BlockLeaves)
                || (b instanceof net.minecraft.block.BlockLog)
                || b == Blocks.LEAVES || b == Blocks.LEAVES2
                || b == Blocks.LOG || b == Blocks.LOG2
                || b == Blocks.VINE;
    }

    @Override
    public boolean shouldExecute() {
        if (this.worker.getAttackTarget() != null || this.worker.isInCombat()) return false;
        if (hasValidCombatTarget()) return false; // <-- yield to combat
        if (worker.isCatchupTravel()) return false;
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
        if (this.worker.getAttackTarget() != null || this.worker.isInCombat()) return false;
        if (hasValidCombatTarget()) return false; // <-- yield to combat
        if (worker.isCatchupTravel()) return false;
        if (!worker.isHiredFollower()) return false;
        if (!worker.isFollowOrderActive()) return false;

        // NEW: allow during RETURNING if worker decided to switch to follow
        if (worker.isJobActive() && !worker.isReturnUsingFollow()) return false;

        EntityLivingBase current = worker.getEffectiveFollowTarget();
        if (current == null || current.isDead) return false;
        if (current instanceof EntityPlayer && ((EntityPlayer) current).isSpectator()) return false;

        // ---- NEW: if target is too far, yield MOVE to Travel/Catchup ----
        final double switchD = TravellersModConfig.returnFollowSwitchDist; // add this in config
        double d2 = worker.getDistanceSq(current);

        // If you're far beyond what follow should handle (teleport / huge separation),
        // stop this AI so Travel AI can run.
        if (d2 > switchD * switchD) {
            worker.getNavigator().clearPath();

            // Optional: tell worker to enter catchup-travel mode here
            // (recommended if you added that flag)
            worker.startCatchupTravelTo(current);


            return false;
        }

        this.followTarget = current;
        return true;
    }

    @Override
    public void startExecuting() {
        lastBoatPosStill = Vec3d.ZERO;
        lastBoatPosStuck = Vec3d.ZERO;
        boatNotMovingTicks = 0;
        boatBeachedTicks = 0;
        boatCollideTicks = 0;
        boatStillTicks = 0;
        idleSlotLocked = false;
        hasSmoothGoal = false;
        lastDistToOwner = Double.NaN;
        lastDistToOwnerForProgress = Double.NaN;
        lastDistToOwnerProg = Double.NaN;
        stuckTpCooldownTicks = 0;
        noProgressTicksProg = 0;


        PathNavigate nav = worker.getNavigator();
        if (nav instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) nav).setFollowerMode(true);
        }


        this.timeToRecalcPath = 0;
        this.smoothedMoveSpeed = 0.0D;
        this.stuckCheckTicks = 0;
        this.stuckForTicks = 0;
        this.lastPosX = worker.posX;
        this.lastPosZ = worker.posZ;

        this.lastOwnerMoving = false;
        this.startMoveDelayTicks = 0;
        this.stopMoveDelayTicks = 0;
        noProgressTicksProg = 0;
        lastDistToOwnerProg = Double.NaN;


        if (followTarget != null) {
            lastSeenOwnerX = followTarget.posX;
            lastSeenOwnerZ = followTarget.posZ;
        } else {
            lastSeenOwnerX = Double.NaN;
            lastSeenOwnerZ = Double.NaN;
        }

        if (followTarget != null) {
            this.lastOwnerDist = worker.getDistance(followTarget);
        } else {
            this.lastOwnerDist = 0.0D;
        }
        initMovingSlotIfNeeded();

        hasFormDir = false;
        ownerMoveAccum = 0.0D;
        lastOwnerSampleX = (followTarget != null) ? followTarget.posX : Double.NaN;
        lastOwnerSampleZ = (followTarget != null) ? followTarget.posZ : Double.NaN;

    }

    @Override
    public void resetTask() {
        lastBoatPosStill = Vec3d.ZERO;
        lastBoatPosStuck = Vec3d.ZERO;
        boatNotMovingTicks = 0;
        boatBeachedTicks = 0;
        boatCollideTicks = 0;
        boatStillTicks = 0;
        idleSlotLocked = false;
        hasSmoothGoal = false;
        lastDistToOwner = Double.NaN;
        lastDistToOwnerForProgress = Double.NaN;
        lastDistToOwnerProg = Double.NaN;
        stuckTpCooldownTicks = 0;

        PathNavigate nav = worker.getNavigator();
        if (nav instanceof TravellerNavigateGround) {
            ((TravellerNavigateGround) nav).setFollowerMode(false);
        }


        lastSeenOwnerX = Double.NaN;
        lastSeenOwnerZ = Double.NaN;

        this.smoothedMoveSpeed = 0.0D;
        this.followTarget = null;
        worker.getNavigator().clearPath();
        hasCachedGoal = false;

        worker.setSprinting(false);

        this.stuckCheckTicks = 0;
        this.stuckForTicks = 0;

        this.lastOwnerMoving = false;
        this.startMoveDelayTicks = 0;
        this.stopMoveDelayTicks = 0;
        this.lastOwnerDist = 0.0D;
    }

    @Override
    public void updateTask() {
        if (waitingForPathTicks > 0) waitingForPathTicks--;

        boolean needsTick = worker.getNavigator().noPath() || waitingForPathTicks > 0 || stuckForTicks > 0;
        if (!needsTick && ((worker.ticksExisted + worker.getEntityId()) & 1) == 1) {
            return;
        }

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
            hasSmoothGoal = false;
            lastDistToOwnerForProgress = Double.NaN;

        }

        if (followTarget == null) {
            return;
        }
// -----------------------------------------
// OFFSCREEN: do not pathfind; hop/teleport toward owner instead
// -----------------------------------------
        if (!worker.world.isRemote && !worker.isInPlayerViewDistance()) {
            worker.getNavigator().clearPath();
            hasCachedGoal = false;
            worker.setSprinting(false);

            if (offscreenHopCooldown > 0) offscreenHopCooldown--;

            // only hop occasionally (cheap) and only if we're not already basically with the owner
            if (offscreenHopCooldown == 0 && worker.getDistanceSq(followTarget) > OFFSCREEN_HOP_DIST_SQ) {
                offscreenHopCooldown = OFFSCREEN_HOP_CD;
                offscreenHopNearOwner();   // helper you’ll add below
            }
            return; // IMPORTANT: skip ALL pathfinding below
        }

        // --- NEW: convoy logic when worker is in a boat ---
        if (TravellersModConfig.followerBoatConvoyEnabled
                && worker.isRiding()
                && worker.getRidingEntity() instanceof EntityBoat) {

            EntityBoat boat = (EntityBoat) worker.getRidingEntity();

            if (boat.getControllingPassenger() != worker) {
                worker.getNavigator().clearPath();
                hasCachedGoal = false;

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
        double distToOwner = Math.sqrt(distSqToOwner);
        int groupSize = getCachedGroupSize(followTarget); // compute ONCE, reuse later
        boolean ownerUnderground = isOwnerUnderground(followTarget) && groupSize < 12;

        if (ownerUnderground) {
            // If already close enough, stop (prevents crowding)
            double stopR = 3.0D;
            if (distToOwner <= stopR) {
                worker.getNavigator().clearPath();
                hasCachedGoal = false;
                worker.setSprinting(false);
                return;
            }

            double dy = worker.posY - followTarget.posY;
            double dx = worker.posX - followTarget.posX;
            double dz = worker.posZ - followTarget.posZ;
            double horizSq = dx * dx + dz * dz;

// If we're basically above the owner but can't navigate down, snap near owner Y.
            if (!worker.world.isRemote && dy > 5.0D && horizSq < 5.0D * 5.0D) {
                BlockPos snap = findStandableNearY(followTarget.posX, followTarget.posY, followTarget.posZ, 7, 3);
                if (snap != null) {
                    worker.setLocationAndAngles(snap.getX() + 0.5D, snap.getY(), snap.getZ() + 0.5D,
                            worker.rotationYaw, worker.rotationPitch);
                    worker.getNavigator().clearPath();
                    hasCachedGoal = false;
                    return;
                }
            }

            BlockPos stand = getUndergroundFollowStandPos(followTarget);
            if (stand != null) {
                cachedGoalX = stand.getX() + 0.5D;
                cachedGoalY = stand.getY();
                cachedGoalZ = stand.getZ() + 0.5D;
                hasCachedGoal = true;

                // Use a steady speed underground (optional tuning)
                double spd = Math.max(0.18D, Math.min(smoothedMoveSpeed > 0 ? smoothedMoveSpeed : this.speed, 0.85D));
                if (!PathScheduler.tryAcquire(worker.world, followTarget, worker.getEntityId(), /*priority*/1, groupSize)) {
                    worker.getMoveHelper().setMoveTo(cachedGoalX, cachedGoalY, cachedGoalZ, spd);
                    waitingForPathTicks = 10;
                } else {
                    worker.getNavigator().tryMoveToXYZ(cachedGoalX, cachedGoalY, cachedGoalZ, spd);
                }
            } else {
                // worst-case fallback
                worker.getNavigator().tryMoveToEntityLiving(followTarget, this.speed);
            }

            worker.setSprinting(false);
            return; // IMPORTANT: skip all formation slot logic below
        }

        if (distToOwner > 256.0D && worker.getNavigator().noPath()) {
            // ~6 blocks/sec if applied every tick (0.3 blocks/tick is ~6 silva/s)
            double step = 0.30D;

            double dx = followTarget.posX - worker.posX;
            double dz = followTarget.posZ - worker.posZ;
            double lenSq = dx * dx + dz * dz;

            if (lenSq > 1e-6) {
                double len = Math.sqrt(lenSq);
                dx /= len;
                dz /= len;

                double nx = worker.posX + dx * step;
                double nz = worker.posZ + dz * step;

                BlockPos feet = findSafeStandPos(nx, followTarget.posY, nz, !ownerUnderground);
                worker.setLocationAndAngles(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                        worker.rotationYaw, worker.rotationPitch);
                worker.getNavigator().clearPath();
                hasCachedGoal = false;

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
                hasCachedGoal = false;

                worker.setSprinting(false);

                // reset stuck timers: we don't care while seated
                this.stuckCheckTicks = 0;
                this.stuckForTicks = 0;
                this.lastPosX = worker.posX;
                this.lastPosZ = worker.posZ;
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
                    BlockPos bp = new BlockPos(ownerBoat.posX, ownerBoat.posY, ownerBoat.posZ);
                    if (!worker.world.isBlockLoaded(bp)) {
                        // don’t force chunk loads here; just wait
                        return;
                    }

                    // Close enough: hop in as passenger
                    if (worker.startRiding(ownerBoat, true)) {
                        worker.setPositionAndUpdate(ownerBoat.posX, ownerBoat.posY, ownerBoat.posZ);
                        worker.motionX = worker.motionY = worker.motionZ = 0;
                        worker.fallDistance = 0;
                    }

                    worker.getNavigator().clearPath();
                    hasCachedGoal = false;
                    boatNotMovingTicks = 0;
                    boatBeachedTicks = 0;


                    worker.setSprinting(false);

                    this.stuckCheckTicks = 0;
                    this.stuckForTicks = 0;
                    this.lastPosX = worker.posX;
                    this.lastPosZ = worker.posZ;
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
            hasCachedGoal = false;

        }

// --- Detect movement of follow target with a "still for a while" concept ---

        // --- Detect movement of follow target with a "still for a while" concept ---

        final boolean targetIsPlayer = (followTarget instanceof EntityPlayer);
        final boolean targetIsWorkerLeader = (followTarget instanceof EntityTravellerWorker);


// leader can repath a bit more often (optional)
        if (targetIsWorkerLeader) {
            timeToRecalcPath = Math.min(timeToRecalcPath, 12);
        }

        final boolean leaderUsingTravelAI =
                targetIsWorkerLeader && ((EntityTravellerWorker) followTarget).isUsingTravelAI();

// 1) Position delta (sometimes unreliable for players)
        double dpX = followTarget.posX - followTarget.prevPosX;
        double dpZ = followTarget.posZ - followTarget.prevPosZ;
        double posDeltaSq = dpX * dpX + dpZ * dpZ;

// 2) Motion delta (often better for players)
        double moX = followTarget.motionX;
        double moZ = followTarget.motionZ;
        double motionSq = moX * moX + moZ * moZ;

// 3) Fallback: our own stored last position (always reliable)
        double lpX = 0.0D, lpZ = 0.0D, lastPosSq = 0.0D;
        if (!Double.isNaN(lastSeenOwnerX)) {
            lpX = followTarget.posX - lastSeenOwnerX;
            lpZ = followTarget.posZ - lastSeenOwnerZ;
            lastPosSq = lpX * lpX + lpZ * lpZ;
        }

// Choose the strongest signal each tick
        double mvX = dpX;
        double mvZ = dpZ;
        double mvLenSq = posDeltaSq;

        if (motionSq > mvLenSq) {
            mvX = moX;
            mvZ = moZ;
            mvLenSq = motionSq;
        }
        if (lastPosSq > mvLenSq) {
            mvX = lpX;
            mvZ = lpZ;
            mvLenSq = lastPosSq;
        }

// Update last-seen owner position for next tick
        lastSeenOwnerX = followTarget.posX;
        lastSeenOwnerZ = followTarget.posZ;

// Thresholds: make player walking count as moving
        final double MOVE_EPS_SQ =
                targetIsPlayer ? 1.0e-8D : (leaderUsingTravelAI ? 2.0e-5D : 4.0e-4D);

        boolean targetMoving = (mvLenSq > MOVE_EPS_SQ);

// ownerSpeed for your later speed scaling
        double ownerSpeed = Math.sqrt(mvLenSq);

        boolean followTargetSprinting = followTarget.isSprinting();
        boolean followTargetSneaking = followTarget.isSneaking();

// “Raw moving” includes real movement + sneak/sprint
        boolean ownerRawMoving = targetMoving || followTargetSprinting || followTargetSneaking;

// Stillness counter based on ownerRawMoving
        if (ownerRawMoving) targetStillTicks = 0;
        else targetStillTicks++;

// --- edge detect (per-follower) ---
        boolean ownerStartedMoving = ownerRawMoving && !prevOwnerRawMoving;
        boolean ownerStoppedMoving = !ownerRawMoving && prevOwnerRawMoving;

        int h = worker.getUniqueID().hashCode();

// On start: random hesitation so they don't all move same tick
        if (ownerStartedMoving) {
            startMoveDelayTicks = Math.abs((h >>> 6) % (MAX_START_DELAY_TICKS + 1));
            stopMoveDelayTicks = 0;
        }


// On stop: random "coast" so they don't all freeze same tick
        if (ownerStoppedMoving) {
            stopMoveDelayTicks = Math.abs((h >>> 10) % (MAX_STOP_DELAY_TICKS + 1));
            startMoveDelayTicks = 0;

            // force slot relock / repath to idle slot
            idleSlotLocked = false;
            hasCachedGoal = false;
            timeToRecalcPath = 0;
        }

        prevOwnerRawMoving = ownerRawMoving;

// tick down delays
        if (ownerRawMoving && startMoveDelayTicks > 0) startMoveDelayTicks--;
        if (!ownerRawMoving && stopMoveDelayTicks > 0) stopMoveDelayTicks--;

        // This is what THIS follower considers "moving".
// - During start delay, we pretend owner is not moving yet.
// - During stop delay, we pretend owner is still moving a bit.
        boolean ownerMovingForMe = ownerRawMoving;
        if (ownerRawMoving && startMoveDelayTicks > 0) ownerMovingForMe = false;
        if (!ownerRawMoving && stopMoveDelayTicks > 0) ownerMovingForMe = true;


// If owner starts moving again, unlock idle slot and resume normal behavior immediately
        if (ownerMovingForMe && idleSlotLocked) {
            idleSlotLocked = false;
            hasCachedGoal = false;
            timeToRecalcPath = 0;
        }

// Detect start/stop edges and roll random delays

        lastOwnerMoving = ownerRawMoving;

// --- START-MOVE DELAY: follower hesitates a bit before starting to walk/run ---


// --- HARD IDLE: owner is properly stopped, follower is near ---
//        boolean ownerHardIdle = !ownerRawMoving && targetStillTicks >= 5; // ~1s of no movement
//        final double idleRadius = 2.0D;
//
//        if (ownerHardIdle && distToOwner <= idleRadius && stopMoveDelayTicks <= 0) {
//            worker.setSprinting(false);
//            worker.getNavigator().clearPath();
//            hasCachedGoal = false;
//
//            this.stuckCheckTicks = 0;
//            this.stuckForTicks   = 0;
//            this.lastPosX        = worker.posX;
//            this.lastPosZ        = worker.posZ;
//
//            return;
//        }
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


// Throttle path recalculation
// Throttle path recalculation ONLY while we still have a path.
// If the path ended early, repath immediately (prevents stop+spin).
        if (--timeToRecalcPath > 0) {
            if (!worker.getNavigator().noPath()) {
                return; // path still running -> let it run
            }

            // Path is gone, but we're still inside cooldown.
            // Don't "drive blind" with setMoveTo; just repath now (scheduler will throttle).
            timeToRecalcPath = 0;
        }


// ~0.03 blocks/tick for AI, ~0.01 for players (tweak)
        final double DIR_EPS_SQ = targetIsPlayer ? 1.0e-4D
                : (leaderUsingTravelAI ? 6.0e-5D : 9.0e-4D);

        if (mvLenSq > DIR_EPS_SQ) {
            double mvLen = Math.sqrt(mvLenSq);
            double ndX = mvX / mvLen;
            double ndZ = mvZ / mvLen;

            // accumulate real distance moved since last sample
            if (!Double.isNaN(lastOwnerSampleX)) {
                double sx = followTarget.posX - lastOwnerSampleX;
                double sz = followTarget.posZ - lastOwnerSampleZ;
                ownerMoveAccum += Math.sqrt(sx * sx + sz * sz);
            }
            lastOwnerSampleX = followTarget.posX;
            lastOwnerSampleZ = followTarget.posZ;

            // only update formation heading every ~0.2 blocks of real movement
            if (ownerMoveAccum >= DIR_SAMPLE_STEP || !hasFormDir) {
                ownerMoveAccum = 0.0D;
                updateFormationDir(ndX, ndZ, followTargetSprinting);
            }
        } else {
            // owner not moving: do NOT rotate formation.
            if (!hasFormDir) {
                float bodyYaw = followTarget.renderYawOffset;
                double yawRad = Math.toRadians(bodyYaw);
                formDirX = -Math.sin(yawRad);
                formDirZ = Math.cos(yawRad);
                hasFormDir = true;
            }
        }


        double forwardX = formDirX;
        double forwardZ = formDirZ;

        int hash = worker.getUniqueID().hashCode();

        // --- follower "role": behind, side, or light-front escort ---
        int role = slotRole;
        int rSlot = slotRSlot;
        double baseOffset = slotBaseOffset;

// NEW: tighter while moving, looser while idle
        double baseIdle = TravellersModConfig.followerRingBaseRadius;      // e.g. 6-8
        double stepIdle = TravellersModConfig.followerRingSlotStep;        // e.g. 0.7-1.0

        double baseMove = TravellersModConfig.followerRingMoveBaseRadius;  // e.g. 2.2-3.2 (ADD THIS CONFIG)
        double stepMove = TravellersModConfig.followerRingMoveSlotStep;    // e.g. 0.3-0.6 (ADD THIS CONFIG)

        double base = ownerMovingForMe ? baseMove : baseIdle;
        double step = ownerMovingForMe ? stepMove : stepIdle;


// Example: add +1 tick cooldown per 4 followers (caps at +20)
        int extraCooldown = Math.min(20, groupSize / 4);


// As group grows, expand formation so they don't crowd the player.
// These numbers are safe to start with; tweak later.


        double behindAngle = Math.atan2(-forwardZ, -forwardX);
        double forwardAngle = Math.atan2(forwardZ, forwardX);

        double theta;
        double radius;

        // Angle slot for some variety
        int angleSlot = (hash >>> 4) & 15;        // 0..15

        switch (role) {
            default:
            case 0:
                // Classic: behind in a loose arc
                radius = base + rSlot * step;
                theta = behindAngle + baseOffset * 1.2D;
                break;

            case 1:
                // Side-left escort
                radius = base + (rSlot * step * 0.7D);
                theta = forwardAngle + (Math.PI / 2.5D) + baseOffset * 0.5D;
                break;

            case 2:
                // Side-right escort
                radius = base + (rSlot * step * 0.7D);
                theta = forwardAngle - (Math.PI / 2.5D) + baseOffset * 0.5D;
                break;

            case 3:
                // Light front-diagonal escort
                radius = base + 1.0D + (rSlot * step * 0.4D);
                double frontSide = ((hash & 1) == 0) ? +1.0D : -1.0D;
                theta = forwardAngle + frontSide * (Math.PI / 3.0D) + baseOffset * 0.3D;
                break;
        }

        double targetX, targetZ;
        double targetY = followTarget.posY;

// --- NEW: cluster formation when the group is large ---
        if (groupSize >= GROUPING_MIN_SIZE) {

            int desiredCount = computeGroupCount(groupSize);
            java.util.UUID oid = followTarget.getUniqueID();

// Reset if owner changes
            if (lastGroupOwner == null || !lastGroupOwner.equals(oid)) {
                lastGroupOwner = oid;
                stableGroupCount = desiredCount;
                stableGroupId = Math.floorMod(hash, stableGroupCount);
                stableGroupTick = worker.world.getTotalWorldTime();
            } else {
                // Only change cluster count rarely (hysteresis) to avoid reshuffles
                long now = worker.world.getTotalWorldTime();
                if (desiredCount != stableGroupCount && (now - stableGroupTick) > 200) { // 10s
                    stableGroupCount = desiredCount;
                    stableGroupId = Math.floorMod(hash, stableGroupCount);
                    stableGroupTick = now;
                }
            }

            int groupCount = stableGroupCount;
            int groupId = stableGroupId;


            // small stable "random" rotation per-owner (so every owner doesn't look identical)
            int ownerHash = followTarget.getUniqueID().hashCode();
            double ownerJitter = (((ownerHash >>> 8) & 1023) / 1023.0D - 0.5D) * 0.70D; // about +/- 0.35 rad

            // stable per-owner base angle in WORLD space (doesn't rotate when owner turns)
            double ownerBaseAngle = ((ownerHash & 0xFFFF) / 65535.0D) * (Math.PI * 2.0D);

            // group center direction around the owner, oriented by movement direction (forwardAngle)
            double groupTheta = ownerBaseAngle + GROUP_ANGLE_OFFSETS[groupId] + ownerJitter;

            // group center radius: NOT too close to the owner
            // (tune these if you want them further/closer)
            double groupR = MathHelper.clamp(base + 4.0D, 8.0D, 16.0D);

            double groupCX = followTarget.posX + Math.cos(groupTheta) * groupR;
            double groupCZ = followTarget.posZ + Math.sin(groupTheta) * groupR;

            if (ownerMovingForMe) {
                double trail = 3.0D;
                groupCX -= forwardX * trail;
                groupCZ -= forwardZ * trail;
            }


            // --- small local offset inside the cluster (human-like clump) ---
            int localAngleSlot = (hash >>> 10) & 31; // 0..31
            double localTheta = (localAngleSlot / 32.0D) * (Math.PI * 2.0D);

            int localRing = (hash >>> 6) & 3; // 0..3 (4 "layers" inside the group)
            double localBaseR = ownerMovingForMe ? 1.6D : 2.2D;
            double localStepR = ownerMovingForMe ? 0.55D : 0.75D;
            double localR = localBaseR + localRing * localStepR;

            targetX = groupCX + Math.cos(localTheta) * localR;
            targetZ = groupCZ + Math.sin(localTheta) * localR;

        } else {
            // --- your original ring behaviour for small groups ---
            targetX = followTarget.posX + Math.cos(theta) * radius;
            targetZ = followTarget.posZ + Math.sin(theta) * radius;
        }


        if (ownerMovingForMe) {
            double lookAhead = 0.6D + MathHelper.clamp(ownerSpeed * 12.0D, 0.0D, 2.0D); // ~0.6..2.6
            targetX += forwardX * lookAhead;
            targetZ += forwardZ * lookAhead;
        }


        // --- NEW: Tight underground tunnels/caves -> single-file follow (no ring offsets) ---
        boolean tight = isTightUnderground(followTarget);
        if (tight) {
            // Use owner's body yaw as a stable "behind" direction
            float yaw = followTarget.renderYawOffset;
            double yawRad = Math.toRadians(yaw);

            double fwdX = -Math.sin(yawRad);
            double fwdZ = Math.cos(yawRad);

            // Stand ~1.2 blocks behind the owner (single-file)
            double behind = 1.2D;

            targetX = followTarget.posX - fwdX * behind;
            targetZ = followTarget.posZ - fwdZ * behind;
            targetY = followTarget.posY;

            // IMPORTANT: disable jitter in tight spaces
            // (we’ll also set jitterRadius = 0 below by checking `tight`)
        }


        double dyOwner = followTarget.posY - worker.posY;
        if (dyOwner < -1.5D && distToOwner < 20.0D) {
            // Go much closer so we actually commit to entering the hole
            radius = 2.0D;
            theta = behindAngle;
            targetX = followTarget.posX + Math.cos(theta) * radius;
            targetZ = followTarget.posZ + Math.sin(theta) * radius;
            targetY = followTarget.posY;
        }


        if (ownerUnderground) {
            // Don’t use ring slots that could be inside rock. Keep it simple and on the same level.
            // (You can keep your "behind" logic; just constrain to valid standable space near owner Y.)

            BlockPos stand = findStandableNearY(targetX, followTarget.posY, targetZ, 4, 2);

            if (stand != null) {
                targetX = stand.getX() + 0.5D;
                targetY = stand.getY();
                targetZ = stand.getZ() + 0.5D;
            } else {
                // If we can't find a standable slot near that X/Z at owner's Y, just follow owner directly.
                worker.getNavigator().tryMoveToEntityLiving(followTarget, smoothedMoveSpeed > 0 ? smoothedMoveSpeed : this.speed);
                return;
            }
        }


        // --- 3) Small jitter = "not extremely precise" ---

// ----------------------------------------------------
// IDLE SLOT LOCK: when owner is idle, pick ONE slot and keep it
// ----------------------------------------------------
// ----------------------------------------------------
// IDLE SLOT LOCK: when owner is idle, pick ONE slot and keep it
// ----------------------------------------------------
        boolean ownerHardIdle = !ownerMovingForMe
                && targetStillTicks >= idleTicksThreshold
                && distToOwner < 64.0D;


        if (ownerHardIdle) {

            // 1) Lock a slot ONCE
            if (!idleSlotLocked) {
                boolean ownerUndergroundNow = isOwnerUnderground(followTarget);

                // Lock the CURRENT computed slot to a safe standable block
                BlockPos feet = findSafeStandPosNoCliff(targetX, followTarget.posY, targetZ, !ownerUndergroundNow);

                idleSlotX = feet.getX() + 0.5D;
                idleSlotY = feet.getY();
                idleSlotZ = feet.getZ() + 0.5D;

                idleSlotLocked = true;

                // Force a fresh path to the locked goal
                hasCachedGoal = false;
                timeToRecalcPath = 0;
            }

            // 2) Use locked slot only (do NOT keep recomputing formation while idle)
            double dxI = worker.posX - idleSlotX;
            double dzI = worker.posZ - idleSlotZ;
            double dyI = worker.posY - idleSlotY;

            double dist2Dsq = dxI * dxI + dzI * dzI;
            double dyAbsI = Math.abs(dyI);

            // 3) If we reached slot => STOP + idle and RETURN EARLY
            if (dist2Dsq <= IDLE_APPROX_STOP_RADIUS_SQ && dyAbsI < 3.4D && stopMoveDelayTicks <= 0) {

                hardStop();

                // ---- START IDLE BEHAVIOR HERE ----
                // If you have a method/state for idle, call it here.
                // e.g. worker.setIdle(true); or worker.startIdleBehavior();
                // ---------------------------------

                // reset stuck timers so we don't teleport while happily idling
                this.stuckCheckTicks = 0;
                this.stuckForTicks = 0;
                this.lastPosX = worker.posX;
                this.lastPosZ = worker.posZ;

                return; // IMPORTANT: nothing below runs
            }

            // 4) Not at slot yet => walk to slot gently, but DON'T do fallback setMoveTo spam
            // (that fallback is a common cause of fast spinning)
            double idleSpeed = 0.35D; // tune 0.25..0.45

            if (--timeToRecalcPath <= 0 || worker.getNavigator().noPath()) {
                timeToRecalcPath = 20; // repath at most once per second

                int groupSizeHere = getCachedGroupSize(followTarget);
                if (PathScheduler.tryAcquire(worker.world, followTarget, worker.getEntityId(), /*priority*/0, groupSizeHere)) {
                    worker.getNavigator().tryMoveToXYZ(idleSlotX, idleSlotY, idleSlotZ, idleSpeed);
                } else {
                    // IMPORTANT: do nothing if path budget denied.
                    // Do NOT call worker.getMoveHelper().setMoveTo(...) here.
                    // That's what causes micro-turn spam / spinning when idle.
                    worker.getNavigator().clearPath();
                }
            }

            worker.setSprinting(false);
            return; // IMPORTANT: skip the rest of updateTask while idle
        }


        // --- 4) Distance to PERSONAL slot ---
        double dxSlot = worker.posX - targetX;
        double dzSlot = worker.posZ - targetZ;
        double distToSlotSq = dxSlot * dxSlot + dzSlot * dzSlot;
        double dySlotAbs = Math.abs(worker.posY - targetY);
        double distToSlot = Math.sqrt(distToSlotSq);

        double dySlot = worker.posY - targetY;
        double distToSlot3D = Math.sqrt(dxSlot * dxSlot + dySlot * dySlot + dzSlot * dzSlot);

        double dyAbs = Math.abs(dySlot);
        double distToSlot2D = distToSlot; // same variable, clearer name

        boolean leaderReallyMoving = targetMoving || followTargetSprinting || leaderUsingTravelAI;

// after distToSlot3D is computed


// "want to move" only if not settled in our slot
        boolean wantToMove =
                !ownerHardIdle                       // if owner isn't hard idle, we may need to move
                        || distToSlot3D > (IDLE_APPROX_STOP_RADIUS + 0.5D);  // if hard idle, only move if not in slot yet

        tickStuckCheck(distToOwner, distToSlot3D, wantToMove);


// --- SPECIAL CASE: following another worker (travel AI) ---
// Prevent rubber-banding: match leader's real movement, only boost when far.
        if (targetIsWorkerLeader) {

            // --- measure leader horizontal motion (blocks/tick) ---
            double lx = followTarget.posX - followTarget.prevPosX;
            double lz = followTarget.posZ - followTarget.prevPosZ;
            double leaderStep = Math.sqrt(lx * lx + lz * lz);

            // consider leader "moving" only if their travel AI is actually advancing
            final double LEADER_MOVE_EPS = 0.010D; // tune 0.007–0.015
            boolean leaderMoving = leaderStep > LEADER_MOVE_EPS;

            // --- compute leader forward direction (motion if available, else yaw fallback) ---
            double fwdX, fwdZ;
            if (leaderMoving) {
                fwdX = lx / leaderStep;
                fwdZ = lz / leaderStep;
            } else {
                float yaw = followTarget.renderYawOffset;
                double yawRad = Math.toRadians(yaw);
                fwdX = -Math.sin(yawRad);
                fwdZ = Math.cos(yawRad);
            }

            // --- if leader is stopped and we're in formation, stop too ---
            // (THIS is the only time we clearPath in the worker-leader mode)
            final double FORMATION_STOP_RADIUS = 2.6D; // tune 2.2–3.2
            if (!leaderMoving && distToSlot3D <= FORMATION_STOP_RADIUS) {
                worker.getNavigator().clearPath();
                hasCachedGoal = false;
                worker.setSprinting(false);
                smoothedMoveSpeed = 0.0D;
                return;
            }

            // --- lookahead target to avoid "micro stop-go"
            // Aim slightly ahead of the slot in the leader direction.
            // This keeps the follower flowing with the leader instead of constantly "arriving".
            double lookAhead = 1.8D; // tune 1.0–2.5
            double tx = targetX + fwdX * lookAhead;
            double tz = targetZ + fwdZ * lookAhead;
            double ty = targetY;

            // --- speed: match leader pace, only boost when behind ---
            // Convert leaderStep (blocks/tick) to a nav-speed-ish value.
            // This is your main tuning knob.
            double leaderSpeedNav = leaderStep * 10.4D;  // tune 10.0–14.0

            // base: match leader
            double moveSpeed = MathHelper.clamp(leaderSpeedNav, 0.18D, 0.75D);
            if (targetIsPlayer && ownerRawMoving) {
                double owner2D = Math.sqrt(mvLenSq);           // blocks/tick
                double minNav = MathHelper.clamp(owner2D * 12.0D, 0.25D, 0.85D); // tune multiplier
                moveSpeed = Math.max(moveSpeed, minNav);
            }
            // catchup only when behind (distance to slot, not distance to owner)
            double catchup = 0.0D;
            if (distToSlot3D > 6.0D) catchup += 0.10D;
            if (distToSlot3D > 12.0D) catchup += 0.15D;
            if (distToSlot3D > 24.0D) catchup += 0.20D;

            // --- FLOW: while owner is moving, don't "arrive and stop".
// Instead, slow down when close to slot so we glide with the owner.
            if (ownerRawMoving) {
                final double SLOW_RADIUS = 3.5D; // blocks: start slowing inside this radius
                if (distToSlot3D < SLOW_RADIUS) {
                    double t = distToSlot3D / SLOW_RADIUS; // 0..1
                    double slowFactor = 0.35D + 0.65D * t; // 0.35..1.0
                    moveSpeed *= slowFactor;
                }
            }


            moveSpeed = Math.min(moveSpeed + catchup, 0.95D);


            // smoothing: bigger alpha = less lag, but too big can oscillate.
            double alpha = 0.30D; // tune 0.22–0.40
            smoothedMoveSpeed = smoothedMoveSpeed + (moveSpeed - smoothedMoveSpeed) * alpha;

            worker.setSprinting(false);

            // repath less often, but don't "stop"; nav continues between repaths
            cachedGoalX = tx;
            cachedGoalY = ty;
            cachedGoalZ = tz;
            hasCachedGoal = true;

            worker.getNavigator().tryMoveToXYZ(cachedGoalX, cachedGoalY, cachedGoalZ, smoothedMoveSpeed);
            return;

        }


        final double SLOT_DEADZONE =
                targetIsPlayer ? 0.6D
                        : (targetIsWorkerLeader ? 2.4D : 1.2D);

        final double SLOT_DEADZONE_2D = targetIsPlayer ? 0.6D : (targetIsWorkerLeader ? 2.4D : 1.2D);
        final double SLOT_Y_TOL = 3.4D; // 10 block + wiggle

        // --- NEW: if owner is idle, only stop when we reached OUR slot (not "within 8 blocks") ---

// use your existing SLOT_DEADZONE, but compare using 2D + small Y tolerance
        if (ownerHardIdle && distToSlotSq < IDLE_APPROX_STOP_RADIUS_SQ && dyAbs < SLOT_Y_TOL && stopMoveDelayTicks <= 0) {
            worker.getNavigator().clearPath();
            hasCachedGoal = false;
            worker.setSprinting(false);

            this.stuckCheckTicks = 0;
            this.stuckForTicks = 0;
            this.lastPosX = worker.posX;
            this.lastPosZ = worker.posZ;
            return;
        }


// Only hard-stop when the owner is truly idle.
// If owner is moving, never clearPath here (prevents micro stop-go).
        if (!ownerMovingForMe && distToSlot2D < SLOT_DEADZONE_2D && dySlotAbs < SLOT_Y_TOL) {
            worker.getNavigator().clearPath();
            hasCachedGoal = false;
            worker.setSprinting(false);
            return;
        }


// Once the owner is idle, and this follower is very close to its personal slot,


        // --- 5) Compute desired speed from owner state + slot distance ---

// --- 5) Compute desired speed from owner state + slot distance ---
        boolean sprint = false;

// Base: match target state
        double stateFactor;
        if (followTargetSprinting) stateFactor = 1.20D;
        else if (followTargetSneaking) stateFactor = 0.60D;
        else stateFactor = 1.10D;

// Only dampen stateFactor for *worker-leader*, not for player
        if (targetIsWorkerLeader) {
            stateFactor = 1.05D; // mild; don't slow players
        }

// Catch-up based on distance to slot
        double catchupFactor;
        if (distToSlot3D > 128.0D) catchupFactor = 1.45D;
        else if (distToSlot3D > 64.0D) catchupFactor = 1.20D;
        else if (distToSlot3D > 32.0D) catchupFactor = 1.15D;
        else if (distToSlot3D > 16.0D) catchupFactor = 1.10D;
        else if (distToSlot3D > 8.0D) catchupFactor = 1.07D;
        else if (distToSlot3D > 4.0D) catchupFactor = 1.05D;
        else catchupFactor = 1.0D;


// Player-only speed scaling from real motion
        double ownerBasedFactor = 1.0D;
        if (targetIsPlayer) {
            // smaller multiplier + hard cap so it can't explode
            ownerBasedFactor = 1.0D + ownerSpeed * 2.0D;
            ownerBasedFactor = MathHelper.clamp(ownerBasedFactor, 1.0D, 1.20D);
        }

// Stable leader boost ONLY for worker leaders
        double leaderFactor = 1.0D;
        if (targetIsWorkerLeader) {
            leaderFactor = 1.35D;          // tune 1.2–1.6
            if (distToSlot3D > 12.0D) leaderFactor *= 1.10D;
            if (distToSlot3D > 24.0D) leaderFactor *= 1.15D;
        }

        double moveSpeed = this.speed * stateFactor * catchupFactor * ownerBasedFactor * leaderFactor;
        if (leaderUsingTravelAI) {
            // Just be slower when leader is travelling
            moveSpeed *= 0.75D;           // tune: 0.65–0.90
            moveSpeed = Math.min(moveSpeed, 0.55D); // hard cap for travel-walk convoy
        }

// Clamp BEFORE using it
        double maxFollowSpeed = 3.0D;

// allow more when very far from owner
        if (distToOwner > 64.0D) maxFollowSpeed = 4.0D;
        if (distToOwner > 128.0D) maxFollowSpeed = 5.0D;

        if (targetIsWorkerLeader) maxFollowSpeed = 1.10D; // tune 0.85–1.3

        moveSpeed = Math.max(0.08D, Math.min(moveSpeed, maxFollowSpeed));
        final double MAX_VISUAL_NAV_SPEED = 1.15D; // tune 1.05–1.25
        moveSpeed = Math.min(moveSpeed, MAX_VISUAL_NAV_SPEED);
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

            double dy = followTarget.posY - worker.posY;
            targetY = worker.posY + MathHelper.clamp(dy, -3.0D, 3.0D);
        }

        BlockPos slotFeet = findSafeStandPos(targetX, followTarget.posY, targetZ);
        targetX = slotFeet.getX() + 0.5D;
        targetY = slotFeet.getY();
        targetZ = slotFeet.getZ() + 0.5D;

        // --- smooth the goal so it doesn't jerk around on sharp turns ---
        if (!hasSmoothGoal) {
            smoothGoalX = targetX;
            smoothGoalY = targetY;
            smoothGoalZ = targetZ;
            hasSmoothGoal = true;
        } else {
            double dxg = targetX - smoothGoalX;
            double dzg = targetZ - smoothGoalZ;
            double d2 = dxg * dxg + dzg * dzg;

            if (d2 > (MAX_GOAL_STEP_PER_TICK * MAX_GOAL_STEP_PER_TICK)) {
                double d = Math.sqrt(d2);
                dxg /= d;
                dzg /= d;
                smoothGoalX += dxg * MAX_GOAL_STEP_PER_TICK;
                smoothGoalZ += dzg * MAX_GOAL_STEP_PER_TICK;
            } else {
                smoothGoalX = targetX;
                smoothGoalZ = targetZ;
            }

            // Y can snap (stairs/cliffs), but you can soften if you want:
            smoothGoalY = targetY;
        }

// Use smoothed goal for everything below (pathing + goal comparisons)
        targetX = smoothGoalX;
        targetY = smoothGoalY;
        targetZ = smoothGoalZ;


        worker.setSprinting(sprint);
// --------------------------------------------------------
// PATH REUSE + FAIR SCHEDULER + CHEAP FALLBACK
// --------------------------------------------------------
        double goalDx = targetX - cachedGoalX;
        double goalDz = targetZ - cachedGoalZ;
        double goalChangeSq = goalDx * goalDx + goalDz * goalDz;

// If we're already walking a path and the goal hasn't meaningfully changed,
// do not ask the pathfinder again.
        if (hasCachedGoal && !worker.getNavigator().noPath() && goalChangeSq < GOAL_CHANGE_EPS_SQ) {
            return;
        }

// If we are already close to the intended goal, don't repath either.
        double toGoalDx = worker.posX - targetX;
        double toGoalDz = worker.posZ - targetZ;
        double distToGoalSq = toGoalDx * toGoalDx + toGoalDz * toGoalDz;

        if (!worker.getNavigator().noPath() && distToGoalSq < MIN_REPATH_DIST_SQ) {
            return;
        }

// Decide urgency (who deserves pathfinding first)
// Decide urgency
        int priority = 0;
        if (worker.getNavigator().noPath()) priority = 1;
        if (distToOwner > 48.0D) priority = Math.max(priority, 1);
        if (stuckForTicks > 40) priority = 2;

// ONE request only
        boolean ok = requestPathTo(targetX, targetY, targetZ, smoothedMoveSpeed, priority, groupSize);

        if (ok) {
            int baseRepath = (distToOwner > 64.0D) ? 6 : 10;
            int extra = Math.min(25, groupSize / 3);
            timeToRecalcPath = baseRepath + extra;
            if (targetIsWorkerLeader) timeToRecalcPath = Math.min(timeToRecalcPath, 12);
        } else {
            worker.getMoveHelper().setMoveTo(targetX, targetY, targetZ, smoothedMoveSpeed);
            waitingForPathTicks = 10;
            timeToRecalcPath = 2;
        }


    }

    private void initMovingSlotIfNeeded() {
        if (slotInit) return;

        int hash = worker.getUniqueID().hashCode();

        // If you want "mostly side-running", force left/right here:
        slotRole = ((hash & 1) == 0) ? 1 : 2;

        // Otherwise keep your old distribution:
        slotRSlot = (hash >>> 1) & 7;      // 0..7

        int angleSlot = (hash >>> 4) & 15; // 0..15
        slotBaseOffset = (angleSlot - 7.5D) * (Math.PI / 20.0D);

        slotInit = true;
    }

    private boolean hasValidCombatTarget() {
        EntityLivingBase atk = worker.getAttackTarget();
        if (atk != null && !atk.isDead) return true;

        EntityLivingBase rev = worker.getRevengeTarget();
        return rev != null && !rev.isDead;
    }

    /**
     * Check if we're stuck (barely moving) while still far from the followTarget.
     * If so, after some time, teleport 8 blocks toward the followTarget on surface.
     */
    private void tickStuckCheck(double distToOwner, double distToSlot3D, boolean wantToMove) {
        if (stuckTpCooldownTicks > 0) stuckTpCooldownTicks--;
        if (!wantToMove) {
            stuckForTicks = 0;
            stuckCheckTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            noProgressTicksProg = 0;
            lastDistToOwnerProg = Double.NaN;

            return;
        }
        // If we're intentionally waiting for path budget, don't accumulate stuck time.
        if (waitingForPathTicks > 0) {
            stuckForTicks = 0;
            stuckCheckTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            noProgressTicksProg = 0;
            lastDistToOwnerProg = Double.NaN;
            return;
        }


        if (worker.isRiding() && worker.getRidingEntity() instanceof EntityBoat) {
            stuckForTicks = 0;
            stuckCheckTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            noProgressTicksProg = 0;
            lastDistToOwnerProg = Double.NaN;

            return;
        }

        // Only care when we are clearly not "already with" the followTarget
        if (distToSlot3D <= (double) (stopDist * 2.0F) || followTarget == null) {
            stuckForTicks = 0;
            stuckCheckTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            noProgressTicksProg = 0;
            lastDistToOwnerProg = Double.NaN;

            return;
        }
// -------------------------
// PROGRESS-based stuck: no progress toward owner (hole rescue)
// -------------------------
        if (followTarget != null) {
            // Only TP if far
            if (distToOwner * distToOwner > STUCK_TP_MIN_DIST_SQ) {

                boolean inLOS = ownerHasLineOfSight(followTarget);
                int threshold = inLOS ? NO_PROGRESS_TP_TICKS_IN_LOS : NO_PROGRESS_TP_TICKS_NO_LOS;

                if (Double.isNaN(lastDistToOwnerProg)) {
                    lastDistToOwnerProg = distToOwner;
                    noProgressTicksProg = 0;
                } else {
                    if (distToOwner > lastDistToOwnerProg - PROGRESS_EPS) noProgressTicksProg++;
                    else noProgressTicksProg = 0;

                    lastDistToOwnerProg = distToOwner;
                }

                if (stuckTpCooldownTicks <= 0 && noProgressTicksProg >= threshold) {
                    noProgressTicksProg = 0;
                    stuckTpCooldownTicks = STUCK_TP_COOLDOWN;

                    double dirX = followTarget.posX - worker.posX;
                    double dirZ = followTarget.posZ - worker.posZ;
                    double lenSq = dirX * dirX + dirZ * dirZ;
                    if (lenSq > 1.0e-6) {
                        double len = Math.sqrt(lenSq);
                        dirX /= len;
                        dirZ /= len;

                        double step = Math.min(STUCK_TP_STEP, Math.max(0.0D, distToOwner - 2.0D));
                        if (step > 0.0D) {
                            double tpX = worker.posX + dirX * step;
                            double tpZ = worker.posZ + dirZ * step;

                            boolean ownerUnderground = isOwnerUnderground(followTarget);
                            BlockPos ground = findSafeStandPos(tpX, followTarget.posY, tpZ, !ownerUnderground);

                            worker.setLocationAndAngles(
                                    ground.getX() + 0.5D,
                                    ground.getY(),
                                    ground.getZ() + 0.5D,
                                    worker.rotationYaw,
                                    worker.rotationPitch
                            );

                            worker.getNavigator().clearPath();
                            hasCachedGoal = false;
                            waitingForPathTicks = 0;
                            worker.setSprinting(false);

                            stuckForTicks = 0;
                            stuckCheckTicks = 0;
                            lastPosX = worker.posX;
                            lastPosZ = worker.posZ;
                            return;
                        }
                    }
                }
            } else {
                // too close: don't accumulate
                noProgressTicksProg = 0;
                lastDistToOwnerProg = Double.NaN;
            }
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


        if (distToOwner * distToOwner <= STUCK_TP_MIN_DIST_SQ) {
            return;
        }

        if (stuckForTicks < STUCK_TP_THRESHOLD) {
            return;
        }

        // --- STUCK: teleport 8 blocks toward followTarget on surface ---

        stuckForTicks = 0;

        double gx = hasCachedGoal ? cachedGoalX : followTarget.posX;
        double gz = hasCachedGoal ? cachedGoalZ : followTarget.posZ;


        double dirX = gx - worker.posX;
        double dirZ = gz - worker.posZ;

        double distToGoal = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (distToGoal < 2.0D) return;

        dirX /= distToGoal;
        dirZ /= distToGoal;

        double step = Math.min(8.0D, distToGoal - 2.0D);
        if (step <= 0.0D) return;


        double tpX = worker.posX + dirX * step;
        double tpZ = worker.posZ + dirZ * step;

        boolean ownerUnderground = isOwnerUnderground(followTarget);
        BlockPos ground = findSafeStandPos(tpX, followTarget.posY, tpZ, !ownerUnderground);


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
     * * Try to board owner's boat if it has a free seat.
     * * Otherwise, share boats with other workers.
     * * As a last resort, spawn our own boat.
     * - If owner is not in a boat but we are, try to converge & dismount.
     * <p>
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
                updateBoatConvoyFollow();  // <-- THE obvious one
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
            stuckForTicks = 0;
            lastPosX = worker.posX;
            lastPosZ = worker.posZ;
            return true;
        }

// B2: worker already in a DIFFERENT boat → use the main convoy steering
        if (workerInBoat) {
            updateBoatConvoyFollow();  // <-- THE obvious one
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
                BlockPos bp = new BlockPos(ownerBoat.posX, ownerBoat.posY, ownerBoat.posZ);
                if (!worker.world.isBlockLoaded(bp)) {
                    // don’t force chunk loads here; just wait
                    return false;
                }
                worker.startRiding(ownerBoat, true);
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
                stuckCheckTicks = 0;
                stuckForTicks = 0;
                boatNotMovingTicks = 0;
                boatBeachedTicks = 0;

                lastPosX = worker.posX;
                lastPosZ = worker.posZ;
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
                BlockPos bp = new BlockPos(ownerBoat.posX, ownerBoat.posY, ownerBoat.posZ);
                if (!worker.world.isBlockLoaded(bp)) {
                    // don’t force chunk loads here; just wait
                    return false;
                }
                worker.startRiding(sharedBoat, true);
                worker.getNavigator().clearPath();
                worker.setSprinting(false);
                boatNotMovingTicks = 0;
                boatBeachedTicks = 0;

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
        BlockPos bp = new BlockPos(boat.posX, boat.posY, boat.posZ);
        if (!worker.world.isBlockLoaded(bp)) {
            // don’t force chunk loads here; just wait
            return false;
        }
        worker.startRiding(boat, true);
        worker.getNavigator().clearPath();
        worker.setSprinting(false);
        boatNotMovingTicks = 0;
        boatBeachedTicks = 0;


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

        IBlockState feet = w.getBlockState(pos);
        IBlockState below = w.getBlockState(pos.down());
        return isVanillaWater(feet) || isVanillaWater(below);
    }

    private void updateBoatConvoyFollow() {
        if (!(worker.getRidingEntity() instanceof EntityBoat)) {
            return;
        }
        EntityBoat boat = (EntityBoat) worker.getRidingEntity();

        if (boat.getControllingPassenger() != worker) {
            worker.getNavigator().clearPath();
            worker.setSprinting(false);
            return;
        }

// NEW: if this is NOT a TravellerBoat, we never steer/kill it.
// If owner left the boat, follower should simply dismount.
        if (!(boat instanceof EntityTravellerBoat)) {
            EntityLivingBase owner = this.followTarget;

            boolean ownerStillOnSameBoat =
                    owner != null && owner.isRiding() && owner.getRidingEntity() == boat;

            if (!ownerStillOnSameBoat) {
                // Just get off (no killing).
                // Prefer shore if beached, otherwise surface water.
                if (!isBoatOnWater(boat)) {
                    tryDismountAtShore(boat, false); // this won't kill now because it's not TravellerBoat
                } else {
                    dismountIntoWaterSurface(boat);  // also won't kill if you add the same guard there
                }
            }
            return;
        }

        EntityLivingBase owner = this.followTarget;
        if (owner == null || owner.isDead) {
            return; // fallback: you could also tryDismountAtShore(boat, false) here
        }

        double distToOwnerSq = boat.getDistanceSq(owner);
        boolean ownerInBoat = owner.isRiding() && owner.getRidingEntity() instanceof EntityBoat;
        boolean ownerOnLand = !ownerInBoat && !owner.isInWater();
        boolean onWater = isBoatOnWater(boat);
        boolean shoreAhead = isShoreAhead(boat);
        double boatSpeedSq = boat.motionX * boat.motionX + boat.motionZ * boat.motionZ;

        // Track if our boat is not moving
        tickBoatStill(boat);

// If owner is NOT in a boat anymore, and our boat stopped/beached,
// then dismount even if owner is far away.
        if (!ownerInBoat) {
            boolean ownerNotNear = distToOwnerSq > OWNER_NOT_NEAR_SQ;

            boolean beached = !isBoatOnWater(boat);
            boolean blocked = boat.collidedHorizontally;

            if (ownerNotNear && (beached || blocked || boatStillTicks >= BOAT_STILL_FORCE_TICKS)) {

                // IMPORTANT: dismount using BOAT shore, not owner shore
                if (tryDismountAtShore(boat, false)) return;
                if (dismountIntoWaterSurface(boat)) return; // fallback for open water

                // can't dismount yet: stop grinding forever
                boat.motionX = 0;
                boat.motionZ = 0;
                worker.getNavigator().clearPath();
                return;
            }
        }


// If owner is NOT in a boat, our job is to reach land and get off.
// Dismount as soon as we hit shoreline / get blocked, regardless of distance to owner.
        if (ownerOnLand) {
            boolean blocked = boat.collidedHorizontally;
            boolean beached = !onWater; // boat's current column not water

            // Also dismount if we are basically stopped and the next block isn't water (classic "grinding shoreline" case)
            boolean stuckAtEdge = shoreAhead && boatSpeedSq < 0.01D;

            if (blocked || beached || shoreAhead || stuckAtEdge) {
                if (tryDismountAtShore(boat, true)) {
                    return; // now on land, normal follow takes over next tick
                } else {
                    // If we can't find an exit yet, STOP pushing into shore so we don't perma-grind.
                    boat.motionX = 0;
                    boat.motionZ = 0;
                    worker.getNavigator().clearPath();
                    return;
                }
            }
        }


        // --- 1) We are close to owner AND owner is on land → try to dismount onto shore near owner ---
        if (ownerOnLand && distToOwnerSq < 12.0D * 12.0D) {
            if (tryDismountAtShore(boat, true)) {
                return; // now on land; normal follow AI will take over next tick
            }
        }
// --- 1b) Owner dismounted into WATER (ocean/river) -> dismount onto water surface, not seabed ---
        if (!ownerInBoat && owner.isInWater() && distToOwnerSq < 18.0D * 18.0D) {
            if (dismountIntoWaterSurface(boat)) {
                return;
            }
        }
        // --- 2) We are very close to solid ground in front while owner is not boating much ---
        // This is optional extra safeguard: if desired, you can also check for "boatHitShore"
        // and call tryDismountAtShore(boat, false) in that case.

        // --- 3) Otherwise, steer convoy as usual (column behaviour etc.) ---

        // Example minimal convoy steering (adjust to your existing code):

        // get owner's boat position if they are in one, otherwise owner position
        // --- choose target position (owner boat if boating, else owner) ---
        EntityBoat ownerBoat = ownerInBoat ? (EntityBoat) owner.getRidingEntity() : null;

        Vec3d targetPos = ownerInBoat
                ? new Vec3d(ownerBoat.posX, ownerBoat.posY, ownerBoat.posZ)
                : new Vec3d(owner.posX, owner.posY, owner.posZ);

        Vec3d myPos = new Vec3d(boat.posX, boat.posY, boat.posZ);

// --- IMPORTANT: compute OWNER forward direction (not "to owner") ---
        Vec3d ownerVel = ownerInBoat
                ? new Vec3d(ownerBoat.motionX, 0, ownerBoat.motionZ)
                : new Vec3d(owner.motionX, 0, owner.motionZ);

        boolean ownerBoatMoving = ownerVel.lengthSquared() > BOAT_MOVE_EPS_SQ;

        if (ownerBoatMoving) {
            Vec3d d = ownerVel.normalize();
            lastOwnerBoatDirX = d.x;
            lastOwnerBoatDirZ = d.z;
            hasOwnerBoatDir = true;
        } else if (!hasOwnerBoatDir) {
            // fallback: use yaw if we don't have motion direction yet
            float yaw = ownerInBoat ? ownerBoat.rotationYaw : owner.rotationYaw;
            double yawRad = Math.toRadians(yaw);
            lastOwnerBoatDirX = -Math.sin(yawRad);
            lastOwnerBoatDirZ = Math.cos(yawRad);
            hasOwnerBoatDir = true;
        }

        Vec3d ownerForward = new Vec3d(lastOwnerBoatDirX, 0, lastOwnerBoatDirZ);
        Vec3d right = new Vec3d(-ownerForward.z, 0, ownerForward.x);

// --- follower slot behind the owner (stable even if we pass them) ---
        int hash = worker.getUniqueID().hashCode();
        int columnIndex = (hash >>> 3) & 3;  // 0..3
        double spacing = 3.0D;              // blocks between boats
        double slotDist = (columnIndex + 1) * spacing;

// small lane offset so boats don't overlap
        int lane = (hash & 1) == 0 ? -1 : 1;
        double laneOffset = 0.9D;
        if (ownerOnLand) {
            Vec3d toOwner = new Vec3d(owner.posX - boat.posX, 0, owner.posZ - boat.posZ);
            if (toOwner.lengthSquared() > 1e-4) {
                Vec3d dir = toOwner.normalize();
                // steer toward owner XZ while still in water
                // (shore detection above will dismount as soon as we reach land)
                // You can reuse your accel logic by setting slotPos = owner position:
                // (and let the usual motion/yaw code run)
            }
        }

        Vec3d slotPos;
        if (ownerOnLand) {
            slotPos = new Vec3d(owner.posX, boat.posY, owner.posZ);
        } else {
            slotPos = targetPos
                    .subtract(ownerForward.scale(slotDist))
                    .add(right.scale(lane * laneOffset));
        }

// --- ARRIVE + STOP behavior ---
        Vec3d toSlot = slotPos.subtract(myPos);
        double slotD2 = toSlot.lengthSquared();

        // We only consider "stuck" if we actually need to be moving.
// This prevents dismounting just because owner is idle.
        boolean wantToMove =
                ownerOnLand
                        || (ownerInBoat && ownerBoatMoving)      // owner actively boating
                        || slotD2 > (3.0D * 3.0D);               // we are not in our slot yet
        if (tickBoatStuckAndMaybeDismount(boat, wantToMove, slotD2)) {
            return;
        }

// If owner is stopped AND we're basically in position -> STOP (prevents circling)
        if (!ownerBoatMoving && slotD2 < (1.2D * 1.2D)) {
            boat.motionX = 0.0D;
            boat.motionZ = 0.0D;
            worker.getNavigator().clearPath();
            return;
        }

// If owner is stopped, brake hard so we settle instead of orbiting
        if (!ownerBoatMoving) {
            boat.motionX *= 0.80D;
            boat.motionZ *= 0.80D;
        }

// --- Move toward slot with accel + speed cap ---
        if (slotD2 < 0.25D) {
            // close: gentle brake
            boat.motionX *= TravellersModConfig.followerBoatConvoyBrakeNear; // e.g. 0.6
            boat.motionZ *= TravellersModConfig.followerBoatConvoyBrakeNear;
        } else {
            Vec3d dir = toSlot.normalize();

            double accel = TravellersModConfig.followerBoatConvoyAccel;       // e.g. 0.04
            double maxSpeed = TravellersModConfig.followerBoatConvoyMaxSpeed; // e.g. 0.45

            boat.motionX += dir.x * accel;
            boat.motionZ += dir.z * accel;

            // friction
            boat.motionX *= 0.96D;
            boat.motionZ *= 0.96D;

            // hard cap
            double v2 = boat.motionX * boat.motionX + boat.motionZ * boat.motionZ;
            double maxV2 = maxSpeed * maxSpeed;
            if (v2 > maxV2) {
                double s = maxSpeed / Math.sqrt(v2);
                boat.motionX *= s;
                boat.motionZ *= s;
            }
        }

// rotate boat to face motion
        applyOwnerRepulsion(boat, owner, ownerBoat);
        double v2Now = boat.motionX * boat.motionX + boat.motionZ * boat.motionZ;
        if (v2Now > 1.0E-4D) {
            float yaw = (float) (Math.atan2(boat.motionZ, boat.motionX) * 180.0D / Math.PI - 90.0D);
            boat.rotationYaw = yaw;
            boat.prevRotationYaw = yaw;

            worker.rotationYaw = yaw;
            worker.rotationYawHead = yaw;
            worker.renderYawOffset = yaw;
        }

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
        final int[][] OFF = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
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

        // Must be air for feet + head
        if (!w.isAirBlock(feet)) return false;
        if (!w.isAirBlock(feet.up())) return false;

        // NEW: reject caves under oceans (there is water above them)
        // Require several blocks of open air above the dismount spot.
        for (int i = 1; i <= 6; i++) {
            BlockPos up = feet.up(i);
            if (!w.isBlockLoaded(up)) break;
            if (!w.isAirBlock(up)) return false;
        }

        IBlockState below = w.getBlockState(feet.down());
        if (below.getMaterial().isLiquid()) return false;

        BlockFaceShape shape = below.getBlockFaceShape(w, feet.down(), EnumFacing.UP);
        return shape == BlockFaceShape.SOLID
                || below.getBlock() == Blocks.SNOW_LAYER
                || below.getBlock() == Blocks.GRASS_PATH
                || below.getBlock() == Blocks.ICE
                || below.getBlock() == Blocks.PACKED_ICE;
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
        if (dist2 > 12.0D * 12.0D) {
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
            killBoatEvenIfOccupied(boat, exit, false);
        }
        boatNotMovingTicks = 0;
        boatBeachedTicks = 0;
        worker.getNavigator().clearPath();
        return true;
    }

    private boolean isWaterAt(double x, double y, double z) {
        BlockPos p = new BlockPos(
                net.minecraft.util.math.MathHelper.floor(x),
                net.minecraft.util.math.MathHelper.floor(y),
                net.minecraft.util.math.MathHelper.floor(z)
        );
        if (!worker.world.isBlockLoaded(p)) return true; // don't force dismount on unloaded
        IBlockState s = worker.world.getBlockState(p);
        return isVanillaWater(s);
    }

    private Vec3d getBoatForward(EntityBoat boat) {
        // Prefer motion direction if moving
        double vx = boat.motionX;
        double vz = boat.motionZ;
        double v2 = vx * vx + vz * vz;
        if (v2 > 1.0e-4) {
            double inv = 1.0 / Math.sqrt(v2);
            return new Vec3d(vx * inv, 0, vz * inv);
        }
        // Fallback: yaw
        float yaw = boat.rotationYaw;
        double yawRad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
    }

    private boolean isShoreAhead(EntityBoat boat) {
        Vec3d fwd = getBoatForward(boat);

        // sample a little in front of the bow, at "waterline"
        double sx = boat.posX + fwd.x * 1.25D;
        double sz = boat.posZ + fwd.z * 1.25D;
        double y = boat.posY - 0.2D;

        // If the block at the waterline ahead is NOT water, we’re about to beach / are beached.
        return !isWaterAt(sx, y, sz);
    }

    private void tickBoatStill(EntityBoat boat) {
        Vec3d now = new Vec3d(boat.posX, boat.posY, boat.posZ);
        if (lastBoatPosStill == Vec3d.ZERO) {
            lastBoatPosStill = now;
            boatStillTicks = 0;
            return;
        }

        double movedSq = now.squareDistanceTo(lastBoatPosStill);

        // IMPORTANT: update every tick, otherwise distance accumulates and breaks logic
        lastBoatPosStill = now;

        if (movedSq < BOAT_STILL_EPS_SQ) boatStillTicks++;
        else boatStillTicks = 0;
    }

    private boolean dismountIntoWaterSurface(EntityBoat boat) {
        if (!isBoatOnWater(boat)) return false;

        World w = worker.world;
        BlockPos aroundBoat = new BlockPos(boat.posX, boat.posY, boat.posZ);

        // Find real surface water with air above (prevents cave/underwater weirdness)
        BlockPos surfaceWater = findWaterSurfaceAirAbove(aroundBoat, 3);
        if (surfaceWater == null) {
            // fallback: don't teleport anywhere strange
            return false;
        }

        // Dismount first (important)
        worker.dismountRidingEntity();

        // Place slightly inside the top water block (so they float/swim at surface)
        double x = surfaceWater.getX() + 0.5D;
        double y = surfaceWater.getY() + 0.10D;
        double z = surfaceWater.getZ() + 0.5D;

        worker.setLocationAndAngles(x, y, z, worker.rotationYaw, worker.rotationPitch);

        // Stop weird movement
        worker.motionX = 0;
        worker.motionY = 0;
        worker.motionZ = 0;
        worker.fallDistance = 0;

        worker.getNavigator().clearPath();
        worker.setSprinting(false);

        // only kill follower boats (never vanilla)
        if (boat instanceof EntityTravellerBoat) {
            killBoatEvenIfOccupied(boat, /*exitPos*/ null, /*waterSurface*/ true);
        }
        return true;
    }

    private void applyOwnerRepulsion(EntityBoat myBoat, EntityLivingBase owner, @Nullable EntityBoat ownerBoat) {
        // Repel from the owner's BOAT if they have one, otherwise repel from the owner entity
        Entity ref = (ownerBoat != null) ? ownerBoat : owner;

        double dx = myBoat.posX - ref.posX;
        double dz = myBoat.posZ - ref.posZ;
        double d2 = dx * dx + dz * dz;

        // How close is "too close"
        final double MIN_DIST = 4.0D;          // blocks (tune 2.0–3.2)
        final double MIN_DIST_SQ = MIN_DIST * MIN_DIST;

        if (d2 < MIN_DIST_SQ && d2 > 1.0e-6D) {
            double d = Math.sqrt(d2);

            // Strength grows as we get closer
            double overlap = (MIN_DIST - d) / MIN_DIST;  // 0..1
            double push = overlap * 0.30D;               // tune 0.10–0.30

            // Push away
            myBoat.motionX += (dx / d) * push;
            myBoat.motionZ += (dz / d) * push;

            // Also brake a bit so we don't keep ramming
            myBoat.motionX *= 0.90D;
            myBoat.motionZ *= 0.90D;
        }
    }

    private boolean tickBoatStuckAndMaybeDismount(EntityBoat boat, boolean wantToMove, double slotD2) {
        // 1) beached check (not on water)
        boolean onWater = isBoatOnWater(boat);
        if (!onWater) {
            boatBeachedTicks++;
            if (boatBeachedTicks >= BOAT_BEACHED_TICKS) {
                if (tryDismountAtShore(boat, false)) return true;
                if (dismountIntoWaterSurface(boat)) return true; // fallback for open water
            }
        } else {
            boatBeachedTicks = 0;
        }

        // 2) per-tick "not moving" check (FIXED: update last pos EVERY tick)
        Vec3d now = new Vec3d(boat.posX, boat.posY, boat.posZ);
        if (lastBoatPosStuck == Vec3d.ZERO) lastBoatPosStuck = now;

        double movedSq = now.squareDistanceTo(lastBoatPosStuck);
        lastBoatPosStuck = now;

        if (wantToMove && movedSq < BOAT_STUCK_MOVE_EPS_SQ) {
            boatNotMovingTicks++;
        } else {
            boatNotMovingTicks = 0;
        }

        if (boatNotMovingTicks >= BOAT_STUCK_TICKS) {
            boatNotMovingTicks = 0;
            if (tryDismountAtShore(boat, false)) return true;
            if (dismountIntoWaterSurface(boat)) return true; // fallback for open water
        }

        // 3) REAL WORLD SHORE-STUCK: collidedHorizontally while we still need to move
        // Only count this if we are NOT already "in position" near slot.
        boolean shouldCare = wantToMove && slotD2 > (3.0D * 3.0D);

        if (shouldCare && boat.collidedHorizontally) {
            boatCollideTicks++;
            // after ~1s of grinding, bail out
            if (boatCollideTicks >= 20) {
                boatCollideTicks = 0;

                if (tryDismountAtShore(boat, false)) return true;

                // If no shore found, stop pushing (prevents infinite grinding)
                boat.motionX = 0;
                boat.motionZ = 0;
                worker.getNavigator().clearPath();
                return false;
            }
        } else {
            boatCollideTicks = 0;
        }

        return false;
    }

    private void killBoatEvenIfOccupied(EntityBoat boat, @Nullable BlockPos exitPos, boolean waterSurface) {
        if (boat == null || boat.isDead) return;
        if (!(boat instanceof EntityTravellerBoat)) return;

        if (boat.world.isRemote) return; // server only

        // Copy list because it changes when dismounting
        java.util.List<Entity> riders = new java.util.ArrayList<>(boat.getPassengers());

        // Dismount everyone from the boat
        boat.removePassengers(); // dismounts all passengers (1.12)

        // Optional: reposition the OTHER passengers (not the worker who already snapped)
        for (Entity e : riders) {
            if (e == null || e.isDead) continue;
            if (e == worker) continue;

            if (exitPos != null && !waterSurface) {
                e.setLocationAndAngles(
                        exitPos.getX() + 0.5D,
                        exitPos.getY(),
                        exitPos.getZ() + 0.5D,
                        e.rotationYaw,
                        e.rotationPitch
                );
            } else if (waterSurface) {
                // Drop them at the boat surface
                e.setLocationAndAngles(
                        boat.posX,
                        boat.posY + 0.35D,
                        boat.posZ,
                        e.rotationYaw,
                        e.rotationPitch
                );
            }
        }

        // Finally kill the boat
        boat.setDead();
    }

    @Nullable
    private BlockPos findWaterSurfaceAirAbove(BlockPos start, int radiusXZ) {
        World w = worker.world;
        int sx = start.getX();
        int sz = start.getZ();

        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
            for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                int x = sx + dx;
                int z = sz + dz;

                // scan downward from world top to find first "water with air above"
                for (int y = w.getActualHeight() - 2; y > 2; y--) {
                    BlockPos water = new BlockPos(x, y, z);
                    if (!w.isBlockLoaded(water)) break;

                    IBlockState s = w.getBlockState(water);
                    if (isVanillaWater(s) && w.isAirBlock(water.up())) {
                        double d2 = water.distanceSq(start);
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            best = water; // this is the TOP water block (surface) with air above
                        }
                        break; // stop scanning this column once we found surface
                    }
                }
            }
        }
        return best;
    }

    private void offscreenHopNearOwner() {
        EntityLivingBase owner = this.followTarget;
        if (owner == null) return;

        // If owner is riding a boat and it's YOUR boat type, prefer mounting (keeps convoy tight)
        if (owner.isRiding() && owner.getRidingEntity() instanceof EntityBoat) {
            EntityBoat ob = (EntityBoat) owner.getRidingEntity();

            // If there is room, just ride with them (best for "follow leader tps")
            if (ob instanceof EntityTravellerBoat && ob.getPassengers().size() < BOAT_MAX_PASSENGERS) {
                worker.startRiding(ob, true);
                worker.getNavigator().clearPath();
                hasCachedGoal = false;
                return;
            }

            // Otherwise just hop near the boat (land or water-safe)
            BlockPos feet = findSafeStandPos(ob.posX, owner.posY, ob.posZ);
            worker.setLocationAndAngles(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D,
                    worker.rotationYaw, worker.rotationPitch);
            worker.getNavigator().clearPath();
            hasCachedGoal = false;
            return;
        }

        // Normal case: hop near owner (NOT exactly on them -> prevents stacking)
        // Keep it deterministic per-worker so they don't scatter.
        int hash = worker.getUniqueID().hashCode();

        // Use owner's body yaw for a stable "behind" direction offscreen
        float yaw = owner.renderYawOffset;
        double yawRad = Math.toRadians(yaw);

        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        double backX = -fwdX;
        double backZ = -fwdZ;

        // small ring behind the owner
        double baseR = 2.2D + ((hash >>> 1) & 3) * 0.6D; // 2.2..4.0
        double rightX = -fwdZ;
        double rightZ = fwdX;

        // lane left/right
        double lane = ((hash & 1) == 0) ? -1.0D : 1.0D;
        double laneOff = 0.9D;

        double tx = owner.posX + backX * baseR + rightX * lane * laneOff;
        double tz = owner.posZ + backZ * baseR + rightZ * lane * laneOff;

        boolean ownerUnderground = isOwnerUnderground(owner);
        BlockPos feet = findSafeStandPos(tx, owner.posY, tz, !ownerUnderground);

        worker.setLocationAndAngles(
                feet.getX() + 0.5D,
                feet.getY(),
                feet.getZ() + 0.5D,
                worker.rotationYaw,
                worker.rotationPitch
        );

        worker.getNavigator().clearPath();
        hasCachedGoal = false;
    }
// --- Tight space detection: switch to "single-file" follow in 1-wide tunnels/caves ---

    private boolean isSolidBlock(World w, BlockPos p) {
        IBlockState s = w.getBlockState(p);
        // "solid enough" for corridor walls
        return s.getCollisionBoundingBox(w, p) != null && !s.getMaterial().isReplaceable();
    }

    private boolean isTightUnderground(EntityLivingBase e) {
        World w = worker.world;

        // Feet position (same as entity standing block)
        BlockPos feet = new BlockPos(e.posX, e.posY, e.posZ);

        if (canSeeSkyIgnoringFoliage(feet.up())) return false;

        // If we can see the sky, it's not an underground tunnel/cave case
        if (w.canSeeSky(feet.up())) return false;

        // Require 2 blocks of headroom where the owner is (typical tunnel height)
        if (!w.isAirBlock(feet) || !w.isAirBlock(feet.up())) return false;

        // Count "wall-like" sides around owner (both at feet and head)
        int solidSides = 0;
        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos sf = feet.offset(f);
            if (!w.isBlockLoaded(sf) || !w.isBlockLoaded(sf.up())) continue;

            if (isSolidBlock(w, sf) && isSolidBlock(w, sf.up())) {
                solidSides++;
            }
        }

        // In a 1-block wide corridor, usually 2+ sides are solid (left+right)
        return solidSides >= 2;
    }

    private boolean tryMoveToReachableSlot(double tx, double tyHint, double tz, double speed) {
        BlockPos p = findSafeStandPos(tx, tyHint, tz);
        double gx = p.getX() + 0.5D;
        double gy = p.getY();
        double gz = p.getZ() + 0.5D;

        if (worker.getNavigator().tryMoveToXYZ(gx, gy, gz, speed)) {
            cachedGoalX = gx;
            cachedGoalY = gy;
            cachedGoalZ = gz;
            hasCachedGoal = true;
            return true;
        }

        // 4-neighborhood only (cheap)
        final int[][] OFF4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : OFF4) {
            BlockPos q = findSafeStandPos(tx + d[0], tyHint, tz + d[1]);
            double ax = q.getX() + 0.5D;
            double ay = q.getY();
            double az = q.getZ() + 0.5D;

            if (worker.getNavigator().tryMoveToXYZ(ax, ay, az, speed)) {
                cachedGoalX = ax;
                cachedGoalY = ay;
                cachedGoalZ = az;
                hasCachedGoal = true;
                return true;
            }
        }

        return false;
    }

    private BlockPos findSafeStandPos(double x, double yHint, double z) {
        return findSafeStandPos(x, yHint, z, true); // gilles behavior: allow surface fallback
    }

    private BlockPos findSafeStandPos(double x, double yHint, double z, boolean allowSurfaceFallback) {
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

    private boolean isOwnerUnderground(EntityLivingBase owner) {
        if (owner == null) return false;

        // use feet-ish pos; using bounding box minY avoids weirdness on slabs
        BlockPos p = new BlockPos(owner.posX, owner.getEntityBoundingBox().minY, owner.posZ);

        // "Underground" = NOT open to sky once you ignore foliage
        return !canSeeSkyIgnoringFoliage(p.up());
    }

    @Nullable
    private BlockPos findStandableNearY(double x, double yHint, double z, int vertRange, int horizRange) {
        World w = worker.world;
        int xi = MathHelper.floor(x);
        int zi = MathHelper.floor(z);

        int y0 = MathHelper.clamp(MathHelper.floor(yHint), 2, w.getActualHeight() - 3);

        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (int dx = -horizRange; dx <= horizRange; dx++) {
            for (int dz = -horizRange; dz <= horizRange; dz++) {
                for (int dy = -vertRange; dy <= vertRange; dy++) {
                    BlockPos feet = new BlockPos(xi + dx, y0 + dy, zi + dz);
                    if (!w.isBlockLoaded(feet)) continue;

                    if (isStandable(feet)) {
                        // Prefer closest to requested X/Z
                        double d2 = feet.distanceSq(x, feet.getY(), z);
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            best = feet;
                        }
                    }
                }
            }
        }
        return best;
    }

    @Nullable
    private BlockPos getUndergroundFollowStandPos(EntityLivingBase owner) {
        if (owner == null) return null;

        // deterministic per-worker angle so they spread out
        int hash = worker.getUniqueID().hashCode();
        double angle = ((hash & 0xFFFF) / (double) 0xFFFF) * (Math.PI * 2.0);

        // target point around the owner, ~3 blocks away
        double tx = owner.posX + Math.cos(angle) * UNDERGROUND_FOLLOW_RADIUS;
        double tz = owner.posZ + Math.sin(angle) * UNDERGROUND_FOLLOW_RADIUS;
        double ty = owner.posY;

        // Find a real standable spot near owner's Y (NO surface)
        BlockPos stand = findStandableNearY(tx, ty, tz, 5, 2);
        if (stand != null) return stand;

        // fallback: try a few radii if that specific angle is blocked by tunnel walls
        for (double r = 1.5D; r <= 4.5D; r += 1.0D) {
            double fx = owner.posX + Math.cos(angle) * r;
            double fz = owner.posZ + Math.sin(angle) * r;
            stand = findStandableNearY(fx, ty, fz, 6, 3);
            if (stand != null) return stand;
        }

        // last fallback: just find *any* standable near the owner at their Y
        return findStandableNearY(owner.posX, owner.posY, owner.posZ, 7, 3);
    }

    private boolean isCliffEdge(BlockPos feet) {
        World w = worker.world;

        // Look at the blocks next to the support block. If any side drops 2+ blocks of air, treat as cliff edge.
        BlockPos support = feet.down();

        for (EnumFacing f : EnumFacing.HORIZONTALS) {
            BlockPos side = support.offset(f);

            // If there's air at the side support and also air below it, it's a sharp drop.
            if (w.isAirBlock(side) && w.isAirBlock(side.down()) && w.isAirBlock(side.down(2))) {
                return true;
            }
        }
        return false;
    }

    private boolean isStandableNoCliff(BlockPos feet) {
        return isStandable(feet) && !isCliffEdge(feet);
    }

    private BlockPos findSafeStandPosNoCliff(double x, double yHint, double z, boolean allowSurfaceFallback) {
        World w = worker.world;

        int xi = net.minecraft.util.math.MathHelper.floor(x);
        int zi = net.minecraft.util.math.MathHelper.floor(z);

        int startY = net.minecraft.util.math.MathHelper.clamp(
                net.minecraft.util.math.MathHelper.floor(yHint),
                2,
                w.getActualHeight() - 3
        );

        BlockPos base = new BlockPos(xi, startY, zi);

        for (int dy = 0; dy <= 32; dy++) {
            BlockPos feet = base.down(dy);
            if (isStandableNoCliff(feet)) return feet;
        }

        for (int dy = 1; dy <= 32; dy++) {
            BlockPos feet = base.up(dy);
            if (isStandableNoCliff(feet)) return feet;
        }

        if (allowSurfaceFallback) {
            BlockPos col = new BlockPos(xi, startY, zi);
            if (w.isBlockLoaded(col)) {
                BlockPos top = w.getTopSolidOrLiquidBlock(new BlockPos(xi, 0, zi));
                if (isStandable(top)) return top;
            }
        }


        return base;
    }

    private int getCachedGroupSize(EntityLivingBase owner) {
        if (owner == null) return 1;
        if (owner.world.isRemote) return 1;

        final java.util.UUID id = owner.getUniqueID();
        final long now = owner.world.getTotalWorldTime();

        GroupCache c = GROUP_CACHE.get(id);
        if (c != null && (now - c.tick) < 20) { // 20 ticks = 1 second
            return c.count;
        }

        // Compute group size once per second
        GroupCache nc = (c != null) ? c : new GroupCache();
        nc.tick = now;

        AxisAlignedBB box = owner.getEntityBoundingBox().grow(32.0D, 8.0D, 32.0D);

        // Count only your follower type(s)
        java.util.List<EntityTravellerWorker> list =
                owner.world.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                        w -> w != null
                                && w.isHiredFollower()
                                && w.isFollowOrderActive()
                                && !w.isInCombat()
                                && w.getAttackTarget() == null
                                && owner.equals(w.getEffectiveFollowTarget()));

        nc.count = Math.max(1, list.size());
        GROUP_CACHE.put(id, nc);
        return nc.count;
    }

    private void updateFormationDir(double desiredX, double desiredZ, boolean ownerSprinting) {
        // Normalize desired
        double dLenSq = desiredX * desiredX + desiredZ * desiredZ;
        if (dLenSq < 1.0e-8D) return;
        double inv = 1.0D / Math.sqrt(dLenSq);
        desiredX *= inv;
        desiredZ *= inv;

        if (!hasFormDir) {
            formDirX = desiredX;
            formDirZ = desiredZ;
            hasFormDir = true;
            return;
        }

        // How far are we turning?
        double dot = formDirX * desiredX + formDirZ * desiredZ;
        dot = MathHelper.clamp(dot, -1.0D, 1.0D);
        double angle = Math.acos(dot);
        if (angle < 1.0e-4D) {
            // tiny change: just blend a bit
            double a = ownerSprinting ? 0.20D : 0.12D;
            formDirX = formDirX * (1.0D - a) + desiredX * a;
            formDirZ = formDirZ * (1.0D - a) + desiredZ * a;
        } else {
            // limit turn speed
            double maxTurn = ownerSprinting ? (MAX_TURN_RAD_PER_TICK * 1.6D) : MAX_TURN_RAD_PER_TICK;
            double t = Math.min(1.0D, maxTurn / angle);

            // rotate current dir toward desired by angle*t
            double cross = formDirX * desiredZ - formDirZ * desiredX; // sign of rotation
            double step = angle * t * (cross >= 0 ? 1.0D : -1.0D);

            double cs = Math.cos(step);
            double sn = Math.sin(step);

            double nx = formDirX * cs - formDirZ * sn;
            double nz = formDirX * sn + formDirZ * cs;

            formDirX = nx;
            formDirZ = nz;
        }

        // renormalize
        double len = Math.sqrt(formDirX * formDirX + formDirZ * formDirZ);
        if (len > 1.0e-8D) {
            formDirX /= len;
            formDirZ /= len;
        }

    }

    private boolean canSeeSkyIgnoringFoliage(BlockPos start) {
        World w = worker.world;
        if (!w.isBlockLoaded(start)) return true; // don't treat unloaded as underground

        // Scan upward: any "real" solid block counts as roof.
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(start);

        int top = w.getActualHeight() - 1;
        for (int y = start.getY(); y <= top; y++) {
            m.setY(y);
            if (!w.isBlockLoaded(m)) break;

            IBlockState s = w.getBlockState(m);
            Block b = s.getBlock();

            // ignore air/replaceable/no-collision blocks
            if (s.getMaterial().isReplaceable() || s.getCollisionBoundingBox(w, m) == null) continue;

            // ignore tree canopy / trunk / vines
            if (isFoliageLike(b)) continue;

            // anything else solid above us => roof
            return false;
        }
        return true;
    }

    private boolean requestPathTo(double gx, double gy, double gz,
                                  double speed, int priority, int groupSize) {
        cachedGoalX = gx;
        cachedGoalY = gy;
        cachedGoalZ = gz;
        hasCachedGoal = true;

        EntityLivingBase owner = worker.getEffectiveFollowTarget();

        if (!PathScheduler.tryAcquire(worker.world, owner, worker.getEntityId(), priority, groupSize)) {
            worker.getMoveHelper().setMoveTo(gx, gy, gz, speed);
            waitingForPathTicks = 10;
            return false;
        }

        return worker.getNavigator().tryMoveToXYZ(gx, gy, gz, speed);
    }

    private int computeGroupCount(int groupSize) {
        int groups = (groupSize + (GROUPS_SIZE_HINT - 1)) / GROUPS_SIZE_HINT; // ceil(groupSize/8)
        groups = MathHelper.clamp(groups, 2, GROUP_ANGLE_OFFSETS.length);
        return groups;
    }

    private boolean ownerHasLineOfSight(EntityLivingBase owner) {
        if (owner == null) return false;
        // In 1.12.2, canEntityBeSeen performs a ray trace
        return owner.canEntityBeSeen(worker);
    }

    // Completely stop navigation + "move helper" nudging.
// This prevents the tiny re-aiming that looks like fast spinning.
    private void hardStop() {
        worker.getNavigator().clearPath();
        hasCachedGoal = false;

        // Cancel any leftover MoveHelper desire to move somewhere
        worker.getMoveHelper().setMoveTo(worker.posX, worker.posY, worker.posZ, 0.0D);

        // Extra safety: kill drifting
        worker.motionX = 0.0D;
        worker.motionZ = 0.0D;

        worker.setSprinting(false);
    }

    private static class GroupCache {
        long tick;
        int count;
    }

}