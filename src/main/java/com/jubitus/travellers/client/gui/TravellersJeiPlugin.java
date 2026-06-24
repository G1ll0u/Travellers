package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.client.gui.bahavior.GuiWorkerBehavior;
import com.jubitus.travellers.client.gui.bahavior.relations.GuiWorkerVillageRelations;
import com.jubitus.travellers.client.gui.go.GuiWorkerGo;
import com.jubitus.travellers.client.gui.orders.GuiWorkerOrders;
import com.jubitus.travellers.client.gui.recruit.GuiWorkerRecruit;
import com.jubitus.travellers.client.gui.transport.GuiWorker;
import com.jubitus.travellers.client.gui.transport.GuiWorkerInventory;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.gui.IAdvancedGuiHandler;
import net.minecraft.client.gui.inventory.GuiContainer;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JEIPlugin
public class TravellersJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        registerFullScreenGui(registry, GuiWorker.class);
        registerFullScreenGui(registry, GuiWorkerInventory.class);
    }

    private static <T extends GuiContainer> void registerFullScreenGui(IModRegistry registry, Class<T> guiClass) {
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<T>() {
            @Override
            public Class<T> getGuiContainerClass() {
                return guiClass;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(T gui) {
                return Collections.singletonList(new Rectangle(0, 0, gui.width, gui.height));
            }

            @Override
            public Object getIngredientUnderMouse(T gui, int mouseX, int mouseY) {
                return null;
            }
        });
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
    }
}