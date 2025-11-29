package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.camp.CampfireBlock;
import com.jubitus.traveller.traveller.camp.CampfireManager;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

public class EntityAICampJoinFire extends EntityAIBase {

    private static final double RUN_SPEED_FAR = 1.20D; // fast approach (>24 blocks)
    private static final double JOG_SPEED_MID = 0.80D; // 8–24 blocks
    private static final double WALK_SPEED_NEAR = 0.35D; // <8 blocks (final alignment)
    private static final int REISSUE_EVERY = 15;    // ticks between speed refreshes
    private static final double CAMP_WALK_SPEED = 0.25D;
    private final EntityTraveller mob;
    private final double approachSpeed;
    private int lastReissueTick = 0;
    private int campWanderCooldown = 0;
    private int campLookCooldown = 0;
    private BlockPos campWanderTarget = null;
    // inside EntityAICampJoinFire
    @Nullable
    private EntityTraveller socialPeer = null;
    private int socialLookTicks = 0;

    private BlockPos targetCamp;
    private BlockPos targetCampApproach; // where we actually walk to (never the fire block)


    public EntityAICampJoinFire(EntityTraveller mob, double approachSpeed) {
        this.mob = mob;
        this.approachSpeed = approachSpeed;
        this.setMutexBits(1 | 2);
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false; // only combat blocks joining
        if (mob.isVillageIdling()) return false;
        if (mob.getTargetVillage() == null) return false;
        if (mob.hasCampfire()) return false;           // already at a camp (leader or joiner)
        if (mob.getTravelRecoverTicks() > 0) return false;

        BlockPos camp = CampfireManager.get(mob.world)
                .nearestActive(mob.world, mob.getPosition(), TravellersModConfig.SEEK_RADIUS, /*excludeOwner*/ null, /*verifyLit*/ true);
        if (camp == null) return false;

        // Skip if we recently avoided this camp
        if (mob.isAvoidingCamp(camp)) return false;

        // Capacity pre-check
        int campersNow = mob.world.getEntitiesWithinAABB(EntityTraveller.class,
                        new AxisAlignedBB(camp).grow(8.0),
                        e -> e != null && e.isEntityAlive() && camp.equals(e.getCampfirePos()))
                .size();
        if (campersNow >= TravellersModConfig.MAX_JOINERS_PER_CAMP) {
            mob.avoidCamp(camp, 20 * 20); // ~20s; tune as you like
            return false;
        }

        this.targetCamp = camp;
        // pick a safe approach position around the campfire
        this.targetCampApproach = pickSafeCampApproachPos(camp);
        if (this.targetCampApproach == null) {
            // no safe spot found; treat as non-executable
            this.targetCamp = null;
            return false;
        }

        return true;
    }

    @Override
    public boolean shouldContinueExecuting() {
        if (mob.world.isRemote) return false;
        if (mob.isInCombat()) return false;   // leave camp if combat starts

        if (targetCamp == null) return false;

        WorldServer ws = (WorldServer) mob.world;
        int cx = targetCamp.getX() >> 4, cz = targetCamp.getZ() >> 4;
        if (!ws.getChunkProvider().chunkExists(cx, cz)) return false;

        IBlockState st = mob.world.getBlockState(targetCamp);
        if (!CampfireBlock.isLit(st)) return false;

        int campersNow = mob.world.getEntitiesWithinAABB(EntityTraveller.class,
                        new AxisAlignedBB(targetCamp).grow(8.0),
                        e -> e != null && e.isEntityAlive() && targetCamp.equals(e.getCampfirePos()))
                .size();

        // If we haven't joined yet and it filled up, give up and avoid for a bit
        if (mob.getCampfirePos() == null && campersNow >= TravellersModConfig.MAX_JOINERS_PER_CAMP) {
            mob.avoidCamp(targetCamp, 20 * 15); // ~15s backoff while walking
            return false;
        }

        // Respect seek radius even after starting
        if (mob.getDistanceSqToCenter(targetCamp) > (double) (TravellersModConfig.SEEK_RADIUS * TravellersModConfig.SEEK_RADIUS))
            return false;

        boolean enRoute = mob.getDistanceSqToCenter(targetCamp) > 9.0;
        // --- personal leave time for joiners ---
        if (mob.getCampfirePos() != null) {
            long now = ws.getTotalWorldTime();
            if (now >= mob.travellerCamp.campLeaveTime) {
                return false; // done camping
            }
        }
        boolean alreadyJoined = mob.getCampfirePos() != null;

        // Keep AI alive either while walking in, or while we're a joined camper
        return enRoute || alreadyJoined;
    }

