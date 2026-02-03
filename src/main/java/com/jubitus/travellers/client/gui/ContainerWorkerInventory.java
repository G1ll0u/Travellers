package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

public class ContainerWorkerInventory extends Container {

    private static final int WORKER_SLOTS = 18;

    private final EntityTravellerWorker worker;
    private final InventoryPlayer playerInv;

    public ContainerWorkerInventory(InventoryPlayer playerInv, EntityTravellerWorker worker) {
        this.worker = worker;
        this.playerInv = playerInv;

        // Worker personal inventory (2x9)
        ItemStackHandler inv = worker.getPersonalInventory();
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlotToContainer(
                        new SlotItemHandler(inv, index, x, y)
                );
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
                // From player -> worker
                if (!this.mergeItemStack(stack, workerStart, workerEnd, false)) {
                    return ItemStack.EMPTY;
                }
            }

            if (stack.isEmpty()) {
                slot.putStack(ItemStack.EMPTY);
            } else {
                slot.onSlotChanged();
            }

            if (stack.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot.onTake(playerIn, stack);
        }

        return itemstack;
    }

    @Override
    public void onContainerClosed(EntityPlayer playerIn) {
        super.onContainerClosed(playerIn);
        // No rollback: this is the worker’s own storage
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return worker.isEntityAlive()
                && worker.getDistanceSq(playerIn) <= 64.0D;
    }
}
