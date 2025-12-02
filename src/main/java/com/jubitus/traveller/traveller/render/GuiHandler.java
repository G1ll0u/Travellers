package com.jubitus.traveller.traveller.render;

import com.jubitus.traveller.traveller.entityAI.EntityTravellerWorker;
import com.jubitus.traveller.traveller.utils.debug.ContainerWorker;
import com.jubitus.traveller.traveller.utils.debug.TravellersGuiIds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.IGuiHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class GuiHandler implements IGuiHandler {

    @Override
    public Object getServerGuiElement(int id, EntityPlayer player, World world,
                                      int x, int y, int z) {
        if (id == TravellersGuiIds.WORKER) {
            int entityId = x; // we packed it in here
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new ContainerWorker(player.inventory, (EntityTravellerWorker) e);
            }
        }
        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public Object getClientGuiElement(int id, EntityPlayer player, World world,
                                      int x, int y, int z) {
        if (id == TravellersGuiIds.WORKER) {
            int entityId = x;
            Entity e = world.getEntityByID(entityId);
            if (e instanceof EntityTravellerWorker) {
                return new GuiWorker(player, (EntityTravellerWorker) e);
            }
        }
        return null;
    }
}

