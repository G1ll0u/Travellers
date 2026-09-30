package com.jubitus.travellers.debug;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.items.ItemStackHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.UUID;

/** Cargo/gear snapshots at each mission step, to find where a worker's items change. Off by default. */
public final class MissionDebugLog {

    private static final Logger LOG = LogManager.getLogger("Travellers/Mission");

    private MissionDebugLog() {
    }

    public static boolean enabled() {
        return TravellersConfig.WORKER.debugMissionLog;
    }

    public static void loaded(EntityTravellerWorker w, String event) {
        if (!enabled()) return;
        LOG.info("[L] {} {} ({}) mode={} | cargo={} | gear={}", event, w.getName(), shortId(w.getUniqueID()),
                w.getMode(), cargo(w.getTravelInventory()), gear(w));
    }

    public static void record(UUID id, String name, String event, NBTTagCompound nbt) {
        if (!enabled() || nbt == null) return;
        ItemStackHandler inv = new ItemStackHandler(18);
        if (nbt.hasKey("TravelInventory")) inv.deserializeNBT(nbt.getCompoundTag("TravelInventory"));
        LOG.info("[V] {} {} ({}) mode={} | cargo={} | gear={}", event, name, shortId(id),
                nbt.getInteger("WorkerMode"), cargo(inv), gear(nbt));
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String cargo(ItemStackHandler inv) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s.isEmpty()) continue;
            if (sb.length() > 1) sb.append(", ");
            sb.append(s.getDisplayName()).append(" x").append(s.getCount());
        }
        return sb.append("]").toString();
    }

    private static String gear(EntityTravellerWorker w) {
        StringBuilder sb = new StringBuilder("[");
        for (net.minecraft.inventory.EntityEquipmentSlot slot : net.minecraft.inventory.EntityEquipmentSlot.values()) {
            ItemStack s = w.getItemStackFromSlot(slot);
            if (s.isEmpty()) continue;
            if (sb.length() > 1) sb.append(", ");
            sb.append(s.getDisplayName());
        }
        return sb.append("]").toString();
    }

    private static String gear(NBTTagCompound nbt) {
        StringBuilder sb = new StringBuilder("[");
        for (String key : new String[]{"HandItems", "ArmorItems"}) {
            NBTTagList list = nbt.getTagList(key, 10);
            for (int i = 0; i < list.tagCount(); i++) {
                ItemStack s = new ItemStack(list.getCompoundTagAt(i));
                if (s.isEmpty()) continue;
                if (sb.length() > 1) sb.append(", ");
                sb.append(s.getDisplayName());
            }
        }
        return sb.append("]").toString();
    }
}
