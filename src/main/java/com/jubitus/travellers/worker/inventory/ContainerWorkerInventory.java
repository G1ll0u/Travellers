package com.jubitus.travellers.worker.inventory;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public class ContainerWorkerInventory extends Container {

    public static final int ARMOR_COUNT = 5; // head, chest, legs, feet, shield (offhand)
    static final int BACKPACK_COUNT = 18;
    static final int PLAYER_INV_COUNT = 27;
    static final int HOTBAR_COUNT = 9;

    static final int ARMOR_START = 0;
    static final int ARMOR_END = ARMOR_START + ARMOR_COUNT;
    static final int BACKPACK_START = ARMOR_END;
    static final int BACKPACK_END = BACKPACK_START + BACKPACK_COUNT;
    static final int PLAYER_START = BACKPACK_END;
    static final int PLAYER_END = PLAYER_START + PLAYER_INV_COUNT;
    static final int HOTBAR_START = PLAYER_END;
    static final int HOTBAR_END = HOTBAR_START + HOTBAR_COUNT;

    private final EntityTravellerWorker worker;

    public ContainerWorkerInventory(InventoryPlayer playerInv, EntityTravellerWorker worker) {
        this.worker = worker;

        // Slots 0-3: armor (HEAD, CHEST, LEGS, FEET)
        EntityEquipmentSlot[] armorOrder = {
            EntityEquipmentSlot.HEAD, EntityEquipmentSlot.CHEST,
            EntityEquipmentSlot.LEGS, EntityEquipmentSlot.FEET,
            EntityEquipmentSlot.OFFHAND
        };
        for (int i = 0; i < ARMOR_COUNT; i++) {
            addSlotToContainer(new SlotEntityEquipment(worker, armorOrder[i], 8 + i * 18, 18));
        }

        // Slots 4-21: backpack (entity.inventory, 2x9)
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new SlotItemHandler(worker.inventory, col + row * 9,
                        8 + col * 18, 40 + row * 18));
            }
        }

        // Slots 22-48: player inventory (3x9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlotToContainer(new Slot(playerInv, col + row * 9 + 9,
                        8 + col * 18, 90 + row * 18));
            }
        }

        // Slots 49-57: hotbar
        for (int i = 0; i < HOTBAR_COUNT; i++) {
            addSlotToContainer(new Slot(playerInv, i, 8 + i * 18, 148));
        }
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int index) {
        Slot slot = inventorySlots.get(index);
        if (slot == null || !slot.getHasStack()) return ItemStack.EMPTY;

        ItemStack stack = slot.getStack();
        ItemStack original = stack.copy();

        if (index < ARMOR_END) {
            // Armor → backpack, then player inventory
            if (!mergeItemStack(stack, BACKPACK_START, BACKPACK_END, false)
                    && !mergeItemStack(stack, PLAYER_START, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index < BACKPACK_END) {
            // Backpack → armor slots if applicable, then player inventory
            if (!mergeItemStack(stack, ARMOR_START, ARMOR_END, false)
                    && !mergeItemStack(stack, PLAYER_START, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            // Player/hotbar → armor slots if applicable, then backpack
            if (!mergeItemStack(stack, ARMOR_START, ARMOR_END, false)
                    && !mergeItemStack(stack, BACKPACK_START, BACKPACK_END, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) slot.putStack(ItemStack.EMPTY);
        else slot.onSlotChanged();

        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public void onContainerClosed(EntityPlayer player) {
        super.onContainerClosed(player);
        if (!worker.world.isRemote) {
            worker.equipment.syncEquipmentFromInventory();
        }
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return worker.isEntityAlive() && worker.getDistanceSq(player) <= 64.0D;
    }
}
