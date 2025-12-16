package com.jubitus.travellers.utils.mobs;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.utils.mobs.lists.TravellerBlacklist;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;

@Mod.EventBusSubscriber
public class TravellerIgnoreHooks {

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
                ? TravellersModConfig.RAIDER_AGGRO_BLACKLIST
                : TravellersModConfig.TRAVELLER_AGGRO_BLACKLIST;
        return list.isBlacklisted(mob);
    }

    // Helper: is this mob type blacklisted to ignore Travellers?
    private static boolean isBlacklistMob(EntityLivingBase mob) {
        return mob instanceof net.minecraft.entity.monster.IMob
                && TravellersModConfig.TRAVELLER_BLACKLIST.isBlacklisted(mob);
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

