package com.jubitus.travellers.entity.entityAI.follow;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerBoat;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

public class EntityAIFollowTraveller extends net.minecraft.entity.ai.EntityAIBase {
    private static final double DEFAULT_RANGE = TravellersModConfig.followDefaultRange;     // detect meet-up radius
    private static final double DESIRED_DIST = TravellersModConfig.followDesiredDist;      // stand ~3 blocks behind
    private static final double MAX_DIST = TravellersModConfig.followMaxDist;         // stop if too far
    private static final int REPUSH_EVERY = TravellersModConfig.followRepushEvery;          // ticks
    private static final int DURATION_MIN = TravellersModConfig.followDurationMin * 20;     // 2 minutes
    private static final int DURATION_MAX = TravellersModConfig.followDurationMax * 20;     // 4 minutes
    private static final float ANGLE_ALIGN_DEG = TravellersModConfig.followAngleAlignDeg;    // roughly same direction
    private static final float START_CHANCE = TravellersModConfig.followStartChance;     // 33% chance when meeting to follow
    private static final double LEADER_MOVE_EPS = 1e-4;     // "moving" threshold (squared units from delta)
    private static final int IDLE_STILL_TICKS = 25;         // ~1.25s before we consider leader idle
    private static final int RANDOM_GAZE_MIN = 20;          // 1.0s
    private static final int RANDOM_GAZE_MAX = 50;          // 2.5s
    private static final int IDLE_MOVE_MIN = 25;          // ~1.25s
    private static final int IDLE_MOVE_MAX = 80;          // up to 4s
    private static final int IDLE_MOVE_COOLDOWN_MIN = 40; // 2s
    private static final int IDLE_MOVE_COOLDOWN_MAX = 140;// up to 7s
    private static final double IDLE_MOVE_RADIUS = 1.8;   // radius around the back point
    private static final int MAX_FOLLOWERS_PER_LEADER = TravellersModConfig.followMaxFollowersPerLeader;
    private final EntityTraveller mob;
    private final double angleAlignCos;
    private final double speed;
    private final double rangeSq;
    private EntityTraveller leader;
    private int repathTicker;
    private Vec3d smoothedFwd = Vec3d.ZERO;
    private Vec3d lastLeaderPos = Vec3d.ZERO;
    private double lateralOffset = 0.0;     // constant per follow session
    private Vec3d lastBackPoint = null;     // last issued target
    private int glanceCooldown = 0;
    private int glanceTicks = 0;
    private int leaderStillTicks = 0;       // how long leader has been effectively still
    private int nextRandomGazeTick = 0;     // when to pick the next idle/random gaze
    private Vec3d currentGaze = null;       // current random gaze target
    // --- idle micro-behaviour state ---
    private int idleMoveTicks = 0;          // how long we keep walking a small idle move
    private int idleMoveCooldown = 0;       // delay before we can start another idle move
    // "talking" / social look-at
    private EntityTraveller socialLookTarget = null;
    private int socialLookTicks = 0;
    // --- NEW: follow gating state ---
    /**
     * Next tick at/after which we’re allowed to *attempt* starting to follow.
     */
    private int nextFollowTryTick = 0;
    /**
     * Track whether we had a valid candidate last tick, to edge-trigger “meeting”.
     */
    private boolean hadCandidateLastTick = false;

    public EntityAIFollowTraveller(EntityTraveller mob, double angleAlignCos, double speed) {
        this.mob = mob;
        this.angleAlignCos = Math.cos(Math.toRadians(ANGLE_ALIGN_DEG));
        this.speed = speed;
        this.rangeSq = DEFAULT_RANGE * DEFAULT_RANGE;
        this.setMutexBits(1);
    }

    private static double cosDeg(float deg) {
        // Want align >= cos(theta) where theta is the max allowed angle between headings.
        return Math.cos(Math.toRadians(deg));
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat() || mob.isAutoEating()) return false;
        if (mob.getTargetVillage() == null) return false;
        if (mob.hasFollowLeader()) return false;

