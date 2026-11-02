package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.debug.trace.TraceChannel;
import com.jubitus.travellers.debug.trace.TraceSession;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import java.io.*;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug trace …`. */
public class TraceDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "trace";
    }

    @Override
    public String description() {
        return "Record one unit's movement, tick by tick";
    }

    @Override
    public String usage() {
        return "start [name]|stop [name|all]|list|channels [list]";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeTrace(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "start", "stop", "list", "channels");
        if (args.length != 3) return Collections.emptyList();
        String sub = args[1].toLowerCase();
        if (sub.equals("channels")) return getListOfStringsMatchingLastWord(args, traceChannelSuggestions());
        if (sub.equals("stop")) {
            List<String> names = nearbyTravellerNames(sender);
            names.add(0, "all");
            return getListOfStringsMatchingLastWord(args, names);
        }
        if (sub.equals("start")) return getListOfStringsMatchingLastWord(args, nearbyTravellerNames(sender));
        return Collections.emptyList();
    }

    /**
     * {@code /travellersdebug mount give|ride|lead|hitch} - bonds a fresh horse to the nearest traveller within 32
     * blocks, for testing mounted pathfinding without waiting on a natural spawn roll.
     */
    /**
     * {@code /travellersdebug trace start|stop|list|channels} - per-entity movement tracing.
     * "start" with no name picks whichever traveller the player is looking at, falling back to the
     * nearest one, since pointing at the individual misbehaving is the natural gesture when several
     * are in view.
     */
    private void executeTrace(ICommandSender sender, String[] args) throws CommandException {
        String sub = args.length >= 2 ? args[1].toLowerCase() : "list";

        if (sub.equals("channels")) {
            if (args.length < 3) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "Channels: "
                        + TraceChannel.join(TravellerTracer.getDefaultChannels())));
                return;
            }
            java.util.EnumSet<TraceChannel> parsed = TraceChannel.parse(args[2]);
            if (parsed == null) throw new CommandException("Unknown channel in: " + args[2]);
            TravellerTracer.setDefaultChannels(parsed);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Trace channels: "
                    + TraceChannel.join(parsed)));
            return;
        }

        if (sub.equals("list")) {
            if (TravellerTracer.sessions().isEmpty()) {
                sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "No trace running."));
                return;
            }
            for (TraceSession s : TravellerTracer.sessions()) {
                sender.sendMessage(new TextComponentString(TextFormatting.AQUA + s.entityName
                        + TextFormatting.GRAY + (s.automatic ? " (auto) " : " (manual) ") + s.getFile().getName()));
            }
            return;
        }

        if (sub.equals("stop")) {
            MinecraftServer server = sender.getServer();
            if (args.length >= 3 && args[2].equalsIgnoreCase("all")) {
                List<String> summaries = TravellerTracer.stopAll(server, "stopped by command");
                if (summaries.isEmpty()) throw new CommandException("No trace running.");
                for (String s : summaries) sendSummary(sender, s);
                return;
            }
            EntityTraveller target = resolveTraceTarget(sender, args, 2);
            String summary = TravellerTracer.stop(server, target.getUniqueID(), "stopped by command");
            if (summary == null) throw new CommandException(target.getName() + " is not being traced.");
            sendSummary(sender, summary);
            return;
        }

        if (!sub.equals("start")) {
            throw new CommandException("Usage: /travellersdebug trace start|stop|list|channels");
        }

        EntityTraveller target = resolveTraceTarget(sender, args, 2);
        TraceSession session = TravellerTracer.start(target, TravellerTracer.getDefaultChannels());
        if (session == null) {
            throw new CommandException("Already tracing " + target.getName() + " (or the file could not be created).");
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Tracing " + target.getName()
                + TextFormatting.GRAY + " -> logs/travellers/trace/" + session.getFile().getName()));
    }

    /** Channel names plus "all", and the current selection with one more appended - so building a
     * comma-separated list by tabbing works instead of only ever offering a single name. */
    private List<String> traceChannelSuggestions() {
        List<String> out = new java.util.ArrayList<>();
        out.add("all");
        for (TraceChannel c : TraceChannel.values()) out.add(c.lower());
        return out;
    }
}
