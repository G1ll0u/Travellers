package com.jubitus.travellers.client.input;

import com.jubitus.travellers.client.gui.worldmap.GuiPlayerWorldMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class WorldMapKeyHandler {

    public static final KeyBinding KEY = new KeyBinding(
            "key.travellers.world_map",
            0, // no default key
            "key.categories.travellers"
    );

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        if (!KEY.isPressed()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.player == null) return;
        mc.displayGuiScreen(new GuiPlayerWorldMap(mc.player, -1));
    }
}
