package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.client.gui.behavior.GuiWorkerBehavior;
import com.jubitus.travellers.client.gui.behavior.relations.ContainerWorkerVillageRelations;
import com.jubitus.travellers.client.gui.behavior.relations.GuiWorkerVillageRelations;
import com.jubitus.travellers.client.gui.go.GuiWorkerGo;
import com.jubitus.travellers.client.gui.home.GuiPlayerZonePrompt;
import com.jubitus.travellers.client.gui.recruit.GuiWorkerRecruit;
import com.jubitus.travellers.client.gui.transport.GuiWorker;
import com.jubitus.travellers.client.gui.transport.GuiWorkerInventory;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.server.gui.CommonGuiHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
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
            return new GuiWorkerBehavior(player, e instanceof EntityTravellerWorker ? (EntityTravellerWorker) e : null, null);
        }
        if (id == TravellersGuiIds.LONE_BARRACK_PROMPT) {
            return new GuiPlayerZonePrompt(player, new BlockPos(x, y, z));
        }
        if (id == TravellersGuiIds.WORKER_GO) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new GuiWorkerGo(player, (EntityTravellerWorker) e)
                    : null;
        }


// client gui
        if (id == TravellersGuiIds.WORKER_VILLAGE_RELATIONS) {
            Entity e = world.getEntityByID(x);
            if (e instanceof EntityTravellerWorker) {
                return new GuiWorkerVillageRelations(player, (EntityTravellerWorker) e);
            }
        }
        return null;
    }
}
