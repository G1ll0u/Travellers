package com.jubitus.travellers.worker.inventory;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/**
 * A worker's cargo over the player's inventory. Transport: the cargo is a staging area, given back on close unless
 * a trip started. Pickup (back from a trip): the player can only take; what is left stays with the worker.
 */
public class ContainerWorker extends Container {

    private static final int WORKER_SLOTS = 18;
    /** {@link #enchantItem} button id: move every cargo stack that fits into the player's inventory. */
    public static final int TAKE_ALL = 0;

    private final EntityTravellerWorker worker;
    private final boolean pickup;
    private boolean jobStarted = false;

    public ContainerWorker(InventoryPlayer playerInv, EntityTravellerWorker worker, boolean pickup) {
        this.worker = worker;
        this.pickup = pickup;

        // Worker inventory (2x9)
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlotToContainer(new SlotItemHandler(worker.getTravelInventory(), index, x, y) {
                    @Override
                    public boolean isItemValid(ItemStack stack) {
                        return !pickup && super.isItemValid(stack);
                    }
                });
            }
        }

        // Player inventory (3x9)
        int invY = 50 + 18;
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlotToContainer(new Slot(playerInv, col + row * 9 + 9,
                        8 + col * 18, invY + row * 18));
            }
        }

        // Hotbar
        int hotbarY = invY + 58;
        for (int i = 0; i < 9; ++i) {
            this.addSlotToContainer(new Slot(playerInv, i, 8 + i * 18, hotbarY));
        }
    }

    public void markJobStarted() {
        this.jobStarted = true;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer playerIn, int index) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot = this.inventorySlots.get(index);

        if (slot != null && slot.getHasStack()) {
            ItemStack stack = slot.getStack();
            itemstack = stack.copy();

            int workerStart = 0;
            int workerEnd = workerStart + WORKER_SLOTS; // 18
            int playerInvStart = workerEnd;             // 18
            int playerInvEnd = playerInvStart + 27;     // 45
            int hotbarStart = playerInvEnd;             // 45
            int hotbarEnd = hotbarStart + 9;            // 54

            if (index < workerEnd) {
                // From worker -> player
                if (!this.mergeItemStack(stack, playerInvStart, hotbarEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // From player -> worker (never into goods brought back)
                if (pickup || !this.mergeItemStack(stack, workerStart, workerEnd, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                slot.putStack(ItemStack.EMPTY);
            } else {
                slot.onSlotChanged();
            }

            if (stack.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY; // nothing changed, avoid loop
            }

            slot.onTake(playerIn, stack);
        }

        return itemstack;
    }

    @Override
    public boolean enchantItem(EntityPlayer playerIn, int id) {
        if (id != TAKE_ALL) return false;
        for (int i = 0; i < WORKER_SLOTS; i++) transferStackInSlot(playerIn, i);
        return true;
    }

    public boolean isCargoEmpty() {
        for (int i = 0; i < WORKER_SLOTS; i++) {
            if (getSlot(i).getHasStack()) return false;
        }
        return true;
    }

    @Override
    public void onContainerClosed(EntityPlayer playerIn) {
        if (!playerIn.world.isRemote && worker != null && worker.isEntityAlive()) {
            if (pickup) {
                if (isCargoEmpty()) worker.delivery.onLootCollected(playerIn);
                else NotificationService.notify(playerIn, NotificationDefs.WORKER_GOODS_LEFT, worker.getName());
            } else if (!jobStarted) {
                giveCargoBack(playerIn);
            }
            this.detectAndSendChanges();
        }
        super.onContainerClosed(playerIn);
    }

    // Transport closed without sending: the staged cargo goes back to the player.
    private void giveCargoBack(EntityPlayer playerIn) {
        ItemStackHandler cargo = worker.getTravelInventory();
        for (int i = 0; i < cargo.getSlots(); i++) {
            ItemStack stack = cargo.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            ItemStack toReturn = cargo.extractItem(i, stack.getCount(), false);
            if (!toReturn.isEmpty() && !playerIn.inventory.addItemStackToInventory(toReturn) && !toReturn.isEmpty()) {
                playerIn.dropItem(toReturn, false);
            }
        }
        playerIn.inventory.markDirty();
        if (playerIn instanceof EntityPlayerMP) {
            ((EntityPlayerMP) playerIn).sendContainerToPlayer(playerIn.inventoryContainer);
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return worker.isEntityAlive()
                && worker.getDistanceSq(playerIn) <= 64.0D;
    }

}
