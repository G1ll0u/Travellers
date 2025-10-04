package com.jubitus.traveller.traveller.utils.mobs;

import com.jubitus.traveller.TravellersModConfig;
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
        if (!isBlacklistMob(mob)) return;

        EntityLivingBase newTarget = e.getTarget();
        if (isTraveller(newTarget)) {
            EntityLiving el = (EntityLiving) mob;
            el.setAttackTarget(null);
            el.setRevengeTarget(null);
            // also wipe any "last attacked" refs so AI like HurtByTarget has nothing to chase
            el.setLastAttackedEntity(null);
        }
    }

    // Helper: is this mob type blacklisted to ignore Travellers?
    private static boolean isBlacklistMob(EntityLivingBase mob) {
        return mob instanceof net.minecraft.entity.monster.IMob
                && TravellersModConfig.TRAVELLER_BLACKLIST.isBlacklisted(mob);
    }

    private static boolean isTraveller(@Nullable Entity entity) {
        return entity instanceof com.jubitus.traveller.traveller.entityAI.EntityTraveller;
    }

    /**
     * 2) When the mob is hurt by a Traveller, prevent “revenge” aggro.
     */
    @SubscribeEvent
    public static void onHurt(net.minecraftforge.event.entity.living.LivingHurtEvent e) {
        EntityLivingBase victim = e.getEntityLiving();
        if (!(victim instanceof EntityLiving)) return;
        if (!isBlacklistMob(victim)) return;

        Entity trueSrc = e.getSource().getTrueSource();
        if (isTraveller(trueSrc)) {
            EntityLiving el = (EntityLiving) victim;
            el.setRevengeTarget(null);
            el.setLastAttackedEntity(null);
            // Some mobs cache path/aggro; clearing attack target again is harmless
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
        if (!isBlacklistMob(el)) return;

        if (isTraveller(el.getAttackTarget()) || isTraveller(el.getRevengeTarget())) {
            el.setAttackTarget(null);
            el.setRevengeTarget(null);
            el.setLastAttackedEntity(null);
        }
    }
}

