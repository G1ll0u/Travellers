package com.jubitus.travellers.mount;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

/**
 * The single authoritative "this bond is over" signal. Everything else that could make a horse look
 * gone - an unloaded chunk, a rider on foot, an entity another mod removed and re-added - is
 * recoverable, and {@code TravellerMountSupport.resolveMount} deliberately rebuilds from those
 * rather than dropping the bond. Death is the one case that must not be recovered from, so it is
 * read from the event rather than inferred from an absence.
 *
 * <p><b>Highest priority, {@code receiveCanceled = true}.</b> The horse's health has already dropped
 * to 0 or below by the time this event fires (vanilla applies damage before posting it) regardless of
 * whether the event ends up cancelled - a health-restoring mod reacting to the same event at a lower
 * priority does not undo that. Left at normal priority, a mod that cancels {@code LivingDeathEvent}
 * (a "no death here" protection/claim mod, a revive/totem effect) would stop this handler from ever
 * running: the bond would never clear, {@code resolveMount} would keep "rebuilding" a horse from a
 * snapshot of what is - by health alone - already a corpse, and that corpse dies again within its own
 * ~20-tick death animation before ever reaching a healthy state to snapshot - a horse that endlessly
 * flickers in and out of existence, dropping its lead each time, while its rider sits forced into LED
 * forever. Running first and unconditionally is what makes the bond-clearing decision ours alone,
 * whatever any other mod decides about the event afterward.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountDeathHandler {

    private MountDeathHandler() {
    }

    // INVARIANT: HIGHEST + receiveCanceled - a mod cancelling the death must never keep a corpse bonded.
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().world.isRemote) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;

        EntityHorse horse = (EntityHorse) event.getEntity();
        if (!TravellerMountSupport.isBondedMount(horse)) return;

        if (event.isCanceled()) {
            FMLLog.log(Level.WARN,
                    "[Travellers/Mounts] a bonded horse's death was cancelled by another mod - "
                            + "clearing the bond anyway (health is already 0)");
        }

        TravellerMountSupport.onBondedMountDied(horse);
    }
}
