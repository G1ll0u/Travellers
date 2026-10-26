package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber
public class TravellerOffscreenHandler {

    @SubscribeEvent
    public static void onSetAttackTarget(LivingSetAttackTargetEvent event) {
        EntityLivingBase target = event.getTarget();
        if (!(target instanceof EntityTraveller)) return;

        EntityTraveller traveller = (EntityTraveller) target;
        if (traveller.world.isRemote) return;
        if (!TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen) return;
        if (traveller.isInPlayerViewDistance()) return;

        EntityLivingBase attacker = event.getEntityLiving();
        if (attacker == null) return;

        // Let players target normally
        if (attacker instanceof EntityPlayer) return;

        if (attacker instanceof EntityLiving) {
            EntityLiving living = (EntityLiving) attacker;

            if (living.getAttackTarget() == traveller) {
                living.setAttackTarget(null);
            }

            living.getNavigator().clearPath(); // stop them walking towards travellers
        }
    }


    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntityLiving() instanceof EntityTraveller)) return;

        EntityTraveller traveller = (EntityTraveller) event.getEntityLiving();
        if (!TravellersConfig.OFFSCREEN.travellerNoAggroOffscreen) return;
        if (traveller.world.isRemote) return;
        if (traveller.isInPlayerViewDistance()) return;

        // Full immunity from being damaged while off-screen (for this option)
        event.setCanceled(true);
    }
}
