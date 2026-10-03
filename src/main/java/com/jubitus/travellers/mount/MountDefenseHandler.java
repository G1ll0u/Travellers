package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/** A rider defends its bonded horse: whoever hurts it is handed to the rider's own hurt-by-target AI,
 * so every existing retaliation rule (owner, declared war, player grace hits, factions) applies unchanged. */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountDefenseHandler {

    private MountDefenseHandler() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityHorse)) return;
        EntityHorse horse = (EntityHorse) event.getEntityLiving();
        if (!(horse.world instanceof WorldServer) || !HorseMarkers.isBondedMount(horse)) return;

        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityLivingBase) || src == horse || !src.isEntityAlive()) return;

        UUID ownerUUID = HorseMarkers.getBondedOwner(horse);
        Entity ownerEntity = ownerUUID != null ? ((WorldServer) horse.world).getEntityFromUuid(ownerUUID) : null;
        if (!(ownerEntity instanceof EntityTraveller) || !ownerEntity.isEntityAlive()) return;
        EntityTraveller rider = (EntityTraveller) ownerEntity;
        if (MountFriendlyFireGuard.isClearlyFriendly(rider, src)) return;

        double range = TravellersConfig.MOUNTS.defendMountRange;
        if (rider.getDistanceSq(horse) > range * range) return;
        rider.noteMountAttacked((EntityLivingBase) src);
    }
}
