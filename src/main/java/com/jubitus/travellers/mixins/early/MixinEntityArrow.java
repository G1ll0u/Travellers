package com.jubitus.travellers.mixins.early;

import com.jubitus.travellers.combat.TravellerFriendlyFireHandler;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Allies are dropped from an arrow's hit candidates, so it flies through their hitbox on both sides
 * (cancelling the impact instead shows a client-side bounce, and loses the enemy or wall behind).
 * A ModifyVariable on the candidate list, not a Redirect, so other mods touching this method still chain.
 */
@Mixin(EntityArrow.class)
public abstract class MixinEntityArrow {

    @Shadow
    public Entity shootingEntity;

    @ModifyVariable(method = "findEntityOnPath", at = @At("STORE"))
    private List<Entity> travellers$skipAllies(List<Entity> candidates) {
        if (!TravellersConfig.COMBAT.friendlyFirePassesThrough || shootingEntity == null) return candidates;
        List<Entity> kept = new ArrayList<>(candidates.size());
        for (Entity e : candidates) {
            if (!TravellerFriendlyFireHandler.areAllies(shootingEntity, e)) kept.add(e);
        }
        return kept;
    }
}
