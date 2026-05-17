package com.jubitus.travellers.server.net;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.item.EntityItem;
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
        if (!TravellersModConfig.workerAutoLootMobs) {
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
            if (stack.isEmpty()) continue;

            // Insert into worker's personal inventory
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(inv, stack, false);

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
