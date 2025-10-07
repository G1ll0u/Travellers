package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.utils.mobs.TravellerBlacklist;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Traveller will defensively aggro the nearest non-blacklisted IMob that gets close.
 */
public class EntityAIDefendIfClose extends net.minecraft.entity.ai.EntityAIBase {
    private final EntityTraveller mob;
    private final boolean requireLOS;
    private final int tickRate;
    // Config/blacklist (pull from your config singletons)
    private final TravellerBlacklist blacklist;
    private double triggerRangeSq;
    private EntityLivingBase pending; // we only hold it until startExecuting()
    private double bowRangeBonusFrac = 0.30; // +30% default

    @Nullable
    public EntityAIDefendIfClose(EntityTraveller mob, double triggerRange, boolean requireLOS) {
        this(mob, triggerRange, requireLOS, 8);
    }

    public EntityAIDefendIfClose(EntityTraveller mob, double triggerRange, boolean requireLOS, int tickRate) {
        this.mob = mob;
        this.triggerRangeSq = triggerRange * triggerRange;
        this.requireLOS = requireLOS;
        this.tickRate = Math.max(3, tickRate);
        this.setMutexBits(0);
        // Get the same blacklist you use in MobTargetInjector
        this.blacklist = TravellersModConfig.TRAVELLER_BLACKLIST;
    }

    public EntityAIDefendIfClose setAssistRadius(double r) {
        this.triggerRangeSq = r * r;
        return this;
    }

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.world.getDifficulty() == EnumDifficulty.PEACEFUL) return false;
        if (mob.isAutoEating()) return false;
        if (mob.ticksExisted % tickRate != 0) return false;

        // Keep any existing valid target
        EntityLivingBase current = mob.getAttackTarget();
        if (current != null && current.isEntityAlive()) return false;

        // Use effective (possibly extended) radius
        double effRangeSq = currentTriggerRangeSq();
        double effRange = Math.sqrt(effRangeSq);

        AxisAlignedBB aabb = mob.getEntityBoundingBox().grow(effRange, 2.0, effRange);
        List<EntityLivingBase> list = mob.world.getEntitiesWithinAABB(
                EntityLivingBase.class, aabb,
                e -> e instanceof net.minecraft.entity.monster.IMob && e.isEntityAlive()
        );
        if (list.isEmpty()) return false;

        EntityLivingBase closest = null;
        double best = Double.POSITIVE_INFINITY;

        for (EntityLivingBase e : list) {
            if (blacklist != null && blacklist.isBlacklisted(e)) continue;
            if (requireLOS && !mob.getEntitySenses().canSee(e)) continue;

            double d = mob.getDistanceSq(e);
            if (d <= effRangeSq && d < best) {
                best = d;
                closest = e;
            }
        }

        if (closest == null) return false;
        this.pending = closest;
        return true;
    }

    private double currentTriggerRangeSq() {
        boolean hasBow =
                mob.hasBowInBackpackPublic() ||
                        (!mob.getHeldItemMainhand().isEmpty() &&
                                mob.getHeldItemMainhand().getItem() instanceof net.minecraft.item.ItemBow);

        // start from the configured trigger range (already squared)
        double effRangeSq = triggerRangeSq;

        if (hasBow && bowRangeBonusFrac > 0.0) {
            // convert to linear, apply +X%, then square again
            double baseLin = Math.sqrt(effRangeSq);
            double boostedLin = baseLin * (1.0 + bowRangeBonusFrac);
            effRangeSq = boostedLin * boostedLin;
        }

        return effRangeSq;
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