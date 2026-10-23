package com.jubitus.travellers.mixins;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A traveller riding its bonded horse is shown to a player exactly when its horse is, in the same tick.
 *
 * <p>Vanilla decides each entity's visibility on its own schedule, so at the edge of tracking range the rider and
 * its horse appear and disappear a few ticks apart. In that gap the client holds a rider with no vehicle, and the
 * rotation packets vanilla keeps sending a riding entity carry its stale last position (where it last stood on
 * foot, typically where it materialized): the client glides the lone body back there.
 */
@Mixin(EntityTrackerEntry.class)
public abstract class MixinEntityTrackerEntry {

    @Shadow
    @Final
    private Entity trackedEntity;

    @Inject(method = "isVisibleTo", at = @At("HEAD"), cancellable = true, require = 0)
    private void travellers$riderVisibleWithItsHorse(EntityPlayerMP player, CallbackInfoReturnable<Boolean> cir) {
        Entity vehicle = trackedEntity.getRidingEntity();
        if (!(trackedEntity instanceof EntityTraveller) || !HorseMarkers.isBondedMount(vehicle)) return;
        EntityTrackerEntry horseEntry = entryOf(vehicle);
        cir.setReturnValue(horseEntry != null && horseEntry.trackingPlayers.contains(player));
    }

    @Inject(method = "updatePlayerEntity", at = @At("TAIL"), require = 0)
    private void travellers$riderFollowsItsHorse(EntityPlayerMP player, CallbackInfo ci) {
        if (!HorseMarkers.isBondedMount(trackedEntity)) return;
        for (Entity passenger : trackedEntity.getPassengers()) {
            if (!(passenger instanceof EntityTraveller)) continue;
            EntityTrackerEntry riderEntry = entryOf(passenger);
            if (riderEntry != null) riderEntry.updatePlayerEntity(player);
        }
    }

    private static EntityTrackerEntry entryOf(Entity e) {
        if (!(e.world instanceof WorldServer)) return null;
        return ((MixinEntityTrackerAccessor) ((WorldServer) e.world).getEntityTracker())
                .travellers$getEntries().lookup(e.getEntityId());
    }
}
