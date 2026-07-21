package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.mobs.TravellerHostilityHelper;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;

import java.util.List;
import java.util.UUID;

/**
 * Traveller will defensively aggro the nearest non-blacklisted IMob that gets close.
 */
public class EntityAIDefendIfClose extends EntityAIBase {
    private static final double CLOSE_NO_LOS_RANGE = 4.0D;
    private static final double CLOSE_NO_LOS_RANGE_SQ = CLOSE_NO_LOS_RANGE * CLOSE_NO_LOS_RANGE;
    private final EntityTraveller mob;
    private final boolean requireLOS;
    private final int tickRate;
    private final int tickPhase;
    private boolean pendingEmergency = false;
    private double triggerRangeSq;
    private double closeStartSq;
    private double closeHoldSq;
    private double baseTriggerRange;
    private double baseTriggerRangeSq;
    private EntityLivingBase pending;
    private double bowRangeBonusFrac = 0.8;

    // OLD SIGNATURE – used at call site
    public EntityAIDefendIfClose(EntityTraveller mob, double triggerRange, boolean requireLOS) {
        // default tickRate = 8, random phase [0,8)
        this(mob,
                triggerRange,
                requireLOS,
                8,
                mob.getRNG().nextInt(8),
                defaultAggroList(mob));
    }

    // Full control ctor
    public EntityAIDefendIfClose(EntityTraveller mob,
                                 double triggerRange,
                                 boolean requireLOS,
                                 int tickRate,
                                 int tickPhase,
                                 TravellerBlacklist blacklist) {
        this.mob = mob;
        this.requireLOS = requireLOS;
        this.tickRate = Math.max(3, tickRate);
        this.tickPhase = tickPhase;
        // Shared mutex bit 1 (vanilla convention for target AIs, see EntityAITarget/
        // EntityAINearestAttackableTarget) so only one target-selector task per entity can
        // actually claim a target the same tick - prevents two independent scans from racing
        // and silently overwriting each other's setAttackTarget() call mid-fight.
        this.setMutexBits(1);

        this.baseTriggerRange = triggerRange;                // linear
        this.baseTriggerRangeSq = triggerRange * triggerRange; // squared
        this.triggerRangeSq = baseTriggerRangeSq;

        // proximity bands (≈65% to start, 75% to hold)
        double start = baseTriggerRange * 0.65;
        double hold = baseTriggerRange * 0.75;
        this.closeStartSq = start * start;
        this.closeHoldSq = hold * hold;
    }

    private static TravellerBlacklist defaultAggroList(EntityTraveller mob) {
        return (mob instanceof EntityTravellerRaider)
                ? TravellersDerivedConfig.RAIDER_AGGRO_BLACKLIST
                : TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
    }

    // Optional convenience: (mob, range, requireLOS, tickRate)
    public EntityAIDefendIfClose(EntityTraveller mob, double triggerRange, boolean requireLOS, int tickRate) {
        this(mob,
                triggerRange,
                requireLOS,
                tickRate,
                mob.getRNG().nextInt(Math.max(3, tickRate)),
                defaultAggroList(mob));
    }

    public EntityAIDefendIfClose setAssistRadius(double r) {
        this.baseTriggerRange = r;
        this.baseTriggerRangeSq = r * r;
        this.triggerRangeSq = baseTriggerRangeSq;

        double start = baseTriggerRange * 0.65;
        double hold = baseTriggerRange * 0.75;
        this.closeStartSq = start * start;
        this.closeHoldSq = hold * hold;
        return this;
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.world.getDifficulty() == EnumDifficulty.PEACEFUL) return false;
        if (mob.isAutoEating()) return false;
// staggered (dynamic scan rate)
        int effectiveTickRate = tickRate;

// Pick whatever “recently in combat” signal you actually have.
// Examples that usually exist:
// - mob.inCombat (your comment suggests you have this)
// - mob.getRevengeTarget() != null
// - mob.getLastAttackedEntity() != null (depends on your codebase)
        if (mob.isInCombat() /* || mob.getRevengeTarget() != null */) {
            effectiveTickRate = 3; // scan faster during/just after combat
        }

        if (!mob.isRetargetBoosted() && (mob.ticksExisted + tickPhase) % effectiveTickRate != 0) return false;


        // keep any existing target
        EntityLivingBase current = mob.getAttackTarget();
        if (current != null && current.isEntityAlive()) return false;

        // Budget the expensive AABB scan below world-wide so mass battles can't spike CPU.
        // Boosted (just lost a target) scans get slight priority so post-kill retargeting
        // doesn't starve behind idle scans.
        int scanPriority = mob.isRetargetBoosted() ? 2 : 0;
        if (!CombatTargetScanScheduler.tryAcquire(mob.world, mob.getEntityId(), scanPriority)) return false;

        double effRangeSq = currentTriggerRangeSq();
        double effRange = Math.sqrt(effRangeSq);

        AxisAlignedBB aabb;

        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;
            if (w.isHiredFollower()) {
                EntityPlayer owner = w.getFollowOwner();
                if (owner != null && owner.isEntityAlive()) {
                    AxisAlignedBB obb = owner.getEntityBoundingBox();
                    aabb = new AxisAlignedBB(
                            obb.minX - effRange, obb.minY - 1.5, obb.minZ - effRange,
                            obb.maxX + effRange, obb.maxY + 1.5, obb.maxZ + effRange
                    );
                } else {
                    // fallback: use the worker
                    AxisAlignedBB bb = mob.getEntityBoundingBox();
                    aabb = new AxisAlignedBB(
                            bb.minX - effRange, bb.minY - 1.5, bb.minZ - effRange,
                            bb.maxX + effRange, bb.maxY + 1.5, bb.maxZ + effRange
                    );
                }
            } else {
                // normal, non-follower behaviour
                AxisAlignedBB bb = mob.getEntityBoundingBox();
                aabb = new AxisAlignedBB(
                        bb.minX - effRange, bb.minY - 1.5, bb.minZ - effRange,
                        bb.maxX + effRange, bb.maxY + 1.5, bb.maxZ + effRange
                );
            }
        } else {
            AxisAlignedBB bb = mob.getEntityBoundingBox();
            aabb = new AxisAlignedBB(
                    bb.minX - effRange, bb.minY - 1.5, bb.minZ - effRange,
                    bb.maxX + effRange, bb.maxY + 1.5, bb.maxZ + effRange
            );
        }

        List<EntityLivingBase> list = mob.world.getEntitiesWithinAABB(
                EntityLivingBase.class, aabb,
                e -> e != null && e.isEntityAlive() && (
                        e instanceof net.minecraft.entity.monster.IMob
                                // NEW: LavaCow IAggressive via reflection
                                || TravellerHostilityHelper.isLavaCowAggressive(e)
                                // cross-faction travellers
                                || (e instanceof EntityTraveller
                                && (((EntityTraveller) e).isAggressiveToTravellers() ^ mob.isAggressiveToTravellers()))
                                // your custom extra config
                                || mob.shouldAttackExtraEntity(e)
                )
        );
        if (list.isEmpty()) return false;

        // In mass combat the list can contain hundreds of entities. Sort by distance (cheap)
        // and cap at 12 candidates before doing expensive LOS raycasts.
        if (list.size() > 12) {
            list.sort((a, b) -> Double.compare(mob.getDistanceSq(a), mob.getDistanceSq(b)));
            list = list.subList(0, 12);
        }

        boolean bowUser = mob.hasBowAnywherePublic();
        final double crowdRadius = 3.5D;

        EntityLivingBase pick = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean bestWasEmergency = false;
        TravellerBlacklist bl = currentBlacklist();

        for (EntityLivingBase e : list) {
            if (bl != null && bl.isBlacklisted(e)) continue;
            if (e.isInvisible()) continue;

            double d2 = mob.getDistanceSq(e);
            boolean see = mob.getEntitySenses().canSee(e);

            // Ignore large vertical separations (caves below/above)
            double dy = e.posY - mob.posY;
            if (Math.abs(dy) > 5.0D) continue;

            if (requireLOS && !see) {
                if (d2 <= CLOSE_NO_LOS_RANGE_SQ) {
                    if (!hasShortPathTo(e)) continue;
                } else {
                    continue;
                }
            }

            boolean emergency = mob.shouldBypassAttackTargetLimiter(e);

            // Skip targets the worker already tried and was blocked on — unless it's an emergency
            if (!emergency && mob.isIgnoring(e)) continue;

            if (emergency) {
                if (!bestWasEmergency || d2 < bestScore) {
                    pick = e;
                    bestScore = d2;
                    bestWasEmergency = true;
                    if (d2 <= 1.0D) break;
                }
                continue;
            }
            if (bestWasEmergency) continue;

            // Prefer less-contested targets: bow users avoid friendly crowding, all workers avoid full buckets
            int attackerCount = AttackTargetLimiter.getAttackerCount(e);
            int crowd = bowUser ? mob.rangedCrowdScoreAround(e, crowdRadius) : 0;
            double penalty = crowd * 4.0D + attackerCount * 2.5D;
            double score = d2 + (penalty * penalty);

            if (score < bestScore) {
                bestScore = score;
                pick = e;
                if (d2 <= (0.25 * closeStartSq)) break;
            }
        }


        if (pick == null) return false;
        this.pending = pick;
        this.pendingEmergency = mob.shouldBypassAttackTargetLimiter(pick);
        return true;
    }

    private double currentTriggerRangeSq() {
        // Low health: emergency-only range, entity focuses on healing
        if (mob.isLowHealth()) {
            double r = TravellersConfig.COMBAT.lowHealthEngageRadius;
            return r * r;
        }

        if (mob.isCampingPassive()) {
            double r = TravellersConfig.COMBAT.anchorModeEngageRadius;
            return r * r;
        }

        // Workers in anchor/stay mode: tight defensive perimeter only
        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;
            if (w.isHiredFollower() && !w.isFollowOrderActive()) {
                double r = TravellersConfig.COMBAT.anchorModeEngageRadius;
                return r * r;
            }
            // Followers of an idle/anchored leader inherit the same reduced range
            UUID leaderUUID = w.getEscortLeaderUUID();
            if (leaderUUID != null) {
                net.minecraft.entity.Entity leaderEntity = ((net.minecraft.world.WorldServer) w.world).getEntityFromUuid(leaderUUID);
                if (leaderEntity instanceof EntityTravellerWorker) {
                    EntityTravellerWorker leader = (EntityTravellerWorker) leaderEntity;
                    if (!leader.isFollowOrderActive()) {
                        double r = TravellersConfig.COMBAT.anchorModeEngageRadius;
                        return r * r;
                    }
                }
            }
        }

        boolean hasBow =
                mob.hasBowInBackpackPublic() ||
                        (!mob.getHeldItemMainhand().isEmpty() &&
                                mob.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemBow);

        double rangeLin = baseTriggerRange;
        if (hasBow && bowRangeBonusFrac > 0.0) {
            rangeLin = rangeLin * (1.0 + bowRangeBonusFrac);
        }
        return rangeLin * rangeLin;
    }

    private TravellerBlacklist currentBlacklist() {
        return (mob instanceof EntityTravellerRaider)
                ? TravellersDerivedConfig.RAIDER_AGGRO_BLACKLIST
                : TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
    }

    // --- helpers ---
// “Not behind walls” substitute when no LOS: require a real path.
// This lets them react around corners but not through sealed walls.
    private boolean hasShortPathTo(EntityLivingBase e) {
        if (mob.getNavigator() == null) return false;

        Path path = mob.getPathToEntityThrottled(e, 2); // urgent
        if (path == null) return false;

        // Optional: reject long detours (prevents “pathing around the mountain”)
        // Tune this; 16–24 is usually fine for an 8-block “awareness” feel.
        if (path.getCurrentPathLength() > 20) return false;

        // Optional: ensure the path actually ends near the target
        PathPoint end = path.getFinalPathPoint();
        if (end == null) return false;

        double dx = (end.x + 0.5D) - e.posX;
        double dy = (end.y) - e.posY;
        double dz = (end.z + 0.5D) - e.posZ;
        return (dx * dx + dy * dy + dz * dz) <= 2.25D; // within ~1.5 blocks of entity
    }

    @Override
    public boolean shouldContinueExecuting() {
        // This target-selector AI only hands off a target once.
        // We let the combat/attack AIs control continuation.
        return false;
    }

    @Override
    public void startExecuting() {
        if (pending != null && pending.isEntityAlive()) {
            // Emergency bypass is handled inside trySetAttackTargetLimited()
            mob.trySetAttackTargetLimited(pending, pendingEmergency ? 2 : 0);
        }
        pending = null;
        pendingEmergency = false;
    }

    @Override
    public void resetTask() {
        pending = null;
        // Do NOT clear mob's attack target here — we want them to keep fighting once engaged.
    }

    public EntityAIDefendIfClose setBowRangeBonus(double frac) {
        this.bowRangeBonusFrac = Math.max(0.0, frac);
        return this;
    }

}