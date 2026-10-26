package com.jubitus.travellers.zone.stable;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;

/**
 * A tiny persisted snapshot of the Stable-relevant item counts sitting in the zone's chests
 * ({@link #ITEMS}), plus how many of each the offscreen simulation has already spent but not yet
 * removed from the real chests.
 *
 * <p>This is what lets {@link StableWork} run identically whether the Stable is loaded or not:
 * the sim only ever reads/decrements this mirror, never a {@code TileEntity}. While the zone is
 * loaded, {@code StablePhysicalReconciler} keeps it honest - it drains any accrued offscreen spend
 * out of the physical chests, then re-imports the real counts every pass.
 */
public final class StableChestMirror {

    /** Every item the stable economy spends, and its NBT key below. */
    static final Item[] ITEMS = {Items.WHEAT, Items.SADDLE, Items.GOLDEN_CARROT, Items.GOLDEN_APPLE,
            StableWork.HAY_BALE, Items.SUGAR, Items.APPLE};
    private static final String[] KEYS = {"Wheat", "Saddle", "GCarrot", "GApple", "HayBale", "Sugar", "Apple"};

    /** Believed-available counts (what the sim may still draw from). */
    private final int[] counts = new int[ITEMS.length];
    /** Offscreen consumption not yet subtracted from the physical chests. */
    private final int[] spent = new int[ITEMS.length];

    private static int indexOf(Item item) {
        for (int i = 0; i < ITEMS.length; i++) {
            if (ITEMS[i] == item) return i;
        }
        return -1;
    }

    public int available(Item item) {
        int i = indexOf(item);
        return i < 0 ? 0 : counts[i];
    }

    /** Draws one {@code item} from the mirror, recording it as unreconciled spend. Returns false
     * (and changes nothing) if the mirror believes there is none left. */
    public boolean consume(Item item) {
        int i = indexOf(item);
        if (i < 0 || counts[i] <= 0) return false;
        counts[i]--;
        spent[i]++;
        return true;
    }

    /** Re-seeds the believed count of {@code item} from a fresh physical chest scan (loaded only). */
    public void importCount(Item item, int count) {
        int i = indexOf(item);
        if (i >= 0) counts[i] = Math.max(0, count);
    }

    /** How many of {@code item} the offscreen sim spent that still need removing from the physical
     * chests; zeroes that counter (the caller is expected to actually remove them). */
    public int drainSpent(Item item) {
        int i = indexOf(item);
        if (i < 0) return 0;
        int n = spent[i];
        spent[i] = 0;
        return n;
    }

    public void writeToNBT(NBTTagCompound tag) {
        NBTTagCompound m = new NBTTagCompound();
        for (int i = 0; i < ITEMS.length; i++) {
            m.setInteger(KEYS[i], counts[i]);
            m.setInteger("Spent" + KEYS[i], spent[i]);
        }
        tag.setTag("ChestMirror", m);
    }

    public void readFromNBT(NBTTagCompound tag) {
        if (!tag.hasKey("ChestMirror")) return;
        NBTTagCompound m = tag.getCompoundTag("ChestMirror");
        for (int i = 0; i < ITEMS.length; i++) {
            counts[i] = m.getInteger(KEYS[i]);
            spent[i] = m.getInteger("Spent" + KEYS[i]);
        }
    }
}
