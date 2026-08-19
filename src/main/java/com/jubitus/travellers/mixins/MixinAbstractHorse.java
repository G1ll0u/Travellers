package com.jubitus.travellers.mixins;

import com.jubitus.travellers.entity.entityAI.travel.mount.TravellerMountSupport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands a bonded mount back its own navigation.
 *
 * <p>{@code AbstractHorse.travel()} takes its movement from the passenger instead of from itself
 * whenever {@code isBeingRidden() && canBeSteered() && isHorseSaddled()} - the branch written for a
 * player holding the reins. That is what this mod used to rely on, and it is the root of a long
 * series of pathfinding problems, because it means the entity <em>planning</em> the route is the
 * rider (0.6 wide) while the entity <em>walking</em> it is the horse (1.4 wide).
 *
 * <p>Vanilla sizes every part of navigation from whoever asks for the path: the cells a step is
 * allowed to occupy ({@code NodeProcessor.init}: {@code floor(width + 1)}, so 1 cell for the rider
 * and 2 for the horse) and even the point aimed at inside a node ({@code Path.getVectorFromIndex}:
 * {@code (int)(width + 1) * 0.5}, so a rider is steered to the middle of one cell while a horse is
 * steered to the middle of a two-by-two block). Asking as the rider therefore parks a 1.4-wide body
 * exactly on the line of block centres, overhanging its neighbours by 0.2 on each side, and every
 * "wedged against a trunk it was walking past" report follows from that one fact.
 *
 * <p>Refusing to be steered puts the horse back on its own navigator and move helper, where all of
 * that is consistent by construction. The rider's AI still decides where to go - it simply hands
 * the destination to the horse (see {@code EntityTraveller.getNavigator()}). The saddle stays on,
 * since it is only the flag that matters here, not the item.
 */
@Mixin(AbstractHorse.class)
public abstract class MixinAbstractHorse {

    @Inject(method = "canBeSteered", at = @At("HEAD"), cancellable = true)
    private void travellers$bondedMountSteersItself(CallbackInfoReturnable<Boolean> cir) {
        if (TravellerMountSupport.isBondedMount((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
