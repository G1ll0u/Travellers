package com.jubitus.travellers.client.server;

import com.jubitus.travellers.client.gui.*;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientGuiHandler extends CommonGuiHandler {
    @Override
    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        if (id == TravellersGuiIds.WORKER) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorker(player, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_HUB) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorkerHub(player, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_ORDERS) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorkerOrders(player, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_INV) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorkerInventory(player, (EntityTravellerWorker) e)
                    : null;
        }
        if (id == TravellersGuiIds.WORKER_RECRUIT) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorkerRecruit(player, (EntityTravellerWorker) e)
                    : null;
        }

        if (id == TravellersGuiIds.WORKER_BEHAVIOR) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorkerBehavior(player, (EntityTravellerWorker) e)
                    : null;
        }
        return null;
    }
}
