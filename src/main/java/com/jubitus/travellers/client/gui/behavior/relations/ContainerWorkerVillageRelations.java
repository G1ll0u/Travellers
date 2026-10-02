package com.jubitus.travellers.client.gui.behavior.relations;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;

public class ContainerWorkerVillageRelations extends Container {

    private final EntityTravellerWorker worker;
    private final EntityPlayer player;

    public ContainerWorkerVillageRelations(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
    }

    @Override
    public boolean canInteractWith(EntityPlayer playerIn) {
        return worker != null
                && worker.isEntityAlive()
                && playerIn.getDistanceSq(worker) <= 64.0D;
    }

}