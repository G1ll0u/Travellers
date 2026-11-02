package com.jubitus.travellers.debug.cmd;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug autotest …`. */
public class AutotestDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "autotest";
    }

    @Override
    public String description() {
        return "Server-side automated test scenarios";
    }

    @Override
    public String usage() {
        return "zone add <x> <z> <radiusChunks>|zone clear|invariants|layout|forest <x1> <z1> <x2> <z2> [solo] [followers] [seconds]|forest status|forest stop|run <scenario|all> <x> <z>|run status|run stop";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeAutotest(server, sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "zone", "invariants", "layout", "forest", "run");
        if (args.length == 3) {
            if (args[1].equalsIgnoreCase("zone")) return getListOfStringsMatchingLastWord(args, "add", "clear");
            if (args[1].equalsIgnoreCase("forest") || args[1].equalsIgnoreCase("run")) {
                return getListOfStringsMatchingLastWord(args, "status", "stop");
            }
        }
        return Collections.emptyList();
    }

    private void executeAutotest(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        String usage = "Usage: /travellersdebug autotest zone add <x> <z> <radiusChunks>|zone clear|invariants"
                + "|forest <x1> <z1> <x2> <z2> [solo] [followers] [seconds]|forest status|forest stop"
                + "|run <scenario|all> <x> <z>|run status|run stop|layout";
        String sub = args.length >= 2 ? args[1].toLowerCase() : "";
        String reply;
        if (sub.equals("layout")) {
            reply = com.jubitus.travellers.debug.autotest.AutotestLayout.compute(server.getWorld(0));
        } else if (sub.equals("invariants")) {
            reply = "invariants: " + com.jubitus.travellers.debug.autotest.AutotestInvariants.check(server);
        } else if (sub.equals("run") && args.length >= 3 && args[2].equalsIgnoreCase("status")) {
            reply = com.jubitus.travellers.debug.autotest.scenario.AutotestScenarios.status(server.getWorld(0));
        } else if (sub.equals("run") && args.length >= 3 && args[2].equalsIgnoreCase("stop")) {
            reply = com.jubitus.travellers.debug.autotest.scenario.AutotestScenarios.stop(server.getWorld(0));
        } else if (sub.equals("run") && args.length >= 5) {
            reply = com.jubitus.travellers.debug.autotest.scenario.AutotestScenarios.start(server.getWorld(0),
                    args[2], parseInt(args[3]), parseInt(args[4]));
        } else if (sub.equals("forest") && args.length >= 3 && args[2].equalsIgnoreCase("status")) {
            reply = com.jubitus.travellers.debug.autotest.AutotestForest.status(server.getWorld(0));
        } else if (sub.equals("forest") && args.length >= 3 && args[2].equalsIgnoreCase("stop")) {
            reply = com.jubitus.travellers.debug.autotest.AutotestForest.stop(server.getWorld(0));
        } else if (sub.equals("forest") && args.length >= 6) {
            reply = com.jubitus.travellers.debug.autotest.AutotestForest.start(server.getWorld(0),
                    parseInt(args[2]), parseInt(args[3]), parseInt(args[4]), parseInt(args[5]),
                    args.length > 6 ? parseInt(args[6], 0, 16) : 4, args.length > 7 ? parseInt(args[7], 0, 16) : 3,
                    args.length > 8 ? parseInt(args[8], 10, 3600) : 300);
        } else if (sub.equals("zone") && args.length >= 3 && args[2].equalsIgnoreCase("clear")) {
            reply = "zones cleared: " + com.jubitus.travellers.debug.autotest.AutotestZones.clear() + " tickets released";
        } else if (sub.equals("zone") && args.length >= 6 && args[2].equalsIgnoreCase("add")) {
            int x = parseInt(args[3]), z = parseInt(args[4]), radius = parseInt(args[5], 0, 16);
            int forced = com.jubitus.travellers.debug.autotest.AutotestZones.add(server.getWorld(0), x, z, radius);
            if (forced < 0) throw new CommandException("Forge refused a chunk ticket (ticket limit reached).");
            reply = "zone added at " + x + " " + z + ": " + forced + " chunks forced";
        } else {
            throw new CommandException(usage);
        }
        if (!sub.equals("invariants") && !sub.equals("forest") && !sub.equals("run")) {
            com.jubitus.travellers.debug.autotest.AutotestReport.append(String.join(" ", args), java.util.Collections.singletonList(reply));
        }
        sender.sendMessage(new TextComponentString(reply + " | "
                + com.jubitus.travellers.debug.autotest.AutotestReport.file().getPath()));
    }
}
