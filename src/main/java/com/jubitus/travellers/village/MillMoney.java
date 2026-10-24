package com.jubitus.travellers.village;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.millenaire.common.item.MillItems;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.util.ArrayList;
import java.util.List;

/** The player's Millénaire money: the one place that counts, spends, gives and formats deniers. */
public final class MillMoney {

    private MillMoney() {
    }

    /** Millénaire's short price text (e.g. "1o 2a 3d"). */
    public static String format(int deniers) {
        return MillCommonUtilities.getShortPrice(deniers);
    }

    /** The fewest coin stacks (gold, silver, then bronze deniers) worth {@code deniers}. */
    public static List<ItemStack> coins(int deniers) {
        List<ItemStack> out = new ArrayList<>();
        if (deniers <= 0) return out;
        addStacks(out, MillItems.DENIER_OR, deniers / (64 * 64));
        addStacks(out, MillItems.DENIER_ARGENT, deniers / 64 % 64);
        addStacks(out, MillItems.DENIER, deniers % 64);
        return out;
    }

    private static void addStacks(List<ItemStack> out, Item coin, int count) {
        int max = new ItemStack(coin).getMaxStackSize();
        for (; count > 0; count -= max) out.add(new ItemStack(coin, Math.min(max, count)));
    }

    public static int countPlayerMoney(EntityPlayer player) {
        if (player == null) return 0;
        return MillCommonUtilities.countMoney(player.inventory);
    }

    public static boolean canAfford(EntityPlayer player, int amount) {
        return amount <= 0 || countPlayerMoney(player) >= amount;
    }

    public static boolean trySpendPlayerMoney(EntityPlayerMP player, int amount) {
        if (player == null) return false;
        if (amount <= 0) {
            syncPlayerInventory(player);
            return true;
        }

        int before = countPlayerMoney(player);
        if (before < amount) {
            syncPlayerInventory(player);
            return false;
        }

        MillCommonUtilities.changeMoney(player.inventory, -amount, player);
        syncPlayerInventory(player);

        int after = countPlayerMoney(player);
        return after == before - amount;
    }

    public static boolean givePlayerMoney(EntityPlayerMP player, int amount) {
        if (player == null) return false;
        if (amount <= 0) {
            syncPlayerInventory(player);
            return true;
        }

        int before = countPlayerMoney(player);

        MillCommonUtilities.changeMoney(player.inventory, amount, player);
        syncPlayerInventory(player);

        int after = countPlayerMoney(player);
        return after == before + amount;
    }

    public static void syncPlayerInventory(EntityPlayerMP player) {
        if (player == null) return;

        player.inventory.markDirty();

        if (player.inventoryContainer != null) {
            player.inventoryContainer.detectAndSendChanges();
            player.sendContainerToPlayer(player.inventoryContainer);
        }

        if (player.openContainer != null && player.openContainer != player.inventoryContainer) {
            player.openContainer.detectAndSendChanges();
        }

        player.updateHeldItem();
    }
}