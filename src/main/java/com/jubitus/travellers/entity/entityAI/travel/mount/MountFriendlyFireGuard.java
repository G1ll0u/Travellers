package com.jubitus.travellers.entity.entityAI.travel.mount;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * A bonded horse takes no damage from a source that is clearly on its rider's own side - itself,
 * a same-faction traveller/raider, a fellow (non-enemy) hired worker, or (for a hired worker's
 * mount) its owning player. Deliberately allow-by-default rather than reusing any single unified
 * "would I attack this" predicate directly: {@code EntityTraveller}/{@code EntityTravellerRaider}/
 * {@code EntityTravellerWorker} each define that differently (a raider's own
 * {@code shouldAttackExtraEntity} override, for instance, excludes travellers entirely - cross-faction
 * traveller hostility is handled elsewhere for raiders), so a positive "is this hostile" reuse would
 * silently disagree with one of the three. Erring toward "let real damage through" here is the safer
 * failure mode than wrongly immunizing a mount against a genuine enemy.
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

    private static boolean isClearlyFriendly(EntityTraveller owner, Entity src) {
        if (src == owner) return true;
        if (owner instanceof EntityTravellerWorker && src instanceof EntityTravellerWorker) {
            return !((EntityTravellerWorker) owner).isEnemyHiredWorker((EntityTravellerWorker) src);
        }
        if (src instanceof EntityTraveller) {
            return !owner.combat.isCrossFaction((EntityTraveller) src);
        }
        if (owner instanceof EntityTravellerWorker && src instanceof EntityPlayer) {
            return !((EntityTravellerWorker) owner).isEnemyPlayer((EntityPlayer) src);
        }
        return false; // IMobs, unrelated players, anything else: never treated as friendly fire
    }
}
