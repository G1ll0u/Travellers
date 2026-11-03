package com.jubitus.travellers.gui;

import com.jubitus.travellers.worker.gui.GuiWorkerBehavior;
import com.jubitus.travellers.village.ContainerWorkerVillageRelations;
import com.jubitus.travellers.village.gui.GuiWorkerVillageRelations;
import com.jubitus.travellers.worker.gui.GuiWorkerGo;
import com.jubitus.travellers.zone.gui.GuiPlayerZonePrompt;
import com.jubitus.travellers.worker.gui.GuiWorkerRecruitCultures;
import com.jubitus.travellers.worker.gui.GuiWorker;
import com.jubitus.travellers.worker.gui.GuiWorkerInventory;
import com.jubitus.travellers.unit.EntityTravellerWorker;
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
        if (id == TravellersGuiIds.WORKER_PICKUP) {
            Entity e = world.getEntityByID(x);
            return (e instanceof EntityTravellerWorker)
                    ? new com.jubitus.travellers.worker.gui.GuiWorkerPickup(player, (EntityTravellerWorker) e, y != 0)
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
                    ? new GuiWorkerRecruitCultures(player, (EntityTravellerWorker) e)
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
