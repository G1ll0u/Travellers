package com.jubitus.travellers.entity.entityai.travel.mount;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * A bonded horse takes no damage from a source that is on its rider's <em>own side</em> - the rider
 * itself, a groupmate traveller/raider (same militia/convoy leader), or a hired worker of the same
 * owner. This is the "allied ranged fire shouldn't shred our own horses" rule, nothing broader:
 * an unrelated same-culture traveller, a different owner's worker, an enemy, and the owning player
 * all deal normal damage. Deliberately not routed through any single unified "would I attack this"
 * predicate: {@code EntityTraveller}/{@code EntityTravellerRaider}/{@code EntityTravellerWorker}
 * each define that differently, so the scope is spelled out here instead.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountFriendlyFireGuard {

    private MountFriendlyFireGuard() {
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!TravellersConfig.MOUNTS.mountImmuneToFriendlyDamage) return;
        if (!(event.getEntityLiving() instanceof EntityHorse)) return;
        EntityHorse horse = (EntityHorse) event.getEntityLiving();
        if (!TravellerMountSupport.isBondedMount(horse)) return;

        Entity src = event.getSource().getTrueSource();
        if (src == null) return; // environmental damage (fall, fire...) is not friendly fire, leave it

        UUID ownerUUID = TravellerMountSupport.getBondedOwner(horse);
        if (ownerUUID == null) return;
        if (!(horse.world instanceof WorldServer)) return;
        Entity ownerEntity = ((WorldServer) horse.world).getEntityFromUuid(ownerUUID);
        if (!(ownerEntity instanceof EntityTraveller)) return; // owner not loaded - can't classify, let it through

        if (isClearlyFriendly((EntityTraveller) ownerEntity, src)) {
            event.setCanceled(true);
        }
    }

    static boolean isClearlyFriendly(EntityTraveller owner, Entity src) {
        if (src == owner) return true;

        // Hired worker mount: only fellow workers of the SAME owner get the pass.
        if (owner instanceof EntityTravellerWorker) {
            if (!(src instanceof EntityTravellerWorker)) return false;
            UUID a = ((EntityTravellerWorker) owner).getFollowOwnerId();
            UUID b = ((EntityTravellerWorker) src).getFollowOwnerId();
            return a != null && a.equals(b);
        }

        // Traveller / raider mount: only groupmates (same militia/convoy leader) get the pass.
        if (src instanceof EntityTraveller) {
            return owner.isGroupmate((EntityTraveller) src);
        }

        return false; // players, IMobs, unrelated travellers, anything else: normal damage
    }
}