        // NEW: don't start a follow session if we're already in a boat
        if (isInBoat(mob)) return false;

        // Cooldown: don't even look for a leader until it elapses
        if (mob.ticksExisted < nextFollowTryTick) return false;

        // Find best candidate (same as your code, minus the angle bug)
        EntityTraveller cand = null;
        double best = Double.NEGATIVE_INFINITY;

        Vec3d myDir = travelDir(mob);
        List<EntityTraveller> nearby = mob.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                mob.getEntityBoundingBox().grow(Math.sqrt(rangeSq), 2.0, Math.sqrt(rangeSq)),
                e -> e != null && e.isEntityAlive() && e != mob && !e.isInCombat()
        );

        for (EntityTraveller t : nearby) {
            if (!(t instanceof EntityTraveller)) continue;
            if (t.getTargetVillage() == null) continue;
            double dSq = mob.getDistanceSq(t);
            if (dSq > rangeSq) continue;
            if (compareIds(mob, t) <= 0) continue;
            if (!mob.getCultureId().equalsIgnoreCase(t.getCultureId())) continue;

            double align = dot2D(myDir, travelDir(t));
            double score = align - dSq * 0.02;
            if (score > best && align >= angleAlignCos) {
                best = score;
                cand = t;
            }
        }
        // Enforce max followers per leader (if > 0)
        if (cand != null && MAX_FOLLOWERS_PER_LEADER > 0) {
            if (cand.getCurrentFollowerCount() >= MAX_FOLLOWERS_PER_LEADER) {
                cand = null;
            }
        }

        boolean hasCandidate = (cand != null);


        // Only roll the dice when we transition from no-candidate -> has-candidate (i.e., when they "meet")
        boolean justMet = hasCandidate && !hadCandidateLastTick;

        // Update edge-detection memory for next tick
        hadCandidateLastTick = hasCandidate;

        if (!justMet) return false;

        // Random start
        if (mob.getRNG().nextFloat() > START_CHANCE) {
            // Failed the roll: back off for a while before trying again
            nextFollowTryTick = mob.ticksExisted + (20 * 60 + mob.getRNG().nextInt(10 * 120)); // 60–120sc
            return false;
        }

        // Success: start following this candidate
        this.leader = cand;
        return true;
    }

    private boolean isInBoat(EntityTraveller t) {
        return t.isRiding() && t.getRidingEntity() instanceof EntityTravellerBoat;
    }

    private static Vec3d travelDir(EntityTraveller t) {
        // prefer waypoint direction if present, else motion, else look
        BlockPos wp = t.getCurrentWaypoint();
        if (wp != null) {
            Vec3d to = new Vec3d(wp.getX() + 0.5 - t.posX, 0, wp.getZ() + 0.5 - t.posZ);
            if (to.lengthSquared() > 1e-4) return to.normalize();
        }
        Vec3d mot = new Vec3d(t.motionX, 0, t.motionZ);
        if (mot.lengthSquared() > 1e-4) return mot.normalize();
        return new Vec3d(-MathHelper.sin(t.rotationYaw * 0.017453292F), 0, MathHelper.cos(t.rotationYaw * 0.017453292F)).normalize();
    }

    /**
     * Return negative if a<b; used to choose a deterministic leader.
     */
    private static int compareIds(Entity a, Entity b) {
        // entityId is fine in 1.12
        return Integer.compare(a.getEntityId(), b.getEntityId());
    }

    private static double dot2D(Vec3d a, Vec3d b) {
        return a.x * b.x + a.z * b.z;
    }

    // --- helpers ---

    @Override
    public boolean shouldContinueExecuting() {
        if (leader == null || !leader.isEntityAlive()) return false;
        if (mob.isInCombat() || mob.isAutoEating()) return false;
        if (mob.getTargetVillage() == null) return false;
        if (!mob.hasFollowLeader() || mob.getFollowLeader() != leader) return false;

        boolean leaderNearVillage = leader.getNearestVillage() != null &&
                leader.getDistanceSq(leader.getNearestVillage()) < (TravellersModConfig.VILLAGE_NEAR * TravellersModConfig.VILLAGE_NEAR);
        boolean meNearVillage = mob.getNearestVillage() != null &&
                mob.getDistanceSq(mob.getNearestVillage()) < (TravellersModConfig.VILLAGE_NEAR * TravellersModConfig.VILLAGE_NEAR);
        if (leaderNearVillage || meNearVillage) return false;

        return mob.getDistanceSq(leader) <= (MAX_DIST * MAX_DIST);
    }

    @Override
    public void startExecuting() {
        this.repathTicker = 0;
        int dur = DURATION_MIN + mob.getRNG().nextInt(DURATION_MAX - DURATION_MIN + 1);
        mob.startFollowing(leader, dur);

        long mix = (31L * mob.getEntityId()) ^ (127L * leader.getEntityId());
        this.lateralOffset = ((mix & 1L) == 0L) ? 0.9 : -0.9;

        this.smoothedFwd = new Vec3d(
                -MathHelper.sin(leader.rotationYaw * 0.017453292F),
                0,
                MathHelper.cos(leader.rotationYaw * 0.017453292F)
        ).normalize();
        this.lastLeaderPos = new Vec3d(leader.posX, leader.posY, leader.posZ);

        this.lastBackPoint = computeBackPoint();
        pushFollowPathTo(lastBackPoint);
    }

    @Override
    public void resetTask() {
        mob.stopFollowing();
        leader = null;

        // After finishing a session, wait before trying to latch onto someone again
        nextFollowTryTick = mob.ticksExisted + (200 + mob.getRNG().nextInt(200)); // ~10–20s
        // Also require a fresh meeting edge next time
        hadCandidateLastTick = true;
    }

    @Override
    public void updateTask() {
        if (leader == null) return;

        // --- BOAT FOLLOW HANDLER ---
        if (handleBoatFollow()) {
            // We're in boat-follow mode; skip normal ground follow / idle logic.
            return;
        }
        // --- Update leader forward (EMA) & motion state ---
        Vec3d now = new Vec3d(leader.posX, leader.posY, leader.posZ);
        Vec3d delta = now.subtract(lastLeaderPos);
        lastLeaderPos = now;

        boolean leaderMoving = isMovingHoriz(delta);
        if (leaderMoving) {
            leaderStillTicks = 0;
            // refresh smoothed heading from instantaneous direction
            Vec3d inst = new Vec3d(delta.x, 0, delta.z).normalize();
            double alpha = 0.25; // lower = smoother
            this.smoothedFwd = new Vec3d(
                    smoothedFwd.x * (1 - alpha) + inst.x * alpha,
                    0,
                    smoothedFwd.z * (1 - alpha) + inst.z * alpha
            ).normalize();
        } else {
            leaderStillTicks++;
            // keep previous smoothedFwd (gives continuity)
        }

        // --- Glance timers (unchanged behavior) ---
        if (glanceCooldown > 0) glanceCooldown--;
        if (glanceTicks > 0) glanceTicks--;

        if (glanceCooldown == 0 && mob.getRNG().nextFloat() < 0.02f) { // ~1 in 50 ticks
            glanceTicks = 20 + mob.getRNG().nextInt(20);         // 1–2s
            glanceCooldown = 100 + mob.getRNG().nextInt(100);    // 5–10s
        }

        // --- Head/Look behavior ---
        // --- Head/Look behavior ---
        if (glanceTicks > 0) {
            // Sometimes look at the leader while walking
            mob.getLookHelper().setLookPositionWithEntity(leader, 30.0F, 30.0F);

        } else if (!leaderMoving && leaderStillTicks >= IDLE_STILL_TICKS) {
            // Leader idle: more varied social behaviour

            // 1) if we already have a "conversation partner", keep looking at them
            if (socialLookTicks > 0 && socialLookTarget != null && socialLookTarget.isEntityAlive()) {
                mob.getLookHelper().setLookPositionWithEntity(socialLookTarget, 20.0F, 20.0F);
                socialLookTicks--;
            } else {
                socialLookTarget = null;
                socialLookTicks = 0;

                // 2) occasional chance to start looking at another follower (like talking)
                if (mob.getRNG().nextFloat() < 0.06f) {
                    EntityTraveller other = pickRandomFellowFollower();
                    if (other != null) {
                        socialLookTarget = other;
                        socialLookTicks = 20 + mob.getRNG().nextInt(40); // 1–3s
                    }
                }

                // 3) if not talking, do general idle random gaze
                if (socialLookTicks == 0) {
                    randomGazeTick();
                }
            }
            // --- Idle micro-movement when leader is stopped ---
            if (!leaderMoving && leaderStillTicks >= IDLE_STILL_TICKS) {
                idleMoveTick();
            } else {
                // reset short-term idle move state when leader is clearly moving
                idleMoveTicks = 0;
            }
        } else {
            // Leader moving (or only briefly paused): look where we're going, not at the leader
            Vec3d fwd = (smoothedFwd.lengthSquared() > 1e-6) ? smoothedFwd : lastResortFacing(leader);
            double ahead = 4.0 + mob.getRNG().nextDouble() * 2.0;
            Vec3d target = new Vec3d(
                    mob.posX + fwd.x * ahead,
                    mob.posY + mob.getEyeHeight() * (0.9 + mob.getRNG().nextDouble() * 0.15),
                    mob.posZ + fwd.z * ahead
            );
            lookAt(target, 15.0F, 15.0F);

            // decay idle state so it gets repicked when we become idle again
            currentGaze = null;
            socialLookTarget = null;
            socialLookTicks = 0;
        }

        // --- Distance gating to avoid micro-corrections ---
        double d = mob.getDistance(leader);
        boolean tucked = d < (DESIRED_DIST + 0.6);

        // --- Single repath cadence point ---
        if (++repathTicker >= REPUSH_EVERY || mob.getNavigator().noPath()) {
            repathTicker = 0;

            Vec3d target = computeBackPoint();

            // Hysteresis: only repath if we meaningfully moved the target
            boolean bigShift = (lastBackPoint == null) ||
                    target.squareDistanceTo(lastBackPoint) > (0.8 * 0.8); // ~0.8 block

            if (bigShift || (!tucked && mob.getNavigator().noPath())) {
                if (!tucked) {
                    lastBackPoint = target;
                    pushFollowPathTo(target);
                } else {
                    lastBackPoint = target; // we're tucked; remember but don't push
                }
            }

            // If very close, stop entirely to reduce jitter
            if (d < (DESIRED_DIST + 0.4)) {
                mob.getNavigator().clearPath();
            }
        }
    }

    /**
     * Handle special follow behaviour when the leader is in a boat.
     * Returns true if we handled the tick in boat-mode and the caller
     * should skip normal ground-follow logic.
     */
    private boolean handleBoatFollow() {
        // If leader isn't in a boat, nothing special to do
        if (!isInBoat(leader)) return false;

        EntityTravellerBoat leaderBoat =
                (EntityTravellerBoat) leader.getRidingEntity();

        if (leaderBoat == null || leaderBoat.isDead) return false;

        // --- 1) If we're already riding the same boat, just let the boat carry us ---
        if (mob.isRiding() && mob.getRidingEntity() == leaderBoat) {
            // Optional: look where the boat is going
            Vec3d dir = new Vec3d(
                    -MathHelper.sin(leaderBoat.rotationYaw * 0.017453292F),
                    0,
                    MathHelper.cos(leaderBoat.rotationYaw * 0.017453292F)
            ).normalize();
            double ahead = 6.0;
            Vec3d look = new Vec3d(
                    mob.posX + dir.x * ahead,
                    mob.posY + mob.getEyeHeight(),
                    mob.posZ + dir.z * ahead
            );
            lookAt(look, 15.0F, 15.0F);
            return true;
        }

        // --- 2) Try to mount leader boat if it has a free passenger slot ---
        int maxPassengers = 2; // vanilla boat: 2 seats
        if (!mob.isRiding() && leaderBoat.getPassengers().size() < maxPassengers) {
            double d2 = mob.getDistanceSq(leaderBoat);

            // If we're close enough, attempt to mount
            if (d2 < 2.2 * 2.2) {
                mob.getNavigator().clearPath();
                // true = force mount even if already riding something else (we checked above)
                mob.startRiding(leaderBoat, true);
                return true;
            }

            // Otherwise, walk/swim to the boat
            mob.getNavigator().tryMoveToEntityLiving(leader, this.speed);
            return true;
        }

        // --- 3) Leader boat is full: use our own boat to follow if possible ---

        // If we're not in a boat yet, and we're in water, spawn our own boat
        if (!isInBoat(mob) && mob.isInWater()) {
            tryEnterOwnBoatForFollow();
            return true;
        }

        // If we're in our own boat, steer it to a "back point" behind the leader boat
        if (isInBoat(mob) && mob.getRidingEntity() != leaderBoat) {
            Vec3d back = computeBoatBackPoint(leaderBoat);
            steerOwnBoatTo(back);
            return true;
        }

        // Fallback: let normal logic run (e.g., we're on land while leader is in boat far away)
        return false;
    }

    // ADD — is leader moving (horizontal)
    private static boolean isMovingHoriz(Vec3d delta) {
        double s = delta.x * delta.x + delta.z * delta.z;
        return s > LEADER_MOVE_EPS;
    }

    private EntityTraveller pickRandomFellowFollower() {
        if (leader == null) return null;
        List<EntityTraveller> nearbyFollowers = mob.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                mob.getEntityBoundingBox().grow(4.0, 2.0, 4.0),
                e -> e != null
                        && e.isEntityAlive()
                        && e != mob
                        && e.hasFollowLeader()
                        && e.getFollowLeader() == leader
        );
        if (nearbyFollowers.isEmpty()) return null;
        return nearbyFollowers.get(mob.getRNG().nextInt(nearbyFollowers.size()));
    }

    // ADD — pick/maintain a natural random gaze around the mob (used for idle + "sometimes look elsewhere")
    private void randomGazeTick() {
        // refresh target periodically
        if (mob.ticksExisted >= nextRandomGazeTick || currentGaze == null) {
            double ang = mob.getRNG().nextDouble() * Math.PI * 2.0;
            double dist = 2.0 + mob.getRNG().nextDouble() * 4.0;     // 2–6 blocks away
            double gx = mob.posX + Math.cos(ang) * dist;
            double gz = mob.posZ + Math.sin(ang) * dist;
            double gy = mob.posY + mob.getEyeHeight() * (0.85 + mob.getRNG().nextDouble() * 0.25); // near eye height

            currentGaze = new Vec3d(gx, gy, gz);
            nextRandomGazeTick = mob.ticksExisted + RANDOM_GAZE_MIN + mob.getRNG().nextInt(RANDOM_GAZE_MAX - RANDOM_GAZE_MIN + 1);
        }
        // gently look at the current random target
        lookAt(currentGaze, 10.0F, 10.0F);
    }

    private void idleMoveTick() {
        // already in an idle move: just count it down
        if (idleMoveTicks > 0) {
            idleMoveTicks--;
            return;
        }

        // cooldown before next move
        if (idleMoveCooldown > 0) {
            idleMoveCooldown--;
            return;
        }

        // chance to start a new micro-move when we're idle
        if (mob.getNavigator().noPath() && mob.getRNG().nextFloat() < 0.18f) {
            Vec3d center = computeBackPoint();   // our usual follow slot
            double ang = mob.getRNG().nextDouble() * Math.PI * 2.0;
            double dist = 0.5 + mob.getRNG().nextDouble() * (IDLE_MOVE_RADIUS - 0.5);

            double tx = center.x + Math.cos(ang) * dist;
            double tz = center.z + Math.sin(ang) * dist;
            double ty = center.y;

            pushFollowPathTo(new Vec3d(tx, ty, tz));

            idleMoveTicks = IDLE_MOVE_MIN + mob.getRNG().nextInt(IDLE_MOVE_MAX - IDLE_MOVE_MIN + 1);
            idleMoveCooldown = IDLE_MOVE_COOLDOWN_MIN +
                    mob.getRNG().nextInt(IDLE_MOVE_COOLDOWN_MAX - IDLE_MOVE_COOLDOWN_MIN + 1);
        }
    }

    private static Vec3d lastResortFacing(EntityTraveller t) {
        return new Vec3d(-MathHelper.sin(t.rotationYaw * 0.017453292F), 0,
                MathHelper.cos(t.rotationYaw * 0.017453292F)).normalize();
    }

    // ADD — set look to a point
    private void lookAt(Vec3d p, float yawSpeed, float pitchSpeed) {
        mob.getLookHelper().setLookPosition(p.x, p.y, p.z, yawSpeed, pitchSpeed);
    }

    private void tryEnterOwnBoatForFollow() {
        if (mob.world.isRemote) return;
        if (isInBoat(mob)) return;
        if (!mob.isInWater()) return;

        World w = mob.world;

        BlockPos spawnPos = findBoatSurfaceSpawnPosForFollow();
        if (spawnPos == null) {
            return; // no proper surface water here → don't spawn a boat
        }

        double x = spawnPos.getX() + 0.5;
        double z = spawnPos.getZ() + 0.5;
        double y = spawnPos.getY() + 0.02D;

        EntityTravellerBoat boat =
                new EntityTravellerBoat(w, x, y, z);

        // Face roughly toward the leader
        Vec3d dir = new Vec3d(leader.posX - x, 0, leader.posZ - z);
        if (dir.lengthSquared() > 1e-6) {
            dir = dir.normalize();
            float yaw = (float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0F;

            boat.setLocationAndAngles(x, y, z, yaw, boat.rotationPitch);
            boat.rotationYaw = yaw;
            boat.prevRotationYaw = yaw;
        }

        w.spawnEntity(boat);
        mob.startRiding(boat, true);
    }

    private Vec3d computeBoatBackPoint(EntityTravellerBoat leaderBoat) {
        // Forward from boat yaw
        Vec3d fwd = new Vec3d(
                -MathHelper.sin(leaderBoat.rotationYaw * 0.017453292F),
                0,
                MathHelper.cos(leaderBoat.rotationYaw * 0.017453292F)
        ).normalize();

        Vec3d left = new Vec3d(-fwd.z, 0, fwd.x).normalize().scale(lateralOffset);

        double x = leaderBoat.posX - fwd.x * DESIRED_DIST + left.x;
        double z = leaderBoat.posZ - fwd.z * DESIRED_DIST + left.z;
        double y = leaderBoat.posY;

        return new Vec3d(x, y, z);
    }

    private void steerOwnBoatTo(Vec3d target) {
        if (!isInBoat(mob)) return;
        EntityTravellerBoat boat =
                (EntityTravellerBoat) mob.getRidingEntity();
        if (boat == null) return;

        double dx = target.x - boat.posX;
        double dz = target.z - boat.posZ;
        double dist2 = dx * dx + dz * dz;
        if (dist2 < 0.3 * 0.3) {
            boat.motionX *= 0.5;
            boat.motionZ *= 0.5;
            return;
        }

        double dist = Math.sqrt(dist2);
        Vec3d dir = new Vec3d(dx / dist, 0, dz / dist);

        // Face the boat
        float yaw = (float) (Math.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0F;
        boat.setLocationAndAngles(boat.posX, boat.posY, boat.posZ, yaw, boat.rotationPitch);
        boat.rotationYaw = yaw;
        boat.prevRotationYaw = yaw;

        // Move boat forward (speed scaled from AI speed)
        double boatSpeed = this.speed * 0.8; // tweak to taste
        boat.motionX = dir.x * boatSpeed;
        boat.motionZ = dir.z * boatSpeed;

        boat.velocityChanged = true;
    }

    @Nullable
    private BlockPos findBoatSurfaceSpawnPosForFollow() {
        World w = mob.world;

        int colX = MathHelper.floor(mob.posX);
        int colZ = MathHelper.floor(mob.posZ);
        int startY = MathHelper.floor(mob.posY);
        int maxY = w.getActualHeight() - 1;
        int minY = 1;

        BlockPos topWater = null;

        // Scan upwards for the top of the water column
        for (int y = startY; y <= maxY; y++) {
            BlockPos p = new BlockPos(colX, y, colZ);
            if (!w.isBlockLoaded(p)) break;
            IBlockState s = w.getBlockState(p);
            if (isVanillaWater(s)) {
                topWater = p;
            } else if (topWater != null) {
                break;
            }
        }

        if (topWater == null) {
            for (int y = startY - 1; y >= minY; y--) {
                BlockPos p = new BlockPos(colX, y, colZ);
                if (!w.isBlockLoaded(p)) break;
                IBlockState s = w.getBlockState(p);
                if (isVanillaWater(s)) {
                    topWater = p;
                    break;
                }
            }
        }

        if (topWater == null) return null;

        BlockPos spawnPos = topWater.up();
        IBlockState head = w.getBlockState(spawnPos);
        if (!head.getMaterial().isReplaceable()
                && head.getCollisionBoundingBox(w, spawnPos) != null) {
            return null;
        }

        return spawnPos;
    }

    // Small local copy of your water check (or reuse if you have one in this class)
    private static boolean isVanillaWater(IBlockState s) {
        Block b = s.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }

    private Vec3d computeBackPoint() {
        Vec3d fwd = (smoothedFwd.lengthSquared() > 1e-6) ? smoothedFwd
                : new Vec3d(-MathHelper.sin(leader.rotationYaw * 0.017453292F), 0,
                MathHelper.cos(leader.rotationYaw * 0.017453292F)).normalize();

        Vec3d left = new Vec3d(-fwd.z, 0, fwd.x).normalize().scale(lateralOffset);
        double x = leader.posX - fwd.x * DESIRED_DIST + left.x;
        double z = leader.posZ - fwd.z * DESIRED_DIST + left.z;
        double y = leader.posY;

        return new Vec3d(x, y, z);
    }

    private void pushFollowPathTo(Vec3d p) {
        // "owner" for scheduling = leader (everyone in this follow group shares same leader)
        EntityLivingBase owner = leader;

        // group size = how many followers are around this leader (you already track this)
        int groupSize = 1;
        try {
            groupSize = Math.max(1, leader.getCurrentFollowerCount() + 1);
        } catch (Throwable ignored) {
        }

        // priority rules:
        // 2 = urgent if we have no path OR we’re far
        // 0 = normal otherwise
        int priority = 0;
        double d2 = mob.getDistanceSq(leader);
        if (mob.getNavigator().noPath() || d2 > (MAX_DIST * 0.75) * (MAX_DIST * 0.75)) {
            priority = 2;
        }

        // Ask scheduler if we’re allowed to compute a new path THIS tick
        if (!PathScheduler.tryAcquire(mob.world, owner, mob.getEntityId(), priority, groupSize)) {
            // Not allowed: keep current path (mob still walks normally on existing path)
            return;
        }

        mob.getNavigator().tryMoveToXYZ(p.x, p.y, p.z, speed);
    }

}

