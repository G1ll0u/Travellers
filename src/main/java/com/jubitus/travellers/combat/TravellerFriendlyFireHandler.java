package com.jubitus.travellers.combat;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.math.RayTraceResult;
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

    /** A player and their hired workers, hired workers of the same owner, or groupmates of a spawn group. */
    public static boolean areAllies(@Nullable Entity a, @Nullable Entity b) {
        if (a == null || b == null || a == b) return false;
        UUID sideA = ownerSide(a);
        if (sideA != null && sideA.equals(ownerSide(b))) return true;
        if (!(a instanceof EntityTraveller) || !(b instanceof EntityTraveller)) return false;
        return ((EntityTraveller) a).isGroupmate((EntityTraveller) b);
    }

    // The player's own id, or a hired worker's owner id.
    @Nullable
    private static UUID ownerSide(Entity e) {
        if (e instanceof EntityPlayer) return e.getUniqueID();
        if (e instanceof EntityTravellerWorker && ((EntityTravellerWorker) e).isHiredFollower()) {
            return ((EntityTravellerWorker) e).getFollowOwnerId();
        }
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
