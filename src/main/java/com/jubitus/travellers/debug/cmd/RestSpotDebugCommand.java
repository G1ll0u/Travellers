package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.restspot.RestSpotManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;

import java.util.Collections;
import java.util.List;

import static net.minecraft.command.CommandBase.getListOfStringsMatchingLastWord;

/** `/travellersdebug restspot here|stats|rescan`. */
public class RestSpotDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "restspot";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Must be run by a player."));
            return true;
        }
        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer ws = player.getServerWorld();
        RestSpotManager mgr = RestSpotManager.get(ws);
        String sub = args.length >= 2 ? args[1] : "stats";
        if (sub.equalsIgnoreCase("here")) {
            send(sender, mgr.judgeForDebug(ws, player.chunkCoordX, player.chunkCoordZ));
        } else if (sub.equalsIgnoreCase("rescan")) {
            int queued = mgr.rescan(ws);
            send(sender, "Rest spots forgotten; " + queued + " loaded chunks queued for judging.");
        } else {
            send(sender, mgr.stats());
        }
        return true;
    }

    private static void send(ICommandSender sender, String text) {
        for (String line : text.split("\n")) sender.sendMessage(new TextComponentString(line));
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "here", "stats", "rescan");
        return Collections.emptyList();
    }
}