    @Override
    public void startExecuting() {
        if (targetCamp != null && targetCampApproach != null) {
            mob.getNavigator().tryMoveToXYZ(
                    targetCampApproach.getX() + 0.5,
                    targetCampApproach.getY(),
                    targetCampApproach.getZ() + 0.5,
                    RUN_SPEED_FAR
            );
            lastReissueTick = mob.ticksExisted;
        }
    }

    @Override
    public void resetTask() {

        mob.clearCamp(); // joiners just forget; they never remove fire
        mob.getNavigator().clearPath();
        mob.setCampJoinCooldown(); // <-- add this
        targetCamp = null;
        targetCampApproach = null;

        campWanderCooldown = 0;
        campLookCooldown = 0;
        campWanderTarget = null;
    }

    @Override
    public void updateTask() {
        if (targetCamp == null) return;

        double d2 = mob.getDistanceSqToCenter(targetCamp);

        if (d2 <= 9.0) {
            // --- arrival / camping branch ---
            int campersNow = mob.world.getEntitiesWithinAABB(EntityTraveller.class,
                            new AxisAlignedBB(targetCamp).grow(8.0),
                            e -> e != null && e.isEntityAlive() && targetCamp.equals(e.getCampfirePos()))
                    .size();

            if (campersNow >= TravellersModConfig.MAX_JOINERS_PER_CAMP) {
                mob.avoidCamp(targetCamp, 20 * 25); // backoff
                mob.clearCamp();
                mob.getNavigator().clearPath();
                targetCamp = null;
                return;
            }

            // ensure we're joined at this camp
            if (mob.getCampfirePos() == null || !targetCamp.equals(mob.getCampfirePos())) {
                int min = 20 * 20;   // 30s
                int max = 20 * 60;  // 80s
                int dur = min + mob.getRNG().nextInt(max - min + 1);
                mob.joinCampAt(targetCamp, dur);
            }


            WorldServer ws = (WorldServer) mob.world;
            long now = ws.getTotalWorldTime();
            CampfireManager.get(ws).addOrRefresh(
                    targetCamp, mob.dimension, /*owner*/ null, now + (20 * 10), /*nearRadius*/ 28
            );

            // --- occasional swing / approach to the front of the fire ---
            if (mob.travellerCamp.campSwingTimer-- <= 0) {
                boolean did = approachAndSwingAtFire();
                mob.travellerCamp.campSwingTimer = did
                        ? (TravellersModConfig.CAMP_SWING_EVERY + mob.getRNG().nextInt(25))
                        : (30 + mob.getRNG().nextInt(30));
            }

            // --- random, slow strolling around the campfire ---
// --- random, slow strolling around the campfire (rarer) ---
            if (--campWanderCooldown <= 0 && mob.getNavigator().noPath()) {
                // Only actually wander ~20% of the time when the cooldown ends
                if (mob.getRNG().nextFloat() < 0.2F) {
                    BlockPos stroll = pickRandomCampStrollPos(targetCamp);
                    if (stroll != null) {
                        mob.getNavigator().tryMoveToXYZ(
                                stroll.getX() + 0.5,
                                stroll.getY(),
                                stroll.getZ() + 0.5,
                                CAMP_WALK_SPEED
                        );
                        campWanderTarget = stroll;
                        tickSocialLook();
                    }

                } else {
                    campWanderTarget = null; // “decided not to move”
                }

                // Make the delay bigger so checks are less frequent in the first place
                int min = TravellersModConfig.CAMP_WANDER_MIN_DELAY ;
                int max = TravellersModConfig.CAMP_WANDER_MAX_DELAY ;
                campWanderCooldown = min + mob.getRNG().nextInt(max - min + 1);
            }

            // --- random look behaviour (fire vs other campers) ---
            if (--campLookCooldown <= 0) {
                doRandomCampLook(targetCamp);
                campLookCooldown = TravellersModConfig.CAMP_LOOK_MIN_DELAY
                        + mob.getRNG().nextInt(TravellersModConfig.CAMP_LOOK_MAX_DELAY - TravellersModConfig.CAMP_LOOK_MIN_DELAY + 1);
            }

        } else {
            // --- approach the safe spot around the fire, not the fire block itself ---
            if (targetCampApproach == null) {
                targetCampApproach = pickSafeCampApproachPos(targetCamp);
                if (targetCampApproach == null) {
                    // give up if we can't find a safe approach
                    mob.clearCamp();
                    mob.getNavigator().clearPath();
                    targetCamp = null;
                    return;
                }
            }

            final double tx = targetCampApproach.getX() + 0.5;
            final double ty = targetCampApproach.getY();
            final double tz = targetCampApproach.getZ() + 0.5;

            double dist = mob.getDistance(tx, ty, tz);
            double speed = (dist > 24.0) ? RUN_SPEED_FAR
                    : (dist > 8.0) ? JOG_SPEED_MID
                    : WALK_SPEED_NEAR;

            if (mob.getNavigator().noPath() || (mob.ticksExisted - lastReissueTick) >= REISSUE_EVERY) {
                mob.getNavigator().tryMoveToXYZ(tx, ty, tz, speed);
                lastReissueTick = mob.ticksExisted;
            }
        }

        // Fire gone? bail and clear camp (keep this at the end)
        IBlockState st = mob.world.getBlockState(targetCamp);
        if (!CampfireBlock.isLit(st)) {
            mob.clearCamp();
            targetCamp = null;
            mob.getNavigator().clearPath();
        }
    }

