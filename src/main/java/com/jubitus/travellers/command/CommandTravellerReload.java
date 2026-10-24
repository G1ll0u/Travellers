package com.jubitus.travellers.command;

import com.jubitus.jubiconfig.JubiConfig;
import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.config.TravellersDerivedConfig;

public class CommandTravellerReload extends net.minecraft.command.CommandBase {
    @Override
    public String getName() {
        return "travellersconfigreload";
    }

    @Override
    public String getUsage(net.minecraft.command.ICommandSender sender) {
        return "/travellersconfigreload";
    }

    @Override
    public void execute(net.minecraft.server.MinecraftServer server,
                        net.minecraft.command.ICommandSender sender,
                        String[] args) throws net.minecraft.command.CommandException {
        JubiConfig.applyOnStartup();
        TravellersDerivedConfig.refresh();
        TravellersDerivedConfig.applyRuntimeEffects();
        notifyCommandListener(sender, this, "Reloaded TravellersMod config.");

    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    } // ops only
}

