package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.client.gui.go.GuiWorkerGo;
import com.jubitus.travellers.client.gui.home.GuiHomeNamePrompt;
import com.jubitus.travellers.client.gui.home.GuiWorkerHomeList;
import com.jubitus.travellers.client.gui.recruit.GuiWorkerRecruit;
import com.jubitus.travellers.client.gui.transport.GuiWorker;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.gui.IAdvancedGuiHandler;

import java.awt.*;
import java.util.Collections;
import java.util.List;

@JEIPlugin
public class TravellersJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {

        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiWorker>() {
            @Override
            public Class<GuiWorker> getGuiContainerClass() {
                return GuiWorker.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiWorker gui) {
                return Collections.singletonList(new Rectangle(0, 0, gui.width, gui.height));
            }

            @Override
            public Object getIngredientUnderMouse(GuiWorker gui, int mouseX, int mouseY) {
                return null;
            }
        });

        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiWorkerRecruit>() {
            @Override
            public Class<GuiWorkerRecruit> getGuiContainerClass() {
                return GuiWorkerRecruit.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiWorkerRecruit gui) {
                return Collections.singletonList(new Rectangle(0, 0, gui.width, gui.height));
            }

            @Override
            public Object getIngredientUnderMouse(GuiWorkerRecruit gui, int mouseX, int mouseY) {
                return null;
            }
        });

        // NEW: hide JEI on home list GUI
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiWorkerHomeList>() {
            @Override
            public Class<GuiWorkerHomeList> getGuiContainerClass() {
                return GuiWorkerHomeList.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiWorkerHomeList gui) {
                return Collections.singletonList(new Rectangle(0, 0, gui.width, gui.height));
            }

            @Override
            public Object getIngredientUnderMouse(GuiWorkerHomeList gui, int mouseX, int mouseY) {
                return null;
            }
        });
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiHomeNamePrompt>() {
            @Override
            public Class<GuiHomeNamePrompt> getGuiContainerClass() {
                return GuiHomeNamePrompt.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiHomeNamePrompt gui) {
                return Collections.singletonList(new Rectangle(0, 0, gui.width, gui.height));
            }

            @Override
            public Object getIngredientUnderMouse(GuiHomeNamePrompt gui, int mouseX, int mouseY) {
                return null;
            }
        });
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiWorkerGo>() {
            @Override
            public Class<GuiWorkerGo> getGuiContainerClass() {
                return GuiWorkerGo.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiWorkerGo gui) {
                return Collections.singletonList(new Rectangle(0, 0, gui.width, gui.height));
            }

            @Override
            public Object getIngredientUnderMouse(GuiWorkerGo gui, int mouseX, int mouseY) {
                return null;
            }
        });
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
    }
}
