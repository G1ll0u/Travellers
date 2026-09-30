package com.jubitus.travellers.world.zone.stable;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.UUID;

/**
 * The one place pool state ({@code tamed}/{@code saddled}/temper - all owned by {@link StableWork})
 * is pushed onto a live {@code EntityHorse}. A horse whose chunk was unloaded while the sim advanced
 * comes back stale, so the entry is authoritative and the entity is written to match, never read
 * from. Used both by {@link StableHorseLoadHandler} (join time) and the physical reconciler.
 */
public final class StableHorseSync {

    private StableHorseSync() {
    }

    public static void pushEntryOntoHorse(StableHorseEntry entry, EntityHorse horse, UUID ownerUUID) {
        if (entry.tamed && !horse.isTame()) {
            horse.setHorseTamed(true);
            horse.setOwnerUniqueId(ownerUUID);
        }
        if (!horse.isTame() && entry.temper > horse.getTemper()) {
            horse.increaseTemper(entry.temper - horse.getTemper());
        }
        if (entry.saddled && !horse.isHorseSaddled()) {
            horse.replaceItemInInventory(400, new ItemStack(Items.SADDLE));
        }
    }
}
