package com.jubitus.travellers.server.gui;

import com.jubitus.travellers.client.gui.*;
import com.jubitus.travellers.client.gui.bahavior.ContainerWorkerBehavior;
import com.jubitus.travellers.client.gui.debug.ContainerNull;
import com.jubitus.travellers.client.gui.go.ContainerWorkerGo;
import com.jubitus.travellers.client.gui.orders.ContainerWorkerFollow;
import com.jubitus.travellers.client.gui.recruit.ContainerWorkerRecruit;
import com.jubitus.travellers.client.gui.transport.ContainerWorker;
import com.jubitus.travellers.client.gui.transport.ContainerWorkerInventory;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
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
        if (id == TravellersGuiIds.WORKER_HUB) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerHub(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_ORDERS) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerFollow(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_INV) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerInventory(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_RECRUIT) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerRecruit(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_BEHAVIOR) {
            Entity e = world.getEntityByID(x);


            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerBehavior(player.inventory, (EntityTravellerWorker) e)
                    : null;
        }

        if (id == TravellersGuiIds.HOME_NAME_PROMPT) {
            return new ContainerNull();
        }
        if (id == TravellersGuiIds.WORKER_HOME_LIST) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerNull()
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_GO) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new ContainerWorkerGo(player.inventory, (EntityTravellerWorker) e) // ou ContainerNull si tu n'as pas de slots
                    : null;
        }


        return null;
    }

    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        // On integrated server, this may be called; delegate to the real client handler.
        return new ClientGuiHandler().getClientGuiElement(id, player, world, x, y, z);
    }




}