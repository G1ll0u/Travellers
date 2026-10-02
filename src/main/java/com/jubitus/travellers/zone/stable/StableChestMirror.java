package com.jubitus.travellers.zone.stable;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;

/**
 * A tiny persisted snapshot of the four Stable-relevant item counts sitting in the zone's chest
 * ({@code WHEAT}, {@code SADDLE}, {@code GOLDEN_CARROT}, {@code GOLDEN_APPLE}), plus how many of each
 * the offscreen simulation has already spent but not yet removed from the real chest.
 *
 * <p>This is what lets {@link StableWork} run identically whether the Stable is loaded or not:
 * the sim only ever reads/decrements this mirror, never a {@code TileEntity}. While the zone is
 * loaded, {@code StableCoordinator.reconcilePhysical} keeps it honest - it re-imports the real chest
 * counts every pass and drains any accrued offscreen spend back out of the physical chest. Same
 * "elapsed-time bookkeeping against a light mirror" idea the camp system uses for build progress.
 */
public final class StableChestMirror {

    /** Believed-available counts (what the sim may still draw from). */
    public int wheat, saddle, goldenCarrot, goldenApple;
    /** Offscreen consumption not yet subtracted from the physical chest - drained on the next
     * loaded reconcile pass. */
    public int spentWheat, spentSaddle, spentGoldenCarrot, spentGoldenApple;

    public int available(Item item) {
        if (item == Items.WHEAT) return wheat;
        if (item == Items.SADDLE) return saddle;
        if (item == Items.GOLDEN_CARROT) return goldenCarrot;
        if (item == Items.GOLDEN_APPLE) return goldenApple;
        return 0;
    }

    /** Draws one {@code item} from the mirror, recording it as unreconciled spend. Returns false
     * (and changes nothing) if the mirror believes there is none left. */
    public boolean consume(Item item) {
        if (available(item) <= 0) return false;
        if (item == Items.WHEAT) { wheat--; spentWheat++; }
        else if (item == Items.SADDLE) { saddle--; spentSaddle++; }
        else if (item == Items.GOLDEN_CARROT) { goldenCarrot--; spentGoldenCarrot++; }
        else if (item == Items.GOLDEN_APPLE) { goldenApple--; spentGoldenApple++; }
        else return false;
        return true;
    }

    /** Re-seeds the believed counts from a fresh physical chest scan (called only while loaded). The
     * spend counters are left untouched here - {@link #drainSpent} handles removing them from the
     * real chest first, in the same reconcile pass. */
    public void importCounts(int wheat, int saddle, int goldenCarrot, int goldenApple) {
        this.wheat = Math.max(0, wheat);
        this.saddle = Math.max(0, saddle);
        this.goldenCarrot = Math.max(0, goldenCarrot);
        this.goldenApple = Math.max(0, goldenApple);
    }

    /** How many of {@code item} the offscreen sim spent that still need removing from the physical
     * chest; zeroes that counter (the caller is expected to actually remove them). */
    public int drainSpent(Item item) {
        int n;
        if (item == Items.WHEAT) { n = spentWheat; spentWheat = 0; }
        else if (item == Items.SADDLE) { n = spentSaddle; spentSaddle = 0; }
        else if (item == Items.GOLDEN_CARROT) { n = spentGoldenCarrot; spentGoldenCarrot = 0; }
        else if (item == Items.GOLDEN_APPLE) { n = spentGoldenApple; spentGoldenApple = 0; }
        else n = 0;
        return n;
    }

    public void writeToNBT(NBTTagCompound tag) {
        NBTTagCompound m = new NBTTagCompound();
        m.setInteger("Wheat", wheat);
        m.setInteger("Saddle", saddle);
        m.setInteger("GCarrot", goldenCarrot);
        m.setInteger("GApple", goldenApple);
        m.setInteger("SpentWheat", spentWheat);
        m.setInteger("SpentSaddle", spentSaddle);
        m.setInteger("SpentGCarrot", spentGoldenCarrot);
        m.setInteger("SpentGApple", spentGoldenApple);
        tag.setTag("ChestMirror", m);
    }

    public void readFromNBT(NBTTagCompound tag) {
        if (!tag.hasKey("ChestMirror")) return;
        NBTTagCompound m = tag.getCompoundTag("ChestMirror");
        wheat = m.getInteger("Wheat");
        saddle = m.getInteger("Saddle");
        goldenCarrot = m.getInteger("GCarrot");
        goldenApple = m.getInteger("GApple");
        spentWheat = m.getInteger("SpentWheat");
        spentSaddle = m.getInteger("SpentSaddle");
        spentGoldenCarrot = m.getInteger("SpentGCarrot");
        spentGoldenApple = m.getInteger("SpentGApple");
    }
}
