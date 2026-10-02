package com.jubitus.travellers.gui;

import com.jubitus.travellers.worker.inventory.ContainerWorker;
import com.jubitus.travellers.worker.inventory.ContainerWorkerInventory;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;

public class CommonGuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == TravellersGuiIds.WORKER) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorker(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }

        if (id == TravellersGuiIds.WORKER_INV) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerInventory(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }

        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return new ClientGuiHandler().getClientGuiElement(id, player, world, x, y, z);
    }
}