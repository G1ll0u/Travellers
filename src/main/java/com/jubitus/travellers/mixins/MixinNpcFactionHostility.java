package com.jubitus.travellers.mixins;

import com.jubitus.travellers.TravellersMixinConfigPlugin;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.combat.compat.AncientWarfare2Compat;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.shadowmage.ancientwarfare.npc.entity.faction.NpcFaction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reciprocal half of AncientWarfare2Compat: makes AW2 faction NPCs (Empire, Bandits, etc.)
 * only proactively target travellers/hired workers they are actually hostile to, mirroring
 * the owner's real AW2 reputation - AW2 itself has no built-in notion of "this creature
 * belongs to a player", so without this it either never reacts to travellers at all, or (the
 * bug this replaces) would have to treat every traveller as unconditionally hostile.
 * <p>
 * Only ever applied if Ancient Warfare 2 is installed - see
 * {@link TravellersMixinConfigPlugin#shouldApplyMixin}. Travellers stay a soft/optional
 * dependency the same way every other compat integration in this codebase is: this mixin class
 * itself may reference AW2 types freely (Mixin reads it structurally via ASM, never through
 * normal JVM class verification, unless actually applied), but nothing outside the mixins
 * package/AncientWarfare2Compat ever does.
 */
@Mixin(value = NpcFaction.class, remap = false)
public abstract class MixinNpcFactionHostility {

    @Inject(method = "isHostileTowards", at = @At("HEAD"), cancellable = true)
    private void travellers$hostileToTravellers(Entity e, CallbackInfoReturnable<Boolean> cir) {
        if (!(e instanceof EntityTraveller) || e instanceof EntityTravellerRaider) return;

        EntityPlayer effectiveOwner = null;
        if (e instanceof EntityTravellerWorker) {
            EntityTravellerWorker worker = (EntityTravellerWorker) e;
            if (worker.isHiredFollower()) effectiveOwner = worker.getFollowOwner();
        }

        cir.setReturnValue(AncientWarfare2Compat.isHostile((NpcFaction) (Object) this, effectiveOwner));
    }
}
