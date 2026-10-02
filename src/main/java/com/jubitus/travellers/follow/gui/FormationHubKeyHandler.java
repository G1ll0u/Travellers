package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.net.TravellerFollowNet;
import com.jubitus.travellers.follow.net.MsgRequestFormationHub;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;

@SideOnly(Side.CLIENT)
public class FormationHubKeyHandler {

    public static final KeyBinding KEY = new KeyBinding(
            "key.travellers.formation_hub",
            Keyboard.KEY_H,
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
        TravellerFollowNet.CHANNEL.sendToServer(new MsgRequestFormationHub());
    }
}
