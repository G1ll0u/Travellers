package com.jubitus.travellers.combat;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class TravellerKillSoundSystem {

    private TravellerKillSoundSystem() {}

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getSource() == null) return;
        net.minecraft.entity.Entity killer = event.getSource().getTrueSource();
        if (!(killer instanceof EntityTraveller)) return;
        EntityTraveller traveller = (EntityTraveller) killer;
        if (traveller.isDead) return;
        traveller.tryPlayKillSound();
    }
}
