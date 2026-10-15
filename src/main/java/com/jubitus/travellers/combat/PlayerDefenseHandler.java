package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = "travellers")
public final class PlayerDefenseHandler {

    private static final double ASSIST_RADIUS_H = 20.0; // horizontal range (blocks)
    private static final double ASSIST_RADIUS_V = 8.0;  // vertical tolerance

    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote) return; // server only
        if (TravellersDerivedConfig.isPeacefulBlocked(player.world)) return;

        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityLivingBase)) return;   // ignore non-entity damage
        if (src instanceof EntityPlayer || src instanceof EntityTraveller) return; // players and units are the war/hostility systems' business
        if (src instanceof net.minecraft.entity.passive.EntityTameable
                && player.getUniqueID().equals(((net.minecraft.entity.passive.EntityTameable) src).getOwnerId())) return;

        EntityLivingBase attacker = (EntityLivingBase) src;

        rallyAgainst(player, attacker);
    }

    @SubscribeEvent
    public static void onPlayerHurtsMob(LivingHurtEvent event) {
        EntityLivingBase victim = event.getEntityLiving();
        if (!(victim instanceof IMob)) return; // only defend you vs hostiles
        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) src;
        if (player.world.isRemote) return;
        if (TravellersDerivedConfig.isPeacefulBlocked(player.world)) return;

        rallyAgainst(player, victim);
    }

    // Friendly travellers in sight join the player's fight, through the same limiter and filters as their own scans.
    private static void rallyAgainst(EntityPlayer player, EntityLivingBase mob) {
        AxisAlignedBB box = new AxisAlignedBB(
                player.posX - ASSIST_RADIUS_H, player.posY - ASSIST_RADIUS_V, player.posZ - ASSIST_RADIUS_H,
                player.posX + ASSIST_RADIUS_H, player.posY + ASSIST_RADIUS_V, player.posZ + ASSIST_RADIUS_H
        );

        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class, box, Entity::isEntityAlive)) {
            if (t.isAggressiveToTravellers()) continue;
            if (t.isCampingPassive() || t.sleep.isSleeping()) continue;
            if (t.getAttackTarget() == mob) continue;
            if (t.getAggroBlacklist().matches(mob)) continue;
            if (!t.getEntitySenses().canSee(mob)) continue;
            if (t.trySetAttackTargetLimited(mob)) {
                t.rememberThreat(mob, TravellersConfig.COMBAT.threatForSeeAttack);
            }
        }
    }

    @SubscribeEvent
    public static void onAnyPlayerHurts(LivingHurtEvent event) {
        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) src;
        if (player.world.isRemote) return;
        if (player.isCreative() || player.isSpectator()) return;

        // Notify nearby travellers that this player was aggressive "just now"
        double r = TravellersConfig.PLAYER_AGGRO.playerCalmNotifyRadius;
        AxisAlignedBB box = new AxisAlignedBB(
                player.posX - r, player.posY - 8, player.posZ - r,
                player.posX + r, player.posY + 8, player.posZ + r
        );

        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class, box, Entity::isEntityAlive)) {
            t.markPlayerRecentAggro(player);
        }
    }
}


