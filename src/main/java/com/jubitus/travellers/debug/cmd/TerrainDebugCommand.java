package com.jubitus.travellers.debug.cmd;

import com.jubitus.travellers.ground.chunk.ChunkReader;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import java.io.*;
import java.util.List;
import java.util.Collections;
import static net.minecraft.command.CommandBase.*;
import static com.jubitus.travellers.debug.cmd.DebugCommandSupport.*;

/** `/travellersdebug terrain …`. */
public class TerrainDebugCommand implements DebugSubcommand {

    @Override
    public String name() {
        return "terrain";
    }

    @Override
    public boolean execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        executeTerrainDebug(sender, args);
        return true;
    }

    @Override
    public List<String> suggestions(ICommandSender sender, String[] args) {
        if (args.length == 2) return getListOfStringsMatchingLastWord(args, "region", "export");
        boolean export = args.length >= 2 && args[1].equalsIgnoreCase("export");
        if (args.length == 4 && export) return getListOfStringsMatchingLastWord(args, "all", "height", "water", "amp", "source", "biome");
        if (args.length == 5 && export) return getListOfStringsMatchingLastWord(args, "fill");
        return Collections.emptyList();
    }

    private void executeTerrainDebug(ICommandSender sender, String[] args) throws CommandException {
        World w = sender.getEntityWorld();
        if (!(w instanceof WorldServer)) throw new CommandException("Must run on the server.");
        WorldServer ws = (WorldServer) w;

        com.jubitus.travellers.pathing.terrain.TerrainWorldState state =
                com.jubitus.travellers.pathing.terrain.TerrainWorldState.get(ws);
        if (state == null) {
            sender.sendMessage(new TextComponentString(TextFormatting.YELLOW
                    + "Planned routes are disabled (ROUTING.enablePlannedRoutes = false)."));
            return;
        }
        com.jubitus.travellers.pathing.terrain.TerrainProfileGrid grid = state.getGrid();

        if (args.length >= 2 && args[1].equalsIgnoreCase("export")) {
            executeTerrainExport(sender, ws, state, grid, args);
            return;
        }

        boolean regionMode = args.length >= 2 && args[1].equalsIgnoreCase("region");
        int argOffset = regionMode ? 2 : 1;
        int x, z;
        if (args.length >= argOffset + 2) {
            x = (int) Math.floor(parseCoord(sender, args[argOffset], true));
            z = (int) Math.floor(parseCoord(sender, args[argOffset + 1], false));
        } else if (sender instanceof Entity) {
            Entity e = (Entity) sender;
            x = (int) Math.floor(e.posX);
            z = (int) Math.floor(e.posZ);
        } else {
            throw new CommandException("Usage: /travellersdebug terrain [region] <x> <z>");
        }

        int cellX = com.jubitus.travellers.pathing.terrain.TerrainProfile.cellCoord(x);
        int cellZ = com.jubitus.travellers.pathing.terrain.TerrainProfile.cellCoord(z);

        // Debug command: block-load the region and fill this cell's estimate so there is something to show.
        grid.loadRegionBlocking(cellX, cellZ);
        grid.ensureEstimated(ws, x, z, x, z);

        if (regionMode) {
            com.jubitus.travellers.pathing.terrain.TerrainProfileGrid.RegionStats st =
                    grid.statsForCell(cellX, cellZ);
            int total = com.jubitus.travellers.pathing.terrain.TerrainProfile.CELLS_PER_REGION;
            sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Terrain region for cell "
                    + cellX + "," + cellZ + TextFormatting.WHITE
                    + " resident=" + st.resident + " dirty=" + st.dirty));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY + String.format(
                    "  REAL_SCAN %d (%.1f%%)  BIOME_EST %d (%.1f%%)  EMPTY %d (%.1f%%)",
                    st.realScan, 100.0 * st.realScan / total,
                    st.biomeEst, 100.0 * st.biomeEst / total,
                    st.empty, 100.0 * st.empty / total)));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                    + "  resident regions in dim " + ws.provider.getDimension() + ": " + grid.residentCount()
                    + "   chunks read by the chunk reader: " + ChunkReader.readCount()
                    + " (at unload: " + ChunkReader.readAtUnloadCount() + ", lost: " + ChunkReader.lostCount() + ")"));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                    + "  chunks measured: " + state.scannedCount()
                    + "   waiting for region file: " + state.heldSampleCount()));
            return;
        }

        byte src = grid.getSource(x, z);
        String srcName = src == com.jubitus.travellers.pathing.terrain.TerrainProfile.SOURCE_REAL_SCAN
                ? "REAL_SCAN" : (src == com.jubitus.travellers.pathing.terrain.TerrainProfile.SOURCE_BIOME_EST
                ? "BIOME_EST" : "EMPTY");
        short biomeIx = grid.getBiomeIx(x, z);
        String biomeName = grid.getBiomeIndex().nameFor(biomeIx);

        sender.sendMessage(new TextComponentString(TextFormatting.AQUA + "Terrain cell "
                + cellX + "," + cellZ + TextFormatting.WHITE + " (block " + x + "," + z + ")"));
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "  source=" + srcName
                + "  heightMean=" + grid.getHeight(x, z)
                + "  amplitude=" + grid.getAmplitude(x, z)
                + "  water=" + grid.getWater(x, z) + "/255"));
        sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                + "  biomeIx=" + biomeIx + " (" + (biomeName != null ? biomeName : "?") + ")"));
    }

    /** {@code /travellersdebug terrain export [radius] [mode] [fill]} — PNG dump of the scanned grid. */
    private void executeTerrainExport(ICommandSender sender, WorldServer ws,
                                     com.jubitus.travellers.pathing.terrain.TerrainWorldState state,
                                     com.jubitus.travellers.pathing.terrain.TerrainProfileGrid grid,
                                     String[] args) throws CommandException {
        int cx = sender instanceof Entity ? (int) Math.floor(((Entity) sender).posX) : 0;
        int cz = sender instanceof Entity ? (int) Math.floor(((Entity) sender).posZ) : 0;
        int radius = args.length >= 3 ? parseInt(args[2], 128, 8192) : 1024;
        String modeTok = args.length >= 4 ? args[3] : "all";
        boolean fill = args.length >= 5 && args[4].equalsIgnoreCase("fill");

        java.util.List<com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.Mode> modes =
                com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.parseModes(modeTok);
        if (modes.isEmpty()) {
            throw new CommandException("Unknown mode '" + modeTok + "'. Use: height, water, amp, source, biome, all");
        }

        // Make sure anything queued from chunks loaded around the player is scanned in before the snapshot.
        state.tick(ws, ws.getTotalWorldTime());

        try {
            com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.Result r =
                    com.jubitus.travellers.pathing.terrain.TerrainProfileImageExport.export(
                            ws.getMinecraftServer(), ws, grid, cx, cz, radius, modes, fill);
            sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "Terrain export: "
                    + r.cellsWide + "x" + r.cellsHigh + " cells, " + r.cellPx + " px/cell"));
            sender.sendMessage(new TextComponentString(TextFormatting.GRAY
                    + "  height ramp " + r.minHeight + ".." + r.maxHeight + " (black->white)"
                    + "   REAL_SCAN " + r.realScan + "  BIOME_EST " + r.biomeEst + "  EMPTY " + r.empty
                    + (r.filled ? "  [estimates filled]" : "")));
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY
                    + "  legend: dim cells = biome estimate, bright = real scan, blue = water, red cross = you"));
            for (java.io.File f : r.files) {
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "  " + f.getAbsolutePath()));
            }
        } catch (java.io.IOException ex) {
            throw new CommandException("Terrain export failed: " + ex.getMessage());
        }
    }
}
