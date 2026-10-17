package com.jubitus.travellers.battle;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Loaded blows and player deaths fed to the battles: what keeps a loaded fight shown and counts the player. */
@Mod.EventBusSubscriber(modid = "travellers")
public final class BattleBlowEvents {

    private BattleBlowEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onHurt(LivingHurtEvent event) {
        Entity victim = event.getEntityLiving();
        if (!(victim.world instanceof WorldServer)) return;
        Entity attacker = event.getSource().getTrueSource();
        if (attacker == null || attacker == victim) return;
        boolean unitInvolved = attacker instanceof EntityTraveller || victim instanceof EntityTraveller;
        boolean fighters = (attacker instanceof EntityTraveller || attacker instanceof EntityPlayer)
                && (victim instanceof EntityTraveller || victim instanceof EntityPlayer);
        if (!unitInvolved || !fighters) return;
        VirtualBattleManager.get((WorldServer) victim.world).onLoadedBlow((WorldServer) victim.world, attacker, victim);
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntityLiving() instanceof EntityPlayer) || !(event.getEntityLiving().world instanceof WorldServer)) return;
        VirtualBattleManager.get((WorldServer) event.getEntityLiving().world).onPlayerDeath((EntityPlayer) event.getEntityLiving());
    }
}