    /**
     * Walk to front-of-fire, wait close, then swing. Returns true if we swung this tick.
     */
    private boolean approachAndSwingAtFire() {
        BlockPos fp = mob.getCampfirePos();
        if (fp == null) return false;
        BlockPos front = mob.pickFrontOfFire(fp);
        if (front == null) return false;

        double d2 = mob.getDistanceSqToCenter(front);
        if (d2 > 2.0) {
            // NOTE: use approachSpeed here (roamSpeed only exists on the leader AI)
            mob.getNavigator().tryMoveToXYZ(front.getX() + 0.5, front.getY(), front.getZ() + 0.5, approachSpeed);
            return false;
        }

        // close: look & swing
        mob.lookAtFire();
        mob.world.setEntityState(mob, (byte) 4);
        mob.swingArm(EnumHand.MAIN_HAND);
        mob.getNavigator().clearPath(); // stay near the front spot briefly
        return true;
    }

    /**
     * Pick a random walkable spot in a ring around the campfire, avoiding the fire block and crowded spots.
     */
    @Nullable
    private BlockPos pickRandomCampStrollPos(BlockPos camp) {
        for (int tries = 0; tries < 8; tries++) {
            // ring between radius 2 and CAMP_RADIUS-1 (so we never step on the fire itself)
            double minR = 2.0;
            double maxR = Math.max(minR + 1.0, TravellersModConfig.CAMP_RADIUS - 1.0);
            double r = minR + (mob.getRNG().nextDouble() * (maxR - minR));
            double angle = mob.getRNG().nextDouble() * Math.PI * 2.0;

            double dx = camp.getX() + 0.5 + r * Math.cos(angle);
            double dz = camp.getZ() + 0.5 + r * Math.sin(angle);
            BlockPos pos = new BlockPos(dx, camp.getY(), dz);

            // never target the campfire block itself
            if (pos.equals(camp)) continue;

            // basic "walkable" check: solid block below, non-solid / non-fire where we stand
            BlockPos below = pos.down();
            IBlockState belowSt = mob.world.getBlockState(below);
            IBlockState atSt = mob.world.getBlockState(pos);

            if (!belowSt.isSideSolid(mob.world, below, EnumFacing.UP)) continue;
            if (!atSt.getMaterial().isReplaceable()) continue;  // don't walk into solid full blocks
            if (CampfireBlock.isLit(atSt)) continue;            // don't walk on the fire

            // try to not stack on top of other travellers
            if (!isSpotFreeOfCampers(pos)) continue;

            return pos;
        }

        return null;
    }

