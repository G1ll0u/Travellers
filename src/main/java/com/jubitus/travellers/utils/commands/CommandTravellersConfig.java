package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.config.TravellersConfigEntry;
import com.jubitus.travellers.config.TravellersConfigReflector;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.config.MsgOpenTravellersConfigGui;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Map;

public class CommandTravellersConfig extends CommandBase {

    @Override
    public String getName() {
        return "travellersconfig";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersconfig";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new net.minecraft.util.text.TextComponentString(
                    "[Travellers] This command must be run by a player."));
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        List<TravellersConfigEntry> entries = TravellersConfigReflector.buildEntries();
        Map<String, String> values = TravellersConfigReflector.toMap(entries);
        TravellerFollowNet.sendToPlayer(player, new MsgOpenTravellersConfigGui(values));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
