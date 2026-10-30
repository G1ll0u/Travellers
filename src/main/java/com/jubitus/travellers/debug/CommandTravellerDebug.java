package com.jubitus.travellers.debug;

import com.jubitus.travellers.debug.cmd.*;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** `/travellersdebug`: routes to one {@link DebugSubcommand} per first word. */
public class CommandTravellerDebug extends CommandBase {

    private static final CultureDumpCommand CULTURE_DUMP = new CultureDumpCommand();
    private static final Map<String, DebugSubcommand> SUBCOMMANDS = new HashMap<>();

    static {
        for (DebugSubcommand c : new DebugSubcommand[]{new TraceDebugCommand(), new ProfileDebugCommand(),
                new AutotestDebugCommand(), new MountDebugCommand(), new PathDebugCommand(), new ElytraDebugCommand(),
                new CampDebugCommand(), new RestSpotDebugCommand(), new BattleDebugCommand(), new MapDebugCommand(), new ResetDebugCommand(),
                new BowDebugCommand(), new SoundDebugCommand(), new StableDebugCommand(), new TerrainDebugCommand(),
                new VillageDebugCommand(), new DialogueDebugCommand(), new DestinationDebugCommand(),
                new RelationsDebugCommand(), new EquipDebugCommand(), new RaidDebugCommand(), CULTURE_DUMP}) {
            SUBCOMMANDS.put(c.name(), c);
        }
    }

    @Override
    public String getName() {
        return "travellersdebug";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersdebug [autotest zone add <x> <z> <radiusChunks>|autotest zone clear|autotest invariants|profile start|stop|report|status|reset [workers]|camp [here|recompute|join <n> [culture] [TRAVELLER|RAIDER]|leave|reset [all]]|restspot [here|stats|rescan]|battle [list|info <n>|end <n>|spawnfight [x z]|loot]|map resweep [all]|map resweep cancel|map stats [reset]|elytra [distance]|elytra give [radius] [rockets]|elytra status|mount give|mount ride|mount lead|mount hitch|stable [list|dump <n>|log <n> [count]|verify <n>]|bow status|sound stress|sound rapidfire|path pos1|path pos2|path mark|path clearmarks|path save [name]|path load [name]|path test [--mount] [--waypoints]|path stop|path show --marks|path show --trace <file>|path export <name>|terrain [region] <x> <z>|terrain export [radius] [mode] [fill]|village show|hide|here|cultures|dialogue setup|raid [now|status|end]]";
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable net.minecraft.util.math.BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "trace", "profile", "mount", "elytra", "camp", "restspot", "battle",
                    "map", "reset", "bow", "sound", "path", "cultures", "stable", "equip", "terrain",
                    "destination", "relations", "dialogue", "village", "autotest");
        }
        DebugSubcommand sub = SUBCOMMANDS.get(args[0].toLowerCase(Locale.ROOT));
        return sub == null ? java.util.Collections.emptyList() : sub.suggestions(sender, args);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!server.isCallingFromMinecraftThread()) {
            executeOnServerThread(server, sender, args);
            return;
        }
        DebugSubcommand sub = args.length >= 1 ? SUBCOMMANDS.get(args[0].toLowerCase(Locale.ROOT)) : null;
        if (sub != null && sub.execute(server, sender, args)) return;
        CULTURE_DUMP.execute(server, sender, args);
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
        return 2; // OP only
    }
}
