package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == TravellersGuiIds.WORKER) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new ContainerWorker(player.inventory, (EntityTravellerWorker) e);
            }
        }

        if (id == TravellersGuiIds.WORKER_HUB) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new ContainerWorkerHub(player.inventory, (EntityTravellerWorker) e);
            }
        }

        if (id == TravellersGuiIds.WORKER_ORDERS) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new ContainerWorkerFollow(player.inventory, (EntityTravellerWorker) e);
            }
        }
        if (id == TravellersGuiIds.WORKER_INV) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new ContainerWorkerInventory(player.inventory, (EntityTravellerWorker) e);
            }
        }
        return null;
    }


    @Override
    @SideOnly(Side.CLIENT)
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == TravellersGuiIds.WORKER) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new GuiWorker(player, (EntityTravellerWorker) e);
            }
        }

        if (id == TravellersGuiIds.WORKER_HUB) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new GuiWorkerHub(player, (EntityTravellerWorker) e);
            }
        }

        if (id == TravellersGuiIds.WORKER_ORDERS) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new GuiWorkerOrders(player, (EntityTravellerWorker) e);
            }
        }
        // NEW: personal inventory
        if (id == TravellersGuiIds.WORKER_INV) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new GuiWorkerInventory(player, (EntityTravellerWorker) e);
            }
        }

        return null;
    }


}

