package com.jubitus.travellers.entity.entityAI.travel.mount;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.DamageSource;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * A bonded horse is immune to the same block hazards its rider is - today just cactus, mirroring
 * {@code EntityTraveller.attackEntityFrom}'s own cactus special-case. The rider's own pathing
 * (DANGER_CACTUS/DAMAGE_CACTUS node priority) already steers it away from cactus most of the time,
 * but that path is computed for the rider's own hitbox, not the wider horse under it - a horse can
 * still brush a cactus the rider's path considered clear. Same event-driven pattern as
 * {@link MountFriendlyFireGuard}, deliberately a separate class since the two guards check
 * unrelated conditions (friendliness of the source vs. the damage type itself).
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountHazardGuard {

    private MountHazardGuard() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!TravellersConfig.MOUNTS.mountImmuneToCactus) return;
        if (!(event.getEntityLiving() instanceof EntityHorse)) return;

        DamageSource source = event.getSource();
        if (source != DamageSource.CACTUS && !"cactus".equals(source.getDamageType())) return;

        EntityHorse horse = (EntityHorse) event.getEntityLiving();
        if (!TravellerMountSupport.isBondedMount(horse)) return;

        event.setCanceled(true);
    }
}
