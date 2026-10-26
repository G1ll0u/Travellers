package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.horse.HorseMarkers;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IEntityOwnable;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingKnockBackEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Hits between allies: they pass through ({@code COMBAT.friendlyFirePassesThrough}), or deal a share of their
 * damage and knockback. Keyed off the entity actually hit, never the shooter's intended target.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class TravellerFriendlyFireHandler {

    private TravellerFriendlyFireHandler() {
    }

    /**
     * Same side: a player, their hired workers and pets; hired workers of the same owner; groupmates of a
     * spawn group; a unit's bonded mount sides with its rider. A player's own hits on an animal stay vanilla.
     */
    public static boolean areAllies(@Nullable Entity a, @Nullable Entity b) {
        if (a == null || b == null || a == b) return false;
        if (a instanceof EntityPlayer && isAnimal(b) || b instanceof EntityPlayer && isAnimal(a)) return false;
        Entity sideA = rider(a), sideB = rider(b);
        if (sideA == sideB) return true;
        UUID ownerA = ownerSide(sideA);
        if (ownerA != null && ownerA.equals(ownerSide(sideB))) return true;
        if (!(sideA instanceof EntityTraveller) || !(sideB instanceof EntityTraveller)) return false;
        return ((EntityTraveller) sideA).isGroupmate((EntityTraveller) sideB);
    }

    private static boolean isAnimal(Entity e) {
        return !(e instanceof EntityPlayer) && !(e instanceof EntityTraveller);
    }

    // A bonded mount stands for its rider when the rider is loaded.
    private static Entity rider(Entity e) {
        if (!HorseMarkers.isBondedMount(e) || !(e.world instanceof WorldServer)) return e;
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

    // Cancelling the impact lets the arrow fly on through the ally.
    @SubscribeEvent
    public static void onArrowImpact(ProjectileImpactEvent.Arrow event) {
        if (!TravellersConfig.COMBAT.friendlyFirePassesThrough) return;
        EntityArrow arrow = event.getArrow();
        RayTraceResult hit = event.getRayTraceResult();
        if (arrow.world.isRemote || hit.typeOfHit != RayTraceResult.Type.ENTITY) return;
        if (areAllies(arrow.shootingEntity, hit.entityHit)) event.setCanceled(true);
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
