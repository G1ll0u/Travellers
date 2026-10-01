package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Lets a player use their own Stable's pool as a personal horse rack:
 * <ul>
 *   <li><b>Mount</b> one of your pool horses (only the zone owner can - {@code MountTheftGuard}
 *       blocks everyone else) and it leaves the pool, becoming an ordinary owned tame horse with
 *       its normal AI back.</li>
 *   <li><b>Dismount</b> a tame horse you own while standing inside one of your Stable zones and it
 *       is folded back into that zone's pool.</li>
 * </ul>
 * Neither direction is gated by the pool caps or the minimum reserve - it is the owner's own pool.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class StablePlayerHorseAccess {

    private StablePlayerHorseAccess() {
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!TravellersConfig.STABLES.enabled) return;
        if (!(event.getWorldObj() instanceof WorldServer)) return;
        if (!(event.getEntityMounting() instanceof EntityPlayer)) return;
        if (!(event.getEntityBeingMounted() instanceof EntityHorse)) return;

        WorldServer world = (WorldServer) event.getWorldObj();
        EntityPlayer player = (EntityPlayer) event.getEntityMounting();
        EntityHorse horse = (EntityHorse) event.getEntityBeingMounted();
        StableCoordinator coord = StableCoordinator.get(world);

        if (event.isMounting()) {
            if (!coord.isPoolHorseOwnedBy(world, horse, player.getUniqueID())) return;
            coord.detachHorseFromPool(world, horse);
            return;
        }

        // Dismount: only fold a horse in if it isn't already a pool horse.
        if (StableCoordinator.isPoolHorse(horse)) return;
        if (!horse.isTame() || horse.isChild()) return;
        if (!player.getUniqueID().equals(horse.getOwnerUniqueId())) return;
        PlayerZoneRecord zone = coord.findOwnStableAt(world, player.getUniqueID(), horse.getPosition());
        if (zone == null) return;
        coord.attachHorseToPool(world, zone, horse);
    }
}
