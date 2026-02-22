package com.jubitus.travellers.client.gui.go;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;

public class ContainerWorkerGo extends Container {
    public ContainerWorkerGo(IInventory playerInv, EntityTravellerWorker worker) {}
    @Override public boolean canInteractWith(EntityPlayer playerIn) { return true; }
}