    /**
     * Occasionally look at the fire or another random camper.
     */
    private void doRandomCampLook(BlockPos camp) {
        if (camp == null) return;

        int roll = mob.getRNG().nextInt(100);

        // 40%: look at fire
        if (roll < 40) {
            mob.lookAtFire();
            return;
        }

        // 60%: try to look at a random other camper, fallback to fire
        EntityTraveller other = pickRandomCamperNear(camp, 8.0);
        if (other != null && other != mob) {
            mob.getLookHelper().setLookPositionWithEntity(other, 20.0F, 20.0F);
        } else {
            mob.lookAtFire();
        }
    }

    @Nullable
    private EntityTraveller pickRandomCamperNear(BlockPos camp, double radius) {
        List<EntityTraveller> list = mob.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                new AxisAlignedBB(camp).grow(radius),
                e -> e != null && e.isEntityAlive() && camp.equals(e.getCampfirePos())
        );

        if (list.isEmpty()) return null;

        // choose random camper
        return list.get(mob.getRNG().nextInt(list.size()));
    }

    /**
     * Pick a solid, walkable spot in a ring around the fire (never the fire block itself).
     */
    @Nullable
    private BlockPos pickSafeCampApproachPos(BlockPos camp) {
        for (int tries = 0; tries < 12; tries++) {
            double minR = 2.0;                         // at least 2 blocks away from center
            double maxR = Math.max(3.0, TravellersModConfig.CAMP_RADIUS - 1.0);
            double r = minR + mob.getRNG().nextDouble() * (maxR - minR);
            double angle = mob.getRNG().nextDouble() * Math.PI * 2.0;

            double dx = camp.getX() + 0.5 + r * Math.cos(angle);
            double dz = camp.getZ() + 0.5 + r * Math.sin(angle);
            BlockPos pos = new BlockPos(dx, camp.getY(), dz);

            // absolutely no walking *to* the camp block
            if (pos.equals(camp)) continue;

            BlockPos below = pos.down();
            IBlockState belowState = mob.world.getBlockState(below);
            IBlockState atState = mob.world.getBlockState(pos);

            // solid ground below
            if (!belowState.isSideSolid(mob.world, below, EnumFacing.UP)) continue;

            // don't stand inside solid / non-replaceable or directly in fire
            if (!atState.getMaterial().isReplaceable()) continue;
            if (CampfireBlock.isLit(atState)) continue;

            // avoid standing *on top* of another traveller (reduces pushing & spin)
            if (!isSpotFreeOfCampers(pos)) continue;

            return pos;
        }

        return null;
    }

    /**
     * Small-radius check so we don't pick a spot already occupied by another camper (reduces pushing).
     */
    private boolean isSpotFreeOfCampers(BlockPos pos) {
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(0.35);
        return mob.world.getEntitiesWithinAABB(EntityTraveller.class, box,
                e -> e != null && e.isEntityAlive()).isEmpty();
    }
    private void tickSocialLook() {
        // continue an active social look if we still have a valid peer
        if (socialLookTicks > 0 && socialPeer != null && socialPeer.isEntityAlive()) {
            mob.getLookHelper().setLookPositionWithEntity(socialPeer, 30.0F, 30.0F);

            // mild body turn toward peer
            BlockPos peerPos = socialPeer.getPosition();
            mob.faceTowards(peerPos, 6.0F, 12.0F);

            socialLookTicks--;
            return;
        }

        // ~1% chance per tick while camping => roughly once per ~5 seconds
        if (mob.getRNG().nextInt(100) != 0) return;

        BlockPos camp = targetCamp;
        if (camp == null) return;

        // pick any traveller at this camp
        java.util.List<EntityTraveller> peers = mob.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                new AxisAlignedBB(camp).grow(6.0, 3.0, 6.0),
                e -> e != null && e != mob && e.isEntityAlive() && camp.equals(e.getCampfirePos())
        );
        if (peers.isEmpty()) return;

        socialPeer = peers.get(mob.getRNG().nextInt(peers.size()));
        socialLookTicks = 40 + mob.getRNG().nextInt(40); // 2–4 seconds
    }

}