package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.EntityLivingBase;
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
 * still brush a cactus the rider's path considered clear.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountHazardGuard {

    private MountHazardGuard() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (cancelCactus(event)) return;
        cancelMountSuffocation(event);
    }

    private static boolean cancelCactus(LivingHurtEvent event) {
        if (!TravellersConfig.MOUNTS.mountImmuneToCactus) return false;
        if (!(event.getEntityLiving() instanceof EntityHorse)) return false;

        DamageSource source = event.getSource();
        if (source != DamageSource.CACTUS && !"cactus".equals(source.getDamageType())) return false;

        EntityHorse horse = (EntityHorse) event.getEntityLiving();
        if (!HorseMarkers.isBondedMount(horse)) return false;

        event.setCanceled(true);
        return true;
    }

    /**
     * A rider in the saddle takes no suffocation damage from the block its head is in, and neither
     * does its horse.
     *
     * <p>This is what buys back low woodland. A horse needs two blocks of headroom; the rider on its
     * back reaches nearly three. Demanding all three of the pathfinder makes every stretch of forest
     * with a low canopy simply unpathable - measured at 43% of a route spent with no path at all,
     * ending in detours and rescue teleports. Demanding only the horse's two, and leaving the rider
     * to scrape through, is the behaviour a player already gets away with; the difference is that a
     * player is not killed by it, and now neither is a traveller.
     *
     * <p>In practice this rarely fires at all any more: {@code TravellerNodeProcessor} now refuses to
     * route under anything that would actually trigger this damage (see its
     * {@code classifyStackClearance}, built on the same {@code causesSuffocation()} test this method
     * would otherwise be reacting to) and only ever costs, never bans, riding under leaves - which
     * were already exempt from this damage in vanilla. What is left for this to guard is the dynamic
     * cases the planner cannot see coming: a reactive jump's mid-air arc, or a sidestep nudge, briefly
     * overlapping something solid before physics carries the pair back clear.
     *
     * <p>Covers the horse itself too, not just the rider: the horse's own body is a hard requirement
     * everywhere the planner runs, but the same dynamic maneuvers that can graze the rider through
     * solid terrain can graze the horse's body through it as well, and nothing else guards that.
     */
    private static void cancelMountSuffocation(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        if (source != DamageSource.IN_WALL && !"inWall".equals(source.getDamageType())) return;

        EntityLivingBase victim = event.getEntityLiving();
        if (victim instanceof EntityTraveller) {
            if (!((EntityTraveller) victim).isMountedOnBondedMount()) return;
        } else if (victim instanceof EntityHorse) {
            if (!HorseMarkers.isBondedMount((EntityHorse) victim)) return;
        } else {
            return;
        }

        event.setCanceled(true);
    }
}
