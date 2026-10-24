package com.jubitus.travellers.village;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.millenaire.common.item.MillItems;
import org.millenaire.common.utilities.MillCommonUtilities;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The player's Millénaire money: the one place that counts, spends, gives and formats deniers. */
public final class MillMoney {

    private MillMoney() {
    }

    /** An amount inside player-facing text; {@code gui/MoneyText} draws it as coin icons. */
    public static final Pattern AMOUNT = Pattern.compile("\u00a7\\$(\\d{1,9});");

    /** Gold, silver, then bronze deniers, the order of {@link #split}. */
    public static Item[] coinItems() {
        return new Item[]{MillItems.DENIER_OR, MillItems.DENIER_ARGENT, MillItems.DENIER};
    }

    /** Player-facing text for an amount (an {@link #AMOUNT} token): sent as is in notices and labels. */
    public static String format(int deniers) {
        return "\u00a7$" + Math.max(0, deniers) + ";";
    }

    /** Coin counts {gold, silver, bronze} worth {@code deniers}. */
    public static int[] split(int deniers) {
        int d = Math.max(0, deniers);
        return new int[]{d / (64 * 64), d / 64 % 64, d % 64};
    }

    /** The fewest coin stacks (gold, silver, then bronze deniers) worth {@code deniers}. */
    public static List<ItemStack> coins(int deniers) {
        List<ItemStack> out = new ArrayList<>();
        int[] counts = split(deniers);
        Item[] items = coinItems();
        for (int i = 0; i < counts.length; i++) addStacks(out, items[i], counts[i]);
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

    /**
     * Takes {@code amount} from the player if they have it. Millénaire silently loses change that does not fit
     * in a full inventory: it is handed back, and what still does not fit falls at the player's feet.
     */
    public static boolean trySpendPlayerMoney(EntityPlayerMP player, int amount) {
        if (player == null) return false;
        int before = countPlayerMoney(player);
        boolean paid = amount <= 0 || before >= amount;
        if (paid && amount > 0) {
            MillCommonUtilities.changeMoney(player.inventory, -amount, player);
            int lost = before - amount - countPlayerMoney(player);
            if (lost > 0) {
                lost -= giveWhatFits(player, lost);
                for (ItemStack stack : coins(lost)) player.dropItem(stack, false);
            }
        }
        syncPlayerInventory(player);
        return paid;
    }

    /**
     * Hands up to {@code amount} to the player without ever losing a coin: all of it into a Millénaire purse
     * the player carries, else the coin stacks that fit in the inventory. Returns what was handed over.
     */
    public static int giveWhatFits(EntityPlayerMP player, int amount) {
        if (player == null || amount <= 0) return 0;
        int given = 0;
        if (carriesMillPurse(player)) {
            int before = countPlayerMoney(player);
            MillCommonUtilities.changeMoney(player.inventory, amount, player);
            given = countPlayerMoney(player) - before;
        } else {
            for (ItemStack stack : coins(amount)) {
                int value = coinValue(stack.getItem());
                int before = stack.getCount();
                player.inventory.addItemStackToInventory(stack); // shrinks the stack by what went in
                given += (before - stack.getCount()) * value;
            }
        }
        syncPlayerInventory(player);
        return Math.max(0, given);
    }

    private static boolean carriesMillPurse(EntityPlayer player) {
        for (ItemStack s : player.inventory.mainInventory) if (s.getItem() == MillItems.PURSE) return true;
        return false;
    }

    public static int coinValue(Item coin) {
        return coin == MillItems.DENIER_OR ? 64 * 64 : coin == MillItems.DENIER_ARGENT ? 64 : 1;
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