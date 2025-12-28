package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
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
        if (!TravellersModConfig.travellerNoAggroOffscreen) return;
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
        if (!TravellersModConfig.travellerNoAggroOffscreen) return;
        if (traveller.world.isRemote) return;
        if (traveller.isInPlayerViewDistance()) return;

        // Full immunity from being damaged while off-screen (for this option)
        event.setCanceled(true);
    }
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityTraveller)) return;

        EntityTraveller traveller = (EntityTraveller) event.getEntityLiving();
        if (traveller.world.isRemote) return;

        DamageSource src = event.getSource();
        Entity trueSrc = src.getTrueSource();
        boolean isPlayerAttack = trueSrc instanceof EntityPlayer;

        // 1) Full off-screen immunity for non-player damage
        if (TravellersModConfig.travellerNoAggroOffscreen
                && !traveller.isInPlayerViewDistance()
                && !isPlayerAttack) {

            event.setCanceled(true);
            return;
        }

        float amount = event.getAmount();

        // 2) Off-screen reduction (non-player only)
        if (!isPlayerAttack
                && !traveller.isInPlayerViewDistance()) {

            float offscreenScale = TravellersModConfig.travellerOffscreenDamageScale;
            if (offscreenScale < 1F) {
                amount *= offscreenScale;
            }
        }

        // 3) Global travellerDamageScale (non-player only)
        if (!isPlayerAttack) {
            float globalScale = TravellersModConfig.travellerDamageScale;
            if (globalScale != 1.0F) {
                amount *= globalScale;
            }
        }

        event.setAmount(amount);
    }



}
