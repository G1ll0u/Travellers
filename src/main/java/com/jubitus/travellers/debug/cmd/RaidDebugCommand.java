package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.raid.RaidScheduler;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.List;

import static net.minecraft.command.CommandBase.getListOfStringsMatchingLastWord;

/** `/travellersdebug raid [now|status|end]`. */
public class RaidDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "raid";
    }

    @Override
    public String description() {
        return "Trigger or end a raid";
    }

    @Override
    public String usage() {
        return "now|status|end";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            throw new CommandException("Run this as a player.");
        }
        EntityPlayerMP player = (EntityPlayerMP) sender.getCommandSenderEntity();
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        String reply;
        switch (sub) {
            case "now":
                reply = RaidScheduler.startNow(player);
                break;
            case "end":
                reply = RaidScheduler.endNow(player);
                break;
            case "status":
                reply = RaidScheduler.describe(player);
                break;
            default:
                return false;
        }
        sender.sendMessage(new TextComponentString(reply));
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        return args.length == 2 ? getListOfStringsMatchingLastWord(args, "now", "status", "end")
                : java.util.Collections.<String>emptyList();
    }
}
