package com.jubitus.travellers.client.gui;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.gui.IAdvancedGuiHandler;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.List;

@JEIPlugin
public class TravellersJeiPlugin implements IModPlugin {

    @Override
    public void register(IModRegistry registry) {
        // For JEI 1.12.x
        registry.addAdvancedGuiHandlers(new IAdvancedGuiHandler<GuiWorker>() {
            @Override
            public Class<GuiWorker> getGuiContainerClass() {
                return GuiWorker.class;
            }

            @Override
            public List<Rectangle> getGuiExtraAreas(GuiWorker gui) {
                // Claim the whole screen as part of this GUI.
                // JEI will think there is no free space for the item list and hide it.
                return Collections.singletonList(
                        new Rectangle(0, 0, gui.width, gui.height)
                );
            }

            @Override
            public Object getIngredientUnderMouse(GuiWorker gui, int mouseX, int mouseY) {
                return null;
            }
        });


    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        // not needed here, but required by interface
    }
}
