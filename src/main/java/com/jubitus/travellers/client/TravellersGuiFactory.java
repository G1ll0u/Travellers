package com.jubitus.travellers.client;

import com.jubitus.travellers.client.gui.config.GuiTravellersConfig;
import com.jubitus.travellers.config.TravellersConfigEntry;
import com.jubitus.travellers.config.TravellersConfigReflector;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.config.MsgOpenTravellersConfigGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Map;
import java.util.Set;

@SideOnly(Side.CLIENT)
public class TravellersGuiFactory implements IModGuiFactory {

    @Override
    public void initialize(Minecraft minecraftInstance) {}

    @Override
    public boolean hasConfigGui() { return true; }

    @Override
    public GuiScreen createConfigGui(GuiScreen parentScreen) {
        Minecraft mc = Minecraft.getMinecraft();
        // If connected to a server/integrated server with a player, send network packet to get server values
        if (mc.player != null && mc.getIntegratedServer() != null) {
            EntityPlayerMP serverPlayer = mc.getIntegratedServer().getPlayerList().getPlayerByUsername(mc.player.getName());
            if (serverPlayer != null) {
                List<TravellersConfigEntry> entries = TravellersConfigReflector.buildEntries();
                Map<String, String> values = TravellersConfigReflector.toMap(entries);
                TravellerFollowNet.sendToPlayer(serverPlayer, new MsgOpenTravellersConfigGui(values));
                return parentScreen; // GUI will open via network message
            }
        }
        // Fallback: open locally with current in-memory values
        List<TravellersConfigEntry> entries = TravellersConfigReflector.buildEntries();
        Map<String, String> values = TravellersConfigReflector.toMap(entries);
        return new GuiTravellersConfig(values, true);
    }

    @Override
    public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return null; }
}
