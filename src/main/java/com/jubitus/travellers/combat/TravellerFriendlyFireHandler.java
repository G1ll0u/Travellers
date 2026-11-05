package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.horse.HorseMarkers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Hits between allies: they pass through ({@code COMBAT.friendlyFirePassesThrough}), or deal a share of their
 * damage and knockback. Keyed off the entity actually hit, never the shooter's intended target. Arrows skip
 * allies in {@code MixinEntityArrow}; {@link #areAllies} also runs on the client there.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class TravellerFriendlyFireHandler {

    private TravellerFriendlyFireHandler() {
    }

    /**
     * Same side: a player, their hired workers and pets; hired workers of the same owner; groupmates of a
     * spawn group; a unit's bonded mount sides with its rider. A player's own hits on an animal stay vanilla
     * (a bonded mount counts as its rider, not as an animal).
     */
    public static boolean areAllies(@Nullable Entity a, @Nullable Entity b) {
        if (a == null || b == null || a == b) return false;
        Entity sideA = rider(a), sideB = rider(b);
        if (sideA instanceof EntityPlayer && isAnimal(sideB) || sideB instanceof EntityPlayer && isAnimal(sideA)) return false;
        if (sideA == sideB) return true;
        UUID ownerA = ownerSide(sideA);
        if (ownerA != null && ownerA.equals(ownerSide(sideB))) return true;
        if (!(sideA instanceof EntityTraveller) || !(sideB instanceof EntityTraveller)) return false;
        return ((EntityTraveller) sideA).isGroupmate((EntityTraveller) sideB);
    }

    private static boolean isAnimal(Entity e) {
        return !(e instanceof EntityPlayer) && !(e instanceof EntityTraveller);
    }

    // A bonded mount stands for its rider when the rider is loaded; the client only knows the unit seated on it.
    private static Entity rider(Entity e) {
        if (!(e.world instanceof WorldServer)) {
            Entity seated = e instanceof AbstractHorse ? e.getControllingPassenger() : null;
            return seated instanceof EntityTraveller ? seated : e;
        }
        if (!HorseMarkers.isBondedMount(e)) return e;
        UUID riderId = HorseMarkers.getBondedOwner(e);
        Entity rider = riderId != null ? ((WorldServer) e.world).getEntityFromUuid(riderId) : null;
        return rider != null ? rider : e;
    }

    // The player's own id, or the owner id of a hired worker, a pet or a tamed horse.
    @Nullable
    private static UUID ownerSide(Entity e) {
        if (e instanceof EntityPlayer) return e.getUniqueID();
        if (e instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) e;
            return w.isHiredFollower() ? w.getFollowOwnerId() : null;
        }
        if (e instanceof IEntityOwnable) return ((IEntityOwnable) e).getOwnerId();
        if (e instanceof AbstractHorse) return ((AbstractHorse) e).getOwnerUniqueId();
        return null;
    }

    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        if (!TravellersConfig.COMBAT.friendlyFirePassesThrough) return;
        if (areAllies(event.getSource().getTrueSource(), event.getEntityLiving())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (!areAllies(event.getSource().getTrueSource(), event.getEntityLiving())) return;
        event.setAmount(event.getAmount() * TravellersConfig.COMBAT.friendlyFireDamagePercent / 100.0F);
    }

    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (!areAllies(event.getAttacker(), event.getEntityLiving())) return;
        event.setStrength(event.getStrength() * TravellersConfig.COMBAT.friendlyFireKnockbackPercent / 100.0F);
    }
}
