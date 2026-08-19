package com.jubitus.travellers.entity.entityAI.travel.mount;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The single authoritative "this bond is over" signal. Everything else that could make a horse look
 * gone - an unloaded chunk, a rider on foot, an entity another mod removed and re-added - is
 * recoverable, and {@code TravellerMountSupport.resolveMount} deliberately rebuilds from those
 * rather than dropping the bond. Death is the one case that must not be recovered from, so it is
 * read from the event rather than inferred from an absence.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountDeathHandler {

    private MountDeathHandler() {
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().world.isRemote) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;

        EntityHorse horse = (EntityHorse) event.getEntity();
        if (!TravellerMountSupport.isBondedMount(horse)) return;

        TravellerMountSupport.onBondedMountDied(horse);
    }
}
