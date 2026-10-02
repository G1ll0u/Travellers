package com.jubitus.travellers.worker.inventory;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

public class SlotEntityEquipment extends Slot {

    private final EntityLivingBase entity;
    private final EntityEquipmentSlot equipmentSlot;

    public SlotEntityEquipment(EntityLivingBase entity, EntityEquipmentSlot slot, int x, int y) {
        super(new InventoryBasic("slot", false, 1), 0, x, y);
        this.entity = entity;
        this.equipmentSlot = slot;
    }

    @Override
    public ItemStack getStack() {
        return entity.getItemStackFromSlot(equipmentSlot);
    }

    @Override
    public void putStack(ItemStack stack) {
        entity.setItemStackToSlot(equipmentSlot, stack.isEmpty() ? ItemStack.EMPTY : stack);
        onSlotChanged();
    }

    @Override
    public boolean isItemValid(ItemStack stack) {
        if (stack.isEmpty()) return true;
        switch (equipmentSlot) {
            case MAINHAND:
                return true;
            case HEAD:
            case CHEST:
            case LEGS:
            case FEET:
                Item item = stack.getItem();
                if (item instanceof ItemArmor)
                    return ((ItemArmor) item).armorType == equipmentSlot;
                try {
                    return item.isValidArmor(stack, equipmentSlot, entity);
                } catch (Throwable t) {
                    return false;
                }
            default:
                return false;
        }
    }

    @Override
    public ItemStack decrStackSize(int amount) {
        ItemStack current = getStack();
        entity.setItemStackToSlot(equipmentSlot, ItemStack.EMPTY);
        return current;
    }

    @Override
    public boolean getHasStack() {
        return !getStack().isEmpty();
    }

    @Override
    public int getSlotStackLimit() {
        return 1;
    }
}
