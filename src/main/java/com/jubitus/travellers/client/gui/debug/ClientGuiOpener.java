package com.jubitus.travellers.client.gui.debug;

import com.jubitus.travellers.utils.debug.TravellerChunkSnapshot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientGuiOpener {
    private ClientGuiOpener() {
    }

    public static void openTravellerChunks(TravellerChunkSnapshot snapshot) {
        net.minecraft.client.Minecraft.getMinecraft()
                .displayGuiScreen(new GuiTravellerChunks(snapshot));
    }
}
