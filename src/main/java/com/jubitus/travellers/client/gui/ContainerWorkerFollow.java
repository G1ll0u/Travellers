package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;

public class ContainerWorkerFollow extends Container {

    private final EntityTravellerWorker worker;

    public ContainerWorkerFollow(InventoryPlayer playerInv, EntityTravellerWorker worker) {
        this.worker = worker;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer playerIn, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return worker != null
                && worker.isEntityAlive()
                && worker.getDistanceSq(playerIn) <= 64.0D;
    }
}
