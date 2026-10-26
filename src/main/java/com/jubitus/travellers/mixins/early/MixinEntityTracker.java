package com.jubitus.travellers.mixins.early;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.mount.TravellerMountSupport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks a bonded mount as far as the traveller riding it.
 *
 * <p>Vanilla picks a tracking range from a chain of {@code instanceof} tests, and an {@code EntityHorse}
 * is an {@code IAnimals}: 80 blocks. Every entity this mod registers uses
 * {@link Travellers#ENTITY_TRACKING_RANGE}. A rider tracked further than its own mount is not a race
 * but a guaranteed break, because {@code EntityTrackerEntry} deliberately sends a riding entity no
 * position packets at all - the client is meant to read a passenger's position off its vehicle. Between
 * the two ranges the client therefore holds a rider it cannot place and has no horse to place it on:
 * the body stands frozen at its last known spot with its head still turning while the horse walks away
 * alone, until the player closes to within 80 blocks. Making the two numbers equal removes the window
 * entirely, at the source.
 *
 * <p>This runs at the one moment that is always correct and never visible: the instant the horse is
 * added to the world, before any player has been told it exists. No re-tracking, no flicker, no state
 * to keep, and it covers every path that puts a bonded horse into the world (fresh bond, chunk load,
 * materialize, restore from captured NBT) without any of them having to know about it.
 *
 * <h3>Written to stay out of other mods' way</h3>
 * <ul>
 *   <li><b>HEAD inject, not {@code @Overwrite} or {@code @Redirect}.</b> Several mods may inject at
 *       the same HEAD and all of them run; an overwrite or a redirect is what actually collides.</li>
 *   <li><b>No local capture, no shifted injection point</b>, so nothing here depends on the target
 *       method's instruction layout - the usual reason a mixin breaks when another mod transforms the
 *       same method.</li>
 *   <li><b>{@code require = 0}</b>: if a mod (a performance/networking rewrite, typically) replaces
 *       {@code track(Entity)} outright, this injector simply does not apply. The game still starts and
 *       everything else works; only mounted riders past 80 blocks look wrong again, which is exactly
 *       vanilla's own behaviour. A crash would be a far worse trade for a cosmetic fix.</li>
 *   <li><b>It touches nothing but our own horses.</b> The very first test is our bond tag, so for every
 *       other entity in the game this is one {@code instanceof} plus a map lookup and an immediate
 *       return - no behaviour change, nothing cancelled.</li>
 *   <li>It delegates to vanilla's own {@code track(Entity, int, int, boolean)} rather than duplicating
 *       what that method does, so anything another mod injects there still runs.</li>
 * </ul>
 *
 * <p>Lives in the early config with the other mount mixins: strictly earlier is never wrong, and it
 * removes any question about when {@code EntityTracker} first gets classloaded in a heavy pack (see
 * CLAUDE.md's mixin section - a late registration silently never applies).
 */
@Mixin(EntityTracker.class)
public abstract class MixinEntityTracker {

    @Shadow
    public abstract void track(Entity entityIn, int trackingRange, int updateFrequency,
                               boolean sendVelocityUpdates);

    @Inject(method = "track(Lnet/minecraft/entity/Entity;)V", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void travellers$trackBondedMountLikeItsRider(Entity entityIn, CallbackInfo ci) {
        if (!HorseMarkers.isBondedMount(entityIn)) return;
        track(entityIn, Travellers.ENTITY_TRACKING_RANGE, Travellers.ENTITY_UPDATE_FREQUENCY, true);
        ci.cancel();
    }
}
