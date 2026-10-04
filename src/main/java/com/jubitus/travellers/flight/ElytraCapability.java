package com.jubitus.travellers.flight;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemElytra;
import net.minecraft.item.ItemStack;

/**
 * Single answer to "may this entity use its elytra right now".
 *
 * Every takeoff path routes through here so the gate can never diverge between the travel AI,
 * the follow AIs and the debug command.
 */
public final class ElytraCapability {

    private ElytraCapability() {}

    /**
     * Is a usable elytra worn, regardless of the entity's current state? Sandbox-style config toggles
     * can substitute for a real one entirely ({@code noElytraNeededAtAll}), for hired workers actively
     * escorting a flying leader only ({@code noElytraNeededForHenchmen}), or just keep a real one from
     * ever wearing out ({@code ignoreElytraDurability}) - see each field's comment in {@code Elytra}.
     */
    public static boolean hasUsableElytra(EntityTraveller mob) {
        TravellersConfig.Elytra cfg = TravellersConfig.ELYTRA;
        if (cfg.noElytraNeededAtAll) return true;

        if (cfg.noElytraNeededForHenchmen && mob instanceof EntityTravellerWorker) {
            EntityLivingBase leader = ((EntityTravellerWorker) mob).getEffectiveFollowTarget();
            if (leader != null && leader.isElytraFlying()) return true;
        }

        ItemStack chest = mob.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA) return false;
        return cfg.ignoreElytraDurability || ItemElytra.isUsable(chest);
    }

    /**
     * Undoes vanilla's own periodic elytra wear (it damages the item by 1 every 20 ticks of flight,
     * {@code EntityLivingBase.updateElytra}, entirely independent of anything this mod does) so a real
     * elytra never actually breaks and vanishes from the slot under {@code ignoreElytraDurability}.
     * A no-op without a real elytra item there - nothing to repair.
     */
    public static void maintainElytra(EntityTraveller mob) {
        if (!TravellersConfig.ELYTRA.ignoreElytraDurability) return;
        ItemStack chest = mob.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
        if (chest.getItem() == Items.ELYTRA && chest.getItemDamage() != 0) {
            chest.setItemDamage(0);
        }
    }

    /** May this entity start a flight this tick? */
    public static boolean canTakeOff(EntityTraveller mob) {
        if (!TravellersConfig.ELYTRA.enabled) return false;
        if (mob.world.isRemote || mob.isDead) return false;
        if (!hasUsableElytra(mob)) return false;
        if (mob.isRiding() || mob.isInWater()) return false;
        if (mob.sleep.isSleeping() || mob.isCampingPassive() || mob.isChoirSinging()) return false;
        if (mob.isInCombat() || mob.getAttackTarget() != null) return false;
        return true;
    }

    /**
     * May an ongoing flight continue? Deliberately looser than {@link #canTakeOff}: a fight starting
     * mid-air ends the flight, but only by landing - the controller never simply drops the entity.
     */
    public static boolean canKeepFlying(EntityTraveller mob) {
        if (!TravellersConfig.ELYTRA.enabled) return false;
        if (mob.isDead || mob.isRiding()) return false;
        return hasUsableElytra(mob);
    }
}
