package com.jubitus.travellers.flight;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.item.EntityFireworkRocket;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * Firework boosting for hired workers escorting a player. A glide can only ever trade height for
 * distance, so an escort following someone who climbs will always be left behind eventually - a
 * rocket is the one thing that buys them height back.
 *
 * <p>Deliberately limited to escorting workers with rockets in their own inventory: travellers and
 * raiders flying on their own have no reason to spend anything, and there is no inventory to spend
 * it from. Vanilla does all the work once the rocket exists - {@code EntityFireworkRocket} boosts
 * any {@code EntityLivingBase} that is elytra-flying, with no player-only gate.
 */
public final class ElytraRocketBoost {

    private ElytraRocketBoost() {}

    /**
     * Spend one rocket from the worker's own inventory, if it has one.
     *
     * @return true if a rocket was lit.
     */
    public static boolean tryBoost(EntityTravellerWorker worker) {
        if (!TravellersConfig.ELYTRA.workerFireworkBoost) return false;
        if (worker.world.isRemote) return false;
        if (!worker.elytraFlight.isFlying()) return false;

        if (TravellersConfig.ELYTRA.infiniteRockets) {
            worker.world.spawnEntity(new EntityFireworkRocket(worker.world, new ItemStack(Items.FIREWORKS), worker));
            return true;
        }

        ItemStackHandler inventory = worker.getPersonalInventory();
        if (inventory == null) return false;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getItem() != Items.FIREWORKS) continue;

            ItemStack rocket = inventory.extractItem(slot, 1, false);
            if (rocket.isEmpty()) continue;

            worker.world.spawnEntity(new EntityFireworkRocket(worker.world, rocket, worker));
            return true;
        }

        return false;
    }

    /** Does this worker still have a rocket to spend? Cheap enough to check before deciding to. */
    public static boolean hasRocket(EntityTravellerWorker worker) {
        if (TravellersConfig.ELYTRA.infiniteRockets) return true;

        ItemStackHandler inventory = worker.getPersonalInventory();
        if (inventory == null) return false;

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.getItem() == Items.FIREWORKS) return true;
        }
        return false;
    }
}
