package com.jubitus.travellers.debug;

import com.jubitus.travellers.debug.cmd.*;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** `/travellersdebug`: routes to one {@link DebugSubcommand} per first word; with none, lists them. */
public class CommandTravellerDebug extends CommandBase {

    private static final Map<String, DebugSubcommand> SUBCOMMANDS = new TreeMap<>();

    static {
        for (DebugSubcommand c : new DebugSubcommand[]{new ExportDebugCommand(), new TraceDebugCommand(),
                new ProfileDebugCommand(), new AutotestDebugCommand(), new MountDebugCommand(), new PathDebugCommand(),
                new ElytraDebugCommand(), new CampDebugCommand(), new RestSpotDebugCommand(), new BattleDebugCommand(),
                new MapDebugCommand(), new ResetDebugCommand(), new BowDebugCommand(), new SoundDebugCommand(),
                new StableDebugCommand(), new TerrainDebugCommand(), new VillageDebugCommand(), new DialogueDebugCommand(),
                new DestinationDebugCommand(), new RelationsDebugCommand(), new EquipDebugCommand(), new RaidDebugCommand(),
                new CultureDumpCommand(), new CultureItemsDebugCommand(), new RouteDumpDebugCommand()}) {
            SUBCOMMANDS.put(c.name(), c);
        }
    }

    @Override
    public String getName() {
        return "travellersdebug";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersdebug [help|<subcommand> …]";
    }

    /** Operators, plus the owner of a local world (who may run the subcommands that are not op-only). */
    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return DebugAccess.isOp(sender) || DebugAccess.isSoloOwner(server, sender);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable net.minecraft.util.math.BlockPos targetPos) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            names.add("help");
            for (DebugSubcommand c : SUBCOMMANDS.values()) if (DebugAccess.mayRun(server, sender, c)) names.add(c.name());
            return getListOfStringsMatchingLastWord(args, names);
        }
        if (args[0].equalsIgnoreCase("help")) {
            return args.length == 2 ? getListOfStringsMatchingLastWord(args, SUBCOMMANDS.keySet()) : Collections.emptyList();
        }
        DebugSubcommand sub = SUBCOMMANDS.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null || !DebugAccess.mayRun(server, sender, sub)) return Collections.emptyList();
        return sub.suggestions(sender, args);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!server.isCallingFromMinecraftThread()) {
            executeOnServerThread(server, sender, args);
            return;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(server, sender, args.length >= 2 ? args[1] : null);
            return;
        }
        DebugSubcommand sub = SUBCOMMANDS.get(args[0].toLowerCase(Locale.ROOT));
        if (sub == null) {
            throw new CommandException("Unknown subcommand '" + args[0] + "'. Run /travellersdebug help for the list.");
        }
        if (!DebugAccess.mayRun(server, sender, sub)) {
            throw new CommandException("/travellersdebug " + sub.name() + " is for operators only.");
        }
        if (!sub.execute(server, sender, args)) sendUsage(sender, sub);
    }

    private static void sendUsage(ICommandSender sender, DebugSubcommand sub) {
        sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + TextFormatting.WHITE
                + "/travellersdebug " + sub.name() + (sub.usage().isEmpty() ? "" : " " + sub.usage())));
    }

    private static void sendHelp(MinecraftServer server, ICommandSender sender, @Nullable String name) throws CommandException {
        if (name != null) {
            DebugSubcommand sub = SUBCOMMANDS.get(name.toLowerCase(Locale.ROOT));
            if (sub == null || !DebugAccess.mayRun(server, sender, sub)) {
                throw new CommandException("No subcommand '" + name + "'. Run /travellersdebug help for the list.");
            }
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + sub.name() + TextFormatting.GRAY + " - " + sub.description()));
            sendUsage(sender, sub);
            return;
        }
        sender.sendMessage(new TextComponentString(TextFormatting.GOLD + "/travellersdebug <subcommand>"
                + TextFormatting.GRAY + "  (help <subcommand> shows its arguments)"));
        for (DebugSubcommand c : SUBCOMMANDS.values()) {
            if (!DebugAccess.mayRun(server, sender, c)) continue;
            sender.sendMessage(new TextComponentString("  " + TextFormatting.AQUA + c.name() + TextFormatting.GRAY + " - " + c.description()));
        }
    }

    /** RCON runs commands on its own thread; everything here reads or changes the world, so hop over and wait. */
    private void executeOnServerThread(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        try {
            server.callFromMainThread(() -> {
                execute(server, sender, args);
                return null;
            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof CommandException) throw (CommandException) e.getCause();
            net.minecraftforge.fml.common.FMLLog.log.error("[Travellers] /travellersdebug {} failed", String.join(" ", args), e.getCause());
            throw new CommandException("Command failed on the server thread: " + e.getCause());
        } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
            throw new CommandException("Command did not finish on the server thread: " + e);
        }
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
