package com.jubitus.travellers.entity.entityai.travel.mount;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Blocks a player from taming, feeding, or riding away a traveller/raider/worker's bonded horse -
 * no exceptions, not even the worker's own owner (a past version let the owner permanently steal
 * the bond back this way; removed - reclaiming a live worker's mount has no player-facing path at
 * all right now). Two layers:
 * <ul>
 *   <li>{@code guard()} cancels both interact events - {@code EntityInteractSpecific} fires first
 *       for a precise hitbox hit and, if not handled, vanilla falls back to {@code EntityInteract}.
 *       This is what stops the mount from ever being attempted in the first place.</li>
 *   <li>{@link #onMount} additionally cancels the {@link EntityMountEvent} itself - the actual gate
 *       {@code Entity.startRiding}/{@code dismountRidingEntity} check before completing a mount.
 *       Belt-and-suspenders: without it, a bonded horse whose rightful rider reclaims it every tick
 *       via {@code TravellerMountSupport.reconcileOnFoot} could still let a player's interact sneak
 *       through once and get force-evicted a tick later, which reads as a flash mount-then-dismount.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountTheftGuard {

    private MountTheftGuard() {
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        guard(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        guard(event, event.getTarget());
    }

    @SubscribeEvent
    public static void onMount(EntityMountEvent event) {
        if (!event.isMounting()) return;
        if (!TravellersConfig.MOUNTS.preventPlayerTheft) return;
        if (!(event.getEntityMounting() instanceof net.minecraft.entity.player.EntityPlayer)) return;
        Entity mount = event.getEntityBeingMounted();
        if (mount != null && mount.world.isRemote) {
            if (HorseInteractLock.blocksClient(mount, (net.minecraft.entity.player.EntityPlayer) event.getEntityMounting())) {
                event.setCanceled(true);
            }
            return;
        }
        if (TravellerMountSupport.isBondedMount(mount)) {
            event.setCanceled(true);
        }
    }

    private static void guard(PlayerInteractEvent event, Entity target) {
        if (target == null) return;
        // The client must refuse too, or it predicts the mount and the server snaps the player back off.
        if (target.world.isRemote) {
            if (HorseInteractLock.blocksClient(target, event.getEntityPlayer())) event.setCanceled(true);
            return;
        }
        // A player interacting with a loose ex-mount (its rider died with nowhere to send it) claims
        // it for good - it is never a cleanup candidate again. Unrelated to the theft guard below;
        // this horse isn't bonded to anyone any more, so nothing here gets cancelled by it.
        if (OrphanMountCleanup.isOrphaned(target)) OrphanMountCleanup.clearOrphanTag(target);
        if (TravellersConfig.MOUNTS.preventPlayerTheft && TravellerMountSupport.isBondedMount(target)) {
            event.setCanceled(true);
            return;
        }
        if (isUnclaimedStableHorse(target)) {
            // The Stable's own owner may take one of their pool horses out - the EntityMountEvent
            // handler (StablePlayerHorseAccess) removes it from the pool when they mount it.
            net.minecraft.entity.player.EntityPlayer p = event.getEntityPlayer();
            if (p != null && target instanceof EntityHorse
                    && target.world instanceof net.minecraft.world.WorldServer
                    && com.jubitus.travellers.world.zone.stable.StableCoordinator
                    .get((net.minecraft.world.WorldServer) target.world)
                    .isPoolHorseOwnedBy((net.minecraft.world.WorldServer) target.world,
                            (EntityHorse) target, p.getUniqueID())) {
                return;
            }
            event.setCanceled(true);
        }
    }

    /**
     * A horse a Stable is tracking but hasn't yet handed to a worker (see the Stable containment
     * system) is off-limits the same way a bonded mount is - otherwise a player could simply walk up
     * and lead or ride one away, which would defeat the "never leaves the zone" guarantee just as
     * thoroughly as a wandering AI would.
     */
    private static boolean isUnclaimedStableHorse(net.minecraft.entity.Entity target) {
        if (!TravellersConfig.STABLES.enabled) return false;
        if (!(target instanceof net.minecraft.entity.passive.EntityHorse)) return false;
        if (!(target.world instanceof net.minecraft.world.WorldServer)) return false;
        return com.jubitus.travellers.world.zone.stable.StableCoordinator.isPoolHorse(target);
    }
}
