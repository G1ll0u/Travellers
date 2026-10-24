package com.jubitus.travellers.unit;

import com.jubitus.travellers.village.MillMoney;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import javax.annotation.Nullable;

/**
 * Money a hired worker carries for its owner, for any mission (a recruiting purse today): the mission spends
 * it and hands the rest back when it ends; dismissing the worker hands it back too; it falls as coins where the
 * worker dies in view and is lost with a worker that dies offscreen. One on the worker, one on its record,
 * copied at each hand-off.
 */
public final class WorkerPurse {

    public static final String NBT_KEY = "Purse";

    private int deniers;

    public int get() {
        return deniers;
    }

    public void set(int deniers) {
        this.deniers = Math.max(0, deniers);
    }

    public void add(int amount) {
        set(deniers + amount);
    }

    public boolean spend(int amount) {
        if (amount > deniers) return false;
        deniers -= amount;
        return true;
    }

    /** As much as the owner can carry goes back to them (never a lost coin); returns the amount handed over. */
    public int handTo(@Nullable EntityPlayer owner) {
        if (deniers <= 0 || !(owner instanceof EntityPlayerMP)) return 0;
        int given = Math.min(deniers, MillMoney.giveWhatFits((EntityPlayerMP) owner, deniers));
        deniers -= given;
        return given;
    }

    /** The coins fall where {@code at} stands. */
    public void drop(Entity at) {
        for (ItemStack coins : MillMoney.coins(deniers)) at.entityDropItem(coins, 0.5F);
        deniers = 0;
    }

    public void write(NBTTagCompound nbt) {
        nbt.setInteger(NBT_KEY, deniers);
    }

    public void read(NBTTagCompound nbt) {
        set(nbt.getInteger(NBT_KEY));
    }
}
