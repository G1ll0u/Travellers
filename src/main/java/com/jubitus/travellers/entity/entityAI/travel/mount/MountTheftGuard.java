package com.jubitus.travellers.entity.entityAI.travel.mount;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Blocks a player from taming, feeding, or riding away a traveller/raider/worker's bonded horse.
 * Both interact events are cancelled - {@code EntityInteractSpecific} fires first for a precise
 * hitbox hit and, if not handled, vanilla falls back to {@code EntityInteract}.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountTheftGuard {

    private MountTheftGuard() {
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        guard(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        guard(event, event.getTarget());
    }

    private static void guard(PlayerInteractEvent event, net.minecraft.entity.Entity target) {
        if (!TravellersConfig.MOUNTS.preventPlayerTheft) return;
        if (target == null) return;
        if (!TravellerMountSupport.isBondedMount(target)) return;
        event.setCanceled(true);
    }
}
