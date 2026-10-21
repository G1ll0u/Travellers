package com.jubitus.travellers.worker;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.item.EntityItem;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentManager;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import java.util.Iterator;

public class TravellerWorkerLootHandler {

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        DamageSource src = event.getSource();
        if (src == null) return;

        if (!(src.getTrueSource() instanceof EntityTravellerWorker)) {
            return;
        }

        EntityTravellerWorker worker = (EntityTravellerWorker) src.getTrueSource();

        // Config: auto-loot on/off
        if (!TravellersConfig.WORKER.workerAutoLootMobs) {
            return;
        }

        ItemStackHandler inv = worker.getPersonalInventory();
        if (inv == null) return; // should never be null, but be safe

        // Iterate over drops and try to push them into the personal backpack
        Iterator<EntityItem> it = event.getDrops().iterator();
        while (it.hasNext()) {
            EntityItem ei = it.next();
            if (ei == null || ei.isDead) continue;

            ItemStack stack = ei.getItem();
            if (stack.isEmpty() || !worker.equipment.fitsRole(stack)) continue;

            // A shield is only ever worn in the offhand, never stacked into the backpack
            if (TravellerEquipmentManager.isShield(stack)) {
                if (worker.equipment.wantsShield(stack)) {
                    ItemStack one = stack.copy();
                    one.setCount(1);
                    worker.equipment.equipShield(one);
                    stack.shrink(1);
                    if (stack.isEmpty()) it.remove();
                }
                continue;
            }

            // Food is capped and a second bow is only taken if it is better (no backpack hoarding)
            boolean bow = TravellerEquipmentManager.isBow(stack);
            if (bow && !worker.equipment.wantsBow(stack)) continue;
            ItemStack remainder = stack.getItem() instanceof ItemFood
                    ? TravellerEquipmentManager.insertFoodCapped(inv, stack.copy())
                    : ItemHandlerHelper.insertItemStacked(inv, stack, false);
            if (bow && remainder.isEmpty()) worker.equipment.keepOnlyBestBow();

            if (remainder.isEmpty()) {
                // Fully absorbed: don’t spawn this drop at all
                it.remove();
            } else if (remainder.getCount() != stack.getCount()) {
                // Partially inserted: keep only leftover in the event
                stack.setCount(remainder.getCount());
                ei.setItem(remainder);
            }
        }
    }
}
