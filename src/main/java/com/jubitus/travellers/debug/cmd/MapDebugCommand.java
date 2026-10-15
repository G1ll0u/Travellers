package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.worldmap.pyramid.MapFillStats;
import com.jubitus.travellers.ground.chunk.ChunkReader;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug map …`. */
public class MapDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "map";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length >= 2 && args[1].equalsIgnoreCase("stats")) {
            executeMapStats(sender, args.length >= 3 && args[2].equalsIgnoreCase("reset"));
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("streams")) {
            executeMapStreams(sender, args.length >= 3 ? args[2] : "report");
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("resweep") && args[2].equalsIgnoreCase("cancel")) {
            executeMapResweepCancel(server, sender);
            return true;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("resweep")) {
            boolean allDimensions = args.length >= 3 && args[2].equalsIgnoreCase("all");
            executeMapResweep(server, sender, allDimensions);
            return true;
        }
        return false;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "resweep", "stats", "streams");
        if (args.length == 3 && args[1].equalsIgnoreCase("resweep")) return getListOfStringsMatchingLastWord(args, "all", "cancel");
        if (args.length == 3 && args[1].equalsIgnoreCase("streams")) return getListOfStringsMatchingLastWord(args, "start", "stop", "report");
        if (args.length == 3 && args[1].equalsIgnoreCase("stats")) return getListOfStringsMatchingLastWord(args, "reset");
        return Collections.emptyList();
    }

    /**
     * On-demand worldmap correction for tiles {@code MapLoadedChunkResampler} can never reach
     * automatically (nobody has visited that chunk since it was first captured, e.g. deep in a
     * bulk-pregenerated world). Plain "map resweep" only processes the sender's current dimension;
     * "map resweep all" processes every loaded dimension. Progress is reported to {@code sender}
     * periodically until each dimension's job finishes.
     */
    private void executeMapResweep(MinecraftServer server, ICommandSender sender, boolean allDimensions) {
        java.util.List<WorldServer> targets = new java.util.ArrayList<>();
        if (allDimensions) {
            targets.addAll(java.util.Arrays.asList(server.worlds));
        } else if (sender instanceof EntityPlayerMP) {
            targets.add(((EntityPlayerMP) sender).getServerWorld());
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Must be run by a player, or use 'map resweep all' to target every dimension."));
            return;
        }

        int alreadyRunning = 0;
        for (WorldServer ws : targets) {
            if (com.jubitus.travellers.worldmap.pyramid.MapResweepCoordinator.INSTANCE.isActive(ws.provider.getDimension())) {
                alreadyRunning++;
                continue;
            }
            com.jubitus.travellers.worldmap.pyramid.MapResweepCoordinator.INSTANCE.start(ws, sender);
        }
        if (alreadyRunning > 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Map resweep already running in " + alreadyRunning + " dimension(s); skipped."));
        }
    }

    // Worldmap fill counters since the last reset, plus a hole census of the 3x3 level-0 tiles around the sender.
    private void executeMapStats(ICommandSender sender, boolean reset) {
        if (reset) {
            MapFillStats.reset();
            ChunkReader.resetCounters();
            com.jubitus.travellers.camp.CampsiteManager.resetDiscoveryCounters();
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Map fill counters reset."));
            return;
        }
        msg(sender, TextFormatting.AQUA + "Chunk reader (terrain + worldmap + camps, since last reset)");
        msg(sender, TextFormatting.GRAY + String.format(
                "  queued now %d (peak %d)  enqueued %d  read %d (at unload %d)  LOST %d  time %.0f ms",
                ChunkReader.queuedCount(), ChunkReader.queuePeak(), ChunkReader.enqueuedCount(),
                ChunkReader.readCount(), ChunkReader.readAtUnloadCount(), ChunkReader.lostCount(),
                ChunkReader.readNanos() / 1e6));
        msg(sender, TextFormatting.GRAY + "  " + ChunkReader.changeStats());
        msg(sender, TextFormatting.GRAY + "  camps: " + com.jubitus.travellers.camp.CampsiteManager.discoveryStats());
        msg(sender, TextFormatting.AQUA + "Worldmap");
        msg(sender, TextFormatting.GRAY + String.format(
                "  chunks painted %d  nothing new %d  paint time %.0f ms",
                MapFillStats.painted(), MapFillStats.unchanged(), MapFillStats.paintNanos() / 1e6));
        msg(sender, TextFormatting.GRAY + String.format(
                "  sweep (overworld): processed %d  changed %d  passes %d  last pass %s for %d chunks  time %.0f ms",
                MapFillStats.sweepProcessed(), MapFillStats.sweepChanged(), MapFillStats.sweepPasses(),
                MapFillStats.sweepLastPassTicks() < 0 ? "-" : String.format("%.1fs", MapFillStats.sweepLastPassTicks() / 20.0),
                MapFillStats.sweepLastPassChunks(), MapFillStats.sweepNanos() / 1e6));
        msg(sender, TextFormatting.GRAY + String.format(
                "  pushes: level0 %d  upper levels %d  delayed by throttle %d  push deflate %.0f ms",
                MapFillStats.pushSentLevel0(), MapFillStats.pushSentUpper(), MapFillStats.pushCoalesced(),
                MapFillStats.pushDeflateNanos() / 1e6));
        long deflates = MapFillStats.storeDeflateCount();
        msg(sender, TextFormatting.GRAY + String.format(
                "  tile store compression (mostly save thread): %d tiles, %.0f ms",
                deflates, MapFillStats.storeDeflateNanos() / 1e6));

        if (!(sender instanceof Entity)) return;
        Entity e = (Entity) sender;
        net.minecraft.world.World world = e.world;
        int tileBlocks = com.jubitus.travellers.worldmap.pyramid.MapTileGenerator.TILE_PX;
        int tx = Math.floorDiv((int) Math.floor(e.posX), tileBlocks);
        int tz = Math.floorDiv((int) Math.floor(e.posZ), tileBlocks);
        com.jubitus.travellers.worldmap.pyramid.MapPyramidStore level0 =
                com.jubitus.travellers.worldmap.pyramid.MapPyramidStore.get(world, 0);
        MapFillStats.HoleCensus total =
                new MapFillStats.HoleCensus();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                byte[] tile = level0.get(com.jubitus.travellers.worldmap.pyramid.TileKey.pack(tx + dx, tz + dz));
                total.add(MapFillStats.census(tile));
            }
        }
        msg(sender, TextFormatting.AQUA + "Holes in the 3x3 map tiles around you (768x768 blocks, per chunk)");
        msg(sender, TextFormatting.GRAY + String.format(
                "  full %d  empty %d  PARTIAL %d (edge-line only %d, %d missing pixels)",
                total.fullChunks, total.emptyChunks, total.partialChunks,
                total.edgeOnlyPartialChunks, total.missingPixelsInPartial));
    }

    // Cost of the unit streams sent to map viewers: start a session, let players use the map, then report.
    private void executeMapStreams(ICommandSender sender, String action) {
        if (action.equalsIgnoreCase("start")) {
            com.jubitus.travellers.worldmap.stream.MapStreamStats.start();
            msg(sender, TextFormatting.GREEN + "Map stream measuring started. Open a map, then 'map streams report'.");
            return;
        }
        if (action.equalsIgnoreCase("stop")) {
            com.jubitus.travellers.worldmap.stream.MapStreamStats.stop();
            msg(sender, TextFormatting.GREEN + "Map stream measuring stopped (report still available).");
        }
        for (String line : com.jubitus.travellers.worldmap.stream.MapStreamStats.report()) {
            msg(sender, TextFormatting.GRAY + line);
        }
    }

    private static void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    /**
     * Stops every running "map resweep" job across every dimension, wherever it was started from
     * (player or server console) and regardless of the sender's current dimension - there's no
     * legitimate reason to want a bulk correction job left running once you've asked to cancel it.
     */
    private void executeMapResweepCancel(MinecraftServer server, ICommandSender sender) {
        int cancelled = 0;
        for (WorldServer ws : server.worlds) {
            if (com.jubitus.travellers.worldmap.pyramid.MapResweepCoordinator.INSTANCE.cancel(ws.provider.getDimension(), sender)) {
                cancelled++;
            }
        }
        if (cancelled == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "No map resweep running."));
        }
    }

    // --- Elytra flight test ---
}
