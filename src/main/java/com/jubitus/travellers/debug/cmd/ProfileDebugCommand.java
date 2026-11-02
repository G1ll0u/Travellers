package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.debug.profile.MovementProfiler;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug profile …`. */
public class ProfileDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public String description() {
        return "Movement health report for all units";
    }

    @Override
    public String usage() {
        return "start|stop|report|status";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeProfile(server, sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "start", "stop", "report", "status");
        return Collections.emptyList();
    }

    private void executeProfile(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "status";
        switch (sub) {
            case "start":
                if (!MovementProfiler.start(server)) throw new CommandException("A movement profile is already running.");
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Movement profile started."
                        + TextFormatting.GRAY + " Use /travellersdebug profile report|stop."));
                return;
            case "report":
            case "stop": {
                if (!MovementProfiler.isActive()) throw new CommandException("No movement profile is running.");
                java.io.File f = sub.equals("stop") ? MovementProfiler.stop() : MovementProfiler.report();
                if (f == null) throw new CommandException("Could not write the report, see the server log.");
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN
                        + (sub.equals("stop") ? "Movement profile stopped. " : "Report written. ")
                        + TextFormatting.GRAY + "logs/travellers/profile/" + f.getName()));
                return;
            }
            case "status": {
                String status = MovementProfiler.status();
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                        + (status == null ? "No movement profile running." : "Movement profile " + status)));
                return;
            }
            default:
                throw new CommandException("Usage: /travellersdebug profile start|stop|report|status");
        }
    }
}
