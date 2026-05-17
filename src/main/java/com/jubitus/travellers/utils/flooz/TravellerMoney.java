package com.jubitus.travellers.utils.flooz;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import org.millenaire.common.utilities.MillCommonUtilities;

public final class TravellerMoney {

    private TravellerMoney() {
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