package com.jubitus.travellers.horse;

import com.jubitus.travellers.mount.MountBondLoadHandler;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.zone.stable.StableHorseDeathHandler;
import com.jubitus.travellers.zone.stable.StableHorseLoadHandler;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The one entry for a marked horse joining the world or dying, dispatched to the Stable (pool
 * horse) or the mount system (bonded horse). A pool horse is never a mount: the pool wins.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class HorseEvents {

    private HorseEvents() {
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!(event.getWorld() instanceof WorldServer) || !(event.getEntity() instanceof EntityHorse)) return;
        WorldServer world = (WorldServer) event.getWorld();
        EntityHorse horse = (EntityHorse) event.getEntity();
        if (HorseMarkers.isPoolHorse(horse)) {
            StableHorseLoadHandler.onJoin(world, horse, event);
        } else if (HorseMarkers.isBondedMount(horse)) {
            MountBondLoadHandler.onJoin(world, horse, event);
        }
    }

    /**
     * Death is the one signal a bond or a pool entry must not be recovered from. Health is already
     * at 0 when this fires, so a mod cancelling the event must not keep a corpse bonded or pooled:
     * a rebuild from its snapshot would only die again, on a loop.
     */
    // INVARIANT: HIGHEST + receiveCanceled - a mod cancelling the death must never keep a corpse bonded or pooled.
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity().world instanceof WorldServer) || !(event.getEntity() instanceof EntityHorse)) return;
        WorldServer world = (WorldServer) event.getEntity().world;
        EntityHorse horse = (EntityHorse) event.getEntity();
        boolean pool = HorseMarkers.isPoolHorse(horse);
        if (!pool && !HorseMarkers.isBondedMount(horse)) return;
        if (event.isCanceled()) {
            FMLLog.log.warn("[Travellers/Horses] a {} horse's death was cancelled by another mod - "
                    + "treating it as dead anyway (health is already 0)", pool ? "Stable" : "bonded");
        }
        if (pool) StableHorseDeathHandler.onDeath(world, horse);
        else TravellerMountSupport.onBondedMountDied(horse);
    }
}
