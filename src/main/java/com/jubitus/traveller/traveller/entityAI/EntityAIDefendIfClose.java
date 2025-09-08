package com.jubitus.traveller.traveller.entityAI;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.utils.TravellerBlacklist;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;

import java.util.List;

/** Traveller will defensively aggro the nearest non-blacklisted IMob that gets close. */
public class EntityAIDefendIfClose extends net.minecraft.entity.ai.EntityAIBase {
    private final EntityTraveller mob;
    private final double triggerRangeSq;
    private final boolean requireLOS;
    private final int tickRate;

    // Config/blacklist (pull from your config singletons)
    private final TravellerBlacklist blacklist;

    private EntityLivingBase pending; // we only hold it until startExecuting()

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

    @Override
    public boolean shouldExecute() {
        if (mob.world.isRemote) return false;
        if (mob.world.getDifficulty() == EnumDifficulty.PEACEFUL) return false;
        if (mob.isAutoEating()) return false;
        if (mob.ticksExisted % tickRate != 0) return false;

        // If we already have a valid target, DO NOT override it — keep fighting it.
        EntityLivingBase current = mob.getAttackTarget();
        if (current != null && current.isEntityAlive()) return false;

        // Scan for nearby hostiles
        AxisAlignedBB aabb = mob.getEntityBoundingBox().grow(Math.sqrt(triggerRangeSq), 2.0, Math.sqrt(triggerRangeSq));
        List<EntityLivingBase> list = mob.world.getEntitiesWithinAABB(
                EntityLivingBase.class, aabb,
                e -> e instanceof net.minecraft.entity.monster.IMob && e.isEntityAlive()
        );
        if (list.isEmpty()) return false;

        EntityLivingBase closest = null;
        double best = Double.POSITIVE_INFINITY;

        for (EntityLivingBase e : list) {
            // Respect blacklist: skip anything your rules block
            if (blacklist != null && blacklist.isBlacklisted(e)) continue;

            if (requireLOS && !mob.getEntitySenses().canSee(e)) continue;

            double d = mob.getDistanceSq(e);
            if (d <= triggerRangeSq && d < best) {
                best = d;
                closest = e;
            }
        }

        if (closest == null) return false;

        this.pending = closest;
        return true;
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

            // Sticky: also set revenge so other vanilla hooks keep memory
            mob.setRevengeTarget(pending);
        }
        pending = null;
    }

    @Override
    public void resetTask() {
        pending = null;
        // Do NOT clear mob's attack target here — we want them to keep fighting once engaged.
    }
}