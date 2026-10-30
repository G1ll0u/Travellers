package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;

/**
 * How far a unit engages a target: units and players from afar, anything attacking it too; mobs,
 * villagers and listed entities only up close, and a monster only once it is near enough to notice the unit.
 */
public final class EngageRange {

    /** A fleeing short-range target is dropped this much beyond the mob range, so one step back does not end the chase. */
    private static final double SHORT_DROP_FACTOR = 1.25D;

    private EngageRange() {
    }

    /** Furthest distance at which the unit may be given or keep this target as a fresh pick. */
    public static double limit(EntityTraveller unit, Entity target) {
        return isLongRange(unit, target) ? TravellersConfig.COMBAT.unitAttackTriggerRange : TravellersConfig.COMBAT.mobAttackTriggerRange;
    }

    /** May the proactive scan pick this candidate. Cheap: runs before any sight ray. */
    public static boolean mayPick(EntityTraveller unit, Entity target) {
        double r;
        if (isLongRange(unit, target)) {
            r = TravellersConfig.COMBAT.unitAttackTriggerRange;
        } else {
            r = TravellersConfig.COMBAT.mobAttackTriggerRange;
            if (target instanceof IMob) r = Math.min(r, noticeRange(target));
        }
        return unit.getDistanceSq(target) <= r * r;
    }

    /** Distance beyond which a held target is dropped. */
    public static double dropLimit(EntityTraveller unit, EntityLivingBase target) {
        if (!isLongRange(unit, target)) return TravellersConfig.COMBAT.mobAttackTriggerRange * SHORT_DROP_FACTOR;
        double max = TravellersConfig.COMBAT.unitAttackTriggerRange;
        boolean hasBow = unit.hasBowInBackpackPublic()
                || (!unit.getHeldItemMainhand().isEmpty() && unit.getHeldItemMainhand().getItem() instanceof ItemBow);
        if (hasBow) max = Math.max(max, TravellersConfig.COMBAT.rangedMaxDist * 1.25);
        return Math.max(max, TravellersConfig.COMBAT.helpRange);
    }

    /** A mob, villager or listed entity that is not attacking the unit's side: engaged only up close. */
    public static boolean isShortRange(EntityTraveller unit, Entity target) {
        return !isLongRange(unit, target);
    }

    private static boolean isLongRange(EntityTraveller unit, Entity target) {
        return target instanceof EntityTraveller || target instanceof EntityPlayer || isAttacking(unit, target);
    }

    // Anything that hit or targets the unit (or a worker's side) is fought back at full range.
    private static boolean isAttacking(EntityTraveller unit, Entity target) {
        if (unit.getRevengeTarget() == target) return true;
        if (!(target instanceof EntityLiving)) return false;
        if (((EntityLiving) target).getAttackTarget() == unit) return true;
        return unit instanceof EntityTravellerWorker && Hostility.isAggressingOurSide((EntityTravellerWorker) unit, target);
    }

    private static double noticeRange(Entity target) {
        if (!(target instanceof EntityLivingBase)) return Double.MAX_VALUE;
        IAttributeInstance follow = ((EntityLivingBase) target).getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE);
        return follow != null ? follow.getAttributeValue() : Double.MAX_VALUE;
    }
}
