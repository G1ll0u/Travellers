package com.jubitus.travellers.combat.ai;

import com.jubitus.travellers.combat.Hostility;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.combat.TravellerHostilityHelper;
import com.jubitus.travellers.combat.lists.EntityRuleList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;
import java.util.UUID;
import com.jubitus.travellers.combat.AttackTargetLimiter;
import com.jubitus.travellers.pathing.PathBudget;

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
                mob.getAggroBlacklist());
    }

    // Full control ctor
    public EntityAIDefendIfClose(EntityTraveller mob,
                                 double triggerRange,
                                 boolean requireLOS,
                                 int tickRate,
                                 int tickPhase,
                                 EntityRuleList blacklist) {
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

    // Optional convenience: (mob, range, requireLOS, tickRate)
    public EntityAIDefendIfClose(EntityTraveller mob, double triggerRange, boolean requireLOS, int tickRate) {
        this(mob,
                triggerRange,
                requireLOS,
                tickRate,
                mob.getRNG().nextInt(Math.max(3, tickRate)),
                mob.getAggroBlacklist());
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
        if (TravellersDerivedConfig.isPeacefulBlocked(mob.world)) return false;
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
        if (!PathBudget.tryAcquire(mob.world, PathBudget.Client.COMBAT_SCAN, mob.getEntityId(), scanPriority)) return false;

        double effRangeSq = currentTriggerRangeSq();
        double effRange = Math.sqrt(effRangeSq);

        AxisAlignedBB aabb;
        // Only set for a hired follower with a live owner - used below to widen the vertical
        // cave filter for anything already actively attacking that owner (e.g. an archer on a
        // wall/tower well above ground, common around AW2 towns - a tight vertical band is
        // right for filtering out unrelated stuff in an adjacent cave, wrong for an attacker
        // that's already engaged).
        EntityPlayer scanOwner = null;

        if (mob instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) mob;
            if (w.isHiredFollower()) {
                EntityPlayer owner = w.getFollowOwner();
                if (owner != null && owner.isEntityAlive()) {
                    scanOwner = owner;
                    // Union of a box around the owner AND a box around this worker's own
                    // position - the owner-only box used to be the whole thing, which meant a
                    // worker standing far from the owner (routine in a large spread-out
                    // formation) had zero defensive awareness of its own surroundings: a threat
                    // attacking that worker directly, or standing right next to it, was only
                    // ever noticed once it also got within range of the owner. Only the one
                    // worker actually hit (via its own hurt-by-target reaction) would react;
                    // every other nearby worker in the same formation stayed idle.
                    AxisAlignedBB obb = owner.getEntityBoundingBox();
                    AxisAlignedBB ownerBox = new AxisAlignedBB(
                            obb.minX - effRange, obb.minY - effRange, obb.minZ - effRange,
                            obb.maxX + effRange, obb.maxY + effRange, obb.maxZ + effRange
                    );
                    AxisAlignedBB selfBox = mob.getEntityBoundingBox().grow(effRange, effRange, effRange);
                    aabb = ownerBox.union(selfBox);
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

        // Players a nearby faction-ally is already actively fighting - the only way this
        // proactive scan may ever pick up a player, since an unprovoked player standing nearby
        // must never be attacked. TravellerHurtByTarget's one-shot call-for-help handles the
        // very first hit; without this, that single burst is the ONLY chance the rest of a
        // group ever gets to join a fight against a player (unlike mobs/raiders, which this
        // scan already re-notices on every cycle below) - a formation straggler just out of
        // helpRange at that exact instant, or a slow follower, never gets a second chance.
        // Plain travellers/raiders only: isAggressiveToTravellers() alone isn't ownership, and a
        // hired worker must never be pulled into a fight against a player its own owner hasn't
        // declared war on just because some OTHER player's worker is fighting them nearby -
        // workers have their own dedicated, owner-aware group escalation (PlayerHostility.rallyGroupAgainst).
        java.util.Set<UUID> hostilePlayers = null;
        if (!(mob instanceof EntityTravellerWorker)) {
            for (EntityTraveller a : mob.world.getEntitiesWithinAABB(EntityTraveller.class, aabb,
                    a -> a != null && a.isEntityAlive() && a != mob && !(a instanceof EntityTravellerWorker)
                            && !Hostility.isCrossFaction(a, mob))) {
                EntityLivingBase t = a.getAttackTarget();
                if (t instanceof EntityPlayer) {
                    if (hostilePlayers == null) hostilePlayers = new java.util.HashSet<>();
                    hostilePlayers.add(t.getUniqueID());
                }
            }
        }
        final java.util.Set<UUID> alliesFighting = hostilePlayers;

        List<EntityLivingBase> list = mob.world.getEntitiesWithinAABB(
                EntityLivingBase.class, aabb,
                e -> {
                    if (e == null || !e.isEntityAlive()) return false;
                    boolean included = e instanceof net.minecraft.entity.monster.IMob
                            // NEW: LavaCow IAggressive via reflection
                            || TravellerHostilityHelper.isLavaCowAggressive(e)
                            // cross-faction travellers
                            || (e instanceof EntityTraveller
                            && Hostility.isCrossFaction((EntityTraveller) e, mob))
                            // a player a faction-ally is already fighting
                            || (e instanceof EntityPlayer && alliesFighting != null && alliesFighting.contains(e.getUniqueID()))
                            // your custom extra config
                            || mob.shouldAttackExtraEntity(e);
                    if (TravellersConfig.COMPAT.ancientWarfare2DebugLog
                            && com.jubitus.travellers.combat.compat.AncientWarfare2Compat.isFactionNpc(e)) {
                        net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                                "[Travellers/AW2] %s AABB predicate for %s#%s = %b (in scan box)",
                                mob.getName(), e.getName(), e.getUniqueID(), included);
                    }
                    return included;
                }
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
        EntityRuleList bl = mob.getAggroBlacklist();

        for (EntityLivingBase e : list) {
            // AW2 diagnostic trace - only for AW2 faction NPC candidates, logs why each one was
            // accepted/rejected by this scan. TravellersConfig.COMPAT.ancientWarfare2DebugLog.
            boolean awDebug = TravellersConfig.COMPAT.ancientWarfare2DebugLog
                    && com.jubitus.travellers.combat.compat.AncientWarfare2Compat.isFactionNpc(e);
            if (awDebug) {
                net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                        "[Travellers/AW2] %s scanning candidate %s#%s (in raw list)",
                        mob.getName(), e.getName(), e.getUniqueID());
            }

            if (bl != null && bl.matches(e)) {
                if (awDebug) logAwSkip(mob, e, "blacklisted");
                continue;
            }
            if (e.isInvisible()) {
                if (awDebug) logAwSkip(mob, e, "invisible");
                continue;
            }

            double d2 = mob.getDistanceSq(e);
            boolean see = mob.getEntitySenses().canSee(e);

            // Is this entity already actively attacking me or my owner? Never filtered out by
            // the vertical cave check below - see scanOwner's comment above.
            net.minecraft.entity.EntityLivingBase currentTarget =
                    (e instanceof net.minecraft.entity.EntityLiving) ? ((net.minecraft.entity.EntityLiving) e).getAttackTarget() : null;
            boolean threatening = currentTarget == mob || e.getRevengeTarget() == mob
                    || (scanOwner != null && (currentTarget == scanOwner || e.getRevengeTarget() == scanOwner));

            // Ignore large vertical separations (caves below/above)
            double dy = e.posY - mob.posY;
            if (!threatening && Math.abs(dy) > 5.0D) {
                if (awDebug) logAwSkip(mob, e, "vertical separation dy=" + dy);
                continue;
            }

            if (requireLOS && !see) {
                if (d2 <= CLOSE_NO_LOS_RANGE_SQ) {
                    if (!hasShortPathTo(e)) {
                        if (awDebug) logAwSkip(mob, e, "no LOS, no short path (close range)");
                        continue;
                    }
                } else {
                    if (awDebug) logAwSkip(mob, e, "no LOS, too far for close-range path check, d2=" + d2);
                    continue;
                }
            }

            boolean emergency = mob.shouldBypassAttackTargetLimiter(e);

            // Skip targets the worker already tried and was blocked on — unless it's an emergency
            if (!emergency && mob.isIgnoring(e)) {
                if (awDebug) logAwSkip(mob, e, "isIgnoring (previously blocked/backed off)");
                continue;
            }
            if (awDebug) {
                net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                        "[Travellers/AW2] %s candidate %s#%s passed all filters (emergency=%b, d2=%.1f)",
                        mob.getName(), e.getName(), e.getUniqueID(), emergency, d2);
            }

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
        if (TravellersConfig.COMPAT.ancientWarfare2DebugLog
                && com.jubitus.travellers.combat.compat.AncientWarfare2Compat.isFactionNpc(pick)) {
            net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                    "[Travellers/AW2] %s picked %s#%s as pending target", mob.getName(), pick.getName(), pick.getUniqueID());
        }
        return true;
    }

    private static void logAwSkip(EntityTraveller mob, EntityLivingBase e, String reason) {
        net.minecraftforge.fml.common.FMLLog.log(org.apache.logging.log4j.Level.INFO,
                "[Travellers/AW2] %s rejected candidate %s#%s: %s", mob.getName(), e.getName(), e.getUniqueID(), reason);
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