package com.jubitus.traveller.traveller.utils;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.EnumDifficulty;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = "traveller")
public final class PlayerDefenseHandler {

    private static final double ASSIST_RADIUS_H = 20.0; // horizontal range (blocks)
    private static final double ASSIST_RADIUS_V = 8.0;  // vertical tolerance

    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) event.getEntityLiving();
        if (player.world.isRemote) return; // server only
        if (player.world.getDifficulty() == EnumDifficulty.PEACEFUL) return;

        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityLivingBase)) return;   // ignore non-entityAI damage
        if (!(src instanceof IMob)) return;               // don’t aggro on players/pets, only hostiles

        EntityLivingBase attacker = (EntityLivingBase) src;

        AxisAlignedBB box = new AxisAlignedBB(
                player.posX - ASSIST_RADIUS_H, player.posY - ASSIST_RADIUS_V, player.posZ - ASSIST_RADIUS_H,
                player.posX + ASSIST_RADIUS_H, player.posY + ASSIST_RADIUS_V, player.posZ + ASSIST_RADIUS_H
        );

        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class, box, Entity::isEntityAlive)) {
            // Optional: require line of sight before committing
            if (!t.getEntitySenses().canSee(attacker)) continue;

            // Don’t override an existing valid target that’s closer/more urgent (optional)
            EntityLivingBase cur = t.getAttackTarget();
            if (cur != null && cur.isEntityAlive()) {
                // keep current target if it’s already the same attacker
                if (cur == attacker) continue;
            }

            // Engage!
            t.setAttackTarget(attacker);
            t.setInCombat(true);  // your helper ensures ghost sword shows & eating cancels
        }
    }
    @SubscribeEvent
    public static void onPlayerHurtsMob(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        EntityLivingBase victim = event.getEntityLiving();
        if (!(victim instanceof IMob)) return; // only defend you vs hostiles
        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityPlayer)) return;

        EntityPlayer player = (EntityPlayer) src;
        if (player.world.isRemote) return;

        AxisAlignedBB box = new AxisAlignedBB(
                player.posX - ASSIST_RADIUS_H, player.posY - ASSIST_RADIUS_V, player.posZ - ASSIST_RADIUS_H,
                player.posX + ASSIST_RADIUS_H, player.posY + ASSIST_RADIUS_V, player.posZ + ASSIST_RADIUS_H
        );

        for (EntityTraveller t : player.world.getEntitiesWithinAABB(EntityTraveller.class, box, Entity::isEntityAlive)) {
            if (!t.getEntitySenses().canSee(victim)) continue;
            t.setAttackTarget(victim);
            t.setInCombat(true);
        }
    }
}

