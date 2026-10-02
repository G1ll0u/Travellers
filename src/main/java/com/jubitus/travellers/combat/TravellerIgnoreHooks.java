package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.combat.lists.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber
public class TravellerIgnoreHooks {

    /**
     * 0) Cancel damage entirely when a blacklisted mob (e.g. Creeper) is attacked by a Traveller.
     * LivingAttackEvent fires before any processing — cancelling it prevents vanilla from setting
     * revengeTarget, which would otherwise survive the onHurt/onUpdate clearing below.
     */
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        EntityLivingBase victim = e.getEntityLiving();
        if (!(victim instanceof EntityLiving)) return;

        Entity src = e.getSource().getTrueSource();
        if (isTraveller(src) && isBlacklistedFor(victim, src)) {
            e.setCanceled(true);
        }
    }

    /**
     * 1) If anything tries to set a target to a Traveller, clear it immediately.
     */
    @SubscribeEvent
    public static void onSetAttackTarget(net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent e) {
        EntityLivingBase mob = e.getEntityLiving();
        if (!(mob instanceof EntityLiving)) return;

        EntityLivingBase newTarget = e.getTarget();
        if (isTraveller(newTarget) && isBlacklistedFor(mob, newTarget)) {
            EntityLiving el = (EntityLiving) mob;
            el.setAttackTarget(null);
            el.setRevengeTarget(null);
            el.setLastAttackedEntity(null);
        }
    }

    private static boolean isTraveller(@Nullable Entity entity) {
        return entity instanceof EntityTraveller;
    }

    private static boolean isBlacklistedFor(EntityLivingBase mob, @Nullable Entity target) {
        if (!(mob instanceof net.minecraft.entity.monster.IMob)) return false;
        if (!(target instanceof EntityTraveller)) return false;

        boolean raider = target instanceof EntityTravellerRaider;
        TravellerBlacklist list = raider
                ? TravellersDerivedConfig.RAIDER_AGGRO_BLACKLIST
                : TravellersDerivedConfig.TRAVELLER_AGGRO_BLACKLIST;
        return list.isBlacklisted(mob);
    }

    /**
     * 2) When the mob is hurt by a Traveller, prevent “revenge” aggro.
     */
    @SubscribeEvent
    public static void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent e) {
        EntityLivingBase victim = e.getEntityLiving();
        if (!(victim instanceof EntityLiving)) return;

        Entity src = e.getSource().getTrueSource();
        if (isTraveller(src) && isBlacklistedFor(victim, src)) {
            EntityLiving el = (EntityLiving) victim;
            el.setRevengeTarget(null);
            el.setLastAttackedEntity(null);
            el.setAttackTarget(null);
        }
    }

    /**
     * (Optional) 3) Belt-and-suspenders: periodically scrub any Traveller target.
     */
    @SubscribeEvent
    public static void onUpdate(net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent e) {
        if (!(e.getEntityLiving() instanceof EntityLiving)) return;
        EntityLiving el = (EntityLiving) e.getEntityLiving();

        EntityLivingBase atk = el.getAttackTarget();
        if (isTraveller(atk) && isBlacklistedFor(el, atk)) {
            el.setAttackTarget(null);
            el.setRevengeTarget(null);
            el.setLastAttackedEntity(null);
        }
        EntityLivingBase rev = el.getRevengeTarget();
        if (isTraveller(rev) && isBlacklistedFor(el, rev)) {
            el.setAttackTarget(null);
            el.setRevengeTarget(null);
            el.setLastAttackedEntity(null);
        }
    }

}

