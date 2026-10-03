package com.jubitus.travellers.mixins;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.mount.TravellerMountSupport;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.ai.EntityMoveHelper;
import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two unrelated things vanilla does to a bonded mount that have to be stopped.
 *
 * <p><b>1. The leash must never snap.</b> {@code EntityCreature.updateLeashedState} breaks it (and
 * drops a lead item on the ground) as soon as the holder is more than 10 blocks away - which happens
 * constantly and legitimately here: a rider swimming a river, crossing in a boat, or being teleported
 * by one of the mod's own rescue hops. Left alone it would separate a rider from a companion it is
 * supposed to keep for life, and litter leads everywhere doing it. Only {@code TravellerMountSupport}
 * itself may detach one; vanilla is still allowed to clean up after a dead horse.
 *
 * <p><b>2. The rider's path must not be mirrored onto the horse.</b>
 * {@code EntityLiving.updateEntityActionState()} copies a mounted mob's own navigation onto its
 * vehicle ({@code vehicle.getNavigator().setPath(rider.getNavigator().getPath(), 1.5D)} plus a
 * {@code moveHelper.read}) - the mechanism behind vanilla's chicken jockeys. It is actively harmful
 * here, for two reasons found from a real log rather than by reading:
 * <ul>
 *   <li>{@code setPath} hands the horse the <em>same {@code Path} object</em>, and {@code Path}
 *       carries {@code currentPathIndex}. The horse then runs vanilla's own {@code pathFollow()} on
 *       it every tick, advancing that shared index with the horse's width (1.4 vs the rider's 0.6),
 *       a half-block-different node offset, and a backwards {@code isDirectPathBetweenPoints} scan
 *       that skips as far ahead as it can. The rider's navigator is left steering toward a node it
 *       has already passed - the "orbits a point, then carries on" report.</li>
 *   <li>{@code moveHelper.read} can put the horse's own move helper into {@code JUMPING}, making it
 *       jump on its own. Mounted jumps belong to {@code TravellerMountSupport.tickMount}, which
 *       sizes them off the horse's real jump-strength attribute.</li>
 * </ul>
 * Nothing is lost by skipping it: {@code AbstractHorse.travel()} reads the rider's
 * {@code moveForward}/{@code rotationYaw} directly, and a 1-block rise is handled by the horse's own
 * {@code stepHeight} (1.0), not by jumping.
 *
 * <p>The rider steers through vanilla's own
 * {@code canBeSteered()} branch, and its navigator plans for the horse's body rather than its own
 * (see {@code PathBody}), so nothing else about vanilla has to be changed.
 */
@Mixin(EntityLiving.class)
public abstract class MixinEntityLiving {

    @Inject(method = "clearLeashed", at = @At("HEAD"), cancellable = true)
    private void travellers$keepBondedMountLeashed(boolean sendPacket, boolean dropLead, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (HorseMarkers.isBondedMount(self) && !TravellerMountSupport.mayUnleash(self)) {
            ci.cancel();
        }
    }

    @Redirect(method = "updateEntityActionState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/pathfinding/PathNavigate;setPath(Lnet/minecraft/pathfinding/Path;D)Z"))
    private boolean travellers$skipMountPathMirror(PathNavigate vehicleNav, Path path, double speed) {
        if (travellers$isRidingBondedMount()) return false;
        return vehicleNav.setPath(path, speed);
    }

    @Redirect(method = "updateEntityActionState",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/ai/EntityMoveHelper;read(Lnet/minecraft/entity/ai/EntityMoveHelper;)V"))
    private void travellers$skipMountMoveHelperMirror(EntityMoveHelper vehicleHelper, EntityMoveHelper riderHelper) {
        if (travellers$isRidingBondedMount()) return;
        vehicleHelper.read(riderHelper);
    }

    private boolean travellers$isRidingBondedMount() {
        Entity self = (Entity) (Object) this;
        return HorseMarkers.isBondedMount(self.getRidingEntity());
    }
}
