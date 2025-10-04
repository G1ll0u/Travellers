package com.jubitus.traveller.traveller.utils.commands;

import com.jubitus.traveller.TravellersModConfig;

public class CommandTravellerReload extends net.minecraft.command.CommandBase {
    @Override
    public String getName() {
        return "travellersconfigreload";
    }

    @Override
    public String getUsage(net.minecraft.command.ICommandSender sender) {
        return "/travellerconfigreload";
    }

    @Override
    public void execute(net.minecraft.server.MinecraftServer server,
                        net.minecraft.command.ICommandSender sender,
                        String[] args) throws net.minecraft.command.CommandException {
        TravellersModConfig.reload();
        notifyCommandListener(sender, this, "Reloaded TravellersMod config.");
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    } // ops only
}

