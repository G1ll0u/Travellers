package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import com.jubitus.travellers.utils.mobs.TravellerHostilityHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.pathfinding.Path;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;


import java.util.List;

/**
 * Traveller will defensively aggro the nearest non-blacklisted IMob that gets close.
 */
public class EntityAIDefendIfClose extends EntityAIBase {
    private final EntityTraveller mob;
    private final boolean requireLOS;
    private final int tickRate;
    private final int tickPhase;
    private final TravellerBlacklist blacklist;

    private double triggerRangeSq;
    private double closeStartSq;
    private double closeHoldSq;

    private double baseTriggerRange;
    private double baseTriggerRangeSq;

    private EntityLivingBase pending;
    private double bowRangeBonusFrac = 0.0;

    // --- helpers ---

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
        this.blacklist = blacklist;
        this.setMutexBits(0);

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
                ? TravellersModConfig.RAIDER_AGGRO_BLACKLIST
                : TravellersModConfig.TRAVELLER_AGGRO_BLACKLIST;
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
        if ((mob.ticksExisted + tickPhase) % tickRate != 0) return false; // staggered

        // keep any existing target
        EntityLivingBase current = mob.getAttackTarget();
        if (current != null && current.isEntityAlive()) return false;

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

        EntityLivingBase pick = null;
        double best = Double.POSITIVE_INFINITY;

        for (EntityLivingBase e : list) {
            if (blacklist.isBlacklisted(e)) continue;
            if (mob.isIgnoring(e)) continue;  // <--- NEW
            if (e.isInvisible()) continue;

            double d2 = mob.getDistanceSq(e);
            boolean see = mob.getEntitySenses().canSee(e);

            // NEW: ignore big vertical separations (e.g. caves below)
            double dy = e.posY - mob.posY;
            if (Math.abs(dy) > 3.0D) { // tweak as needed (3 blocks is a good start)
                continue;
            }

            // NEW: if LOS is required but we can’t see, skip
            if (requireLOS && !see) {
                continue;
            }

//            // OPTIONAL (recommended): require a path or at least a partial path
//            if (TravellersModConfig.requirePathForAggro) {
//                Path path = mob.getNavigator().getPathToEntityLiving(e);
//                if (path == null) {
//                    // no path at all -> don’t aggro; likely behind a wall/cave
//                    continue;
//                }
//            }

            boolean inBand   = (d2 <= closeStartSq);
            boolean inNormal = (d2 <= effRangeSq);

            if (inBand || inNormal) {
                if (d2 < best) {
                    best = d2;
                    pick = e;
                    if (d2 <= (0.25 * closeStartSq)) {
                        break;
                    }
                }
            }
        }


        if (pick == null) return false;
        this.pending = pick;
        return true;
    }


    private double currentTriggerRangeSq() {
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


    @Override
    public boolean shouldContinueExecuting() {
        // This target-selector AI only hands off a target once.
        // We let the combat/attack AIs control continuation.
        return false;
    }

    @Override
    public void startExecuting() {
        if (pending != null && pending.isEntityAlive()) {
            // Hand off to normal combat — your EntityTraveller.setAttackTarget()
            // will flip inCombat + cancel eating, etc.
            mob.setAttackTarget(pending);

        }
        pending = null;
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