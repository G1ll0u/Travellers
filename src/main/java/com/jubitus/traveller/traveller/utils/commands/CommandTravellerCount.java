package com.jubitus.traveller.traveller.utils.commands;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

public class CommandTravellerCount extends CommandBase {

    @Override
    public String getName() {
        return "travellerscount";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellerscount";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        int total = 0;

        // Iterate all loaded dimensions and count live travellers
        for (WorldServer world : server.worlds) {
            total += world.getEntities(EntityTraveller.class, e -> e != null && e.isEntityAlive()).size();
        }

        sender.sendMessage(new TextComponentString("There are " + total + " travellers loaded actually"));
    }

    // Let anyone run it; change to 2 if you want OP-only
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }
}
