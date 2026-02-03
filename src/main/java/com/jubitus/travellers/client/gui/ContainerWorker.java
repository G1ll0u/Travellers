package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

public class ContainerWorker extends Container {

    private static final int WORKER_SLOTS = 18;

    private final EntityTravellerWorker worker;
    private final InventoryPlayer playerInv;
    private boolean jobStarted = false; // NEW

    public ContainerWorker(InventoryPlayer playerInv, EntityTravellerWorker worker) {
        this.worker = worker;
        this.playerInv = playerInv;

        // Worker inventory (2x9)
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlotToContainer(
                        new SlotItemHandler(worker.getTravelInventory(), index, x, y)
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
                return ItemStack.EMPTY; // nothing changed, avoid loop
            }

            slot.onTake(playerIn, stack);
        }

        return itemstack;
    }

    @Override
    public void onContainerClosed(EntityPlayer playerIn) {
        if (!playerIn.world.isRemote) {

            // 1) If worker is idle AND no job was started, rollback cargo to player
            if (worker != null && worker.isEntityAlive() && !jobStarted) {
                ItemStackHandler cargo = worker.getTravelInventory();

                for (int i = 0; i < cargo.getSlots(); i++) {
                    ItemStack stack = cargo.getStackInSlot(i);
                    if (stack.isEmpty()) continue;

                    ItemStack toReturn = cargo.extractItem(i, stack.getCount(), false);
                    if (toReturn.isEmpty()) continue;

                    boolean added = playerIn.inventory.addItemStackToInventory(toReturn);

                    if (!added && !toReturn.isEmpty()) {
                        playerIn.dropItem(toReturn, false);
                    }
                }

                // Make sure the player inventory is marked dirty
                playerIn.inventory.markDirty();

                // Force a sync to the client
                if (playerIn instanceof EntityPlayerMP) {
                    ((EntityPlayerMP) playerIn)
                            .sendContainerToPlayer(playerIn.inventoryContainer);
                }
            }

            // 2) If this was the pickup GUI after a job, start farewell phase
            if (worker != null && worker.isWaitingForPickup()) {
                worker.onLootCollected(playerIn);
            }

            // Optionally also sync this container once more
            this.detectAndSendChanges();
        }

        // 3) Only now call super (removes listener etc.)
        super.onContainerClosed(playerIn);
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return worker.isEntityAlive()
                && worker.getDistanceSq(playerIn) <= 64.0D;
    }

}
