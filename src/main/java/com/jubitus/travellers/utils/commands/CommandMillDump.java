package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;


public class CommandMillDump extends CommandBase {

    @Override
    public String getName() {
        return "millDump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/millDump <x> <y> <z> <outputFileName>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        if (args.length < 4)
            throw new WrongUsageException(getUsage(sender));

        int x = parseInt(args[0]);
        int y = parseInt(args[1]);
        int z = parseInt(args[2]);
        String filename = args[3];

        BlockPos pos = new BlockPos(x, y, z);
        World world = sender.getEntityWorld();

        sender.sendMessage(new TextComponentString("Dumping Millénaire data near " + pos));

        // 1) VillageInfo
        MillVillageIndex.VillageInfo vi =
                MillVillageIndex.findExact(world, pos);
        if (vi == null) vi = MillVillageIndex.findNearest3D(world, pos, 128);
        if (vi == null) vi = MillVillageIndex.findNearest2D(world, pos, 160);

        if (vi == null)
            throw new CommandException("No Millénaire village found near that position!");

        // 2) Buildings
        List<MillVillageIndex.BuildingInfo> buildings =
                MillVillageIndex.listBuildingsAroundVillage(world, vi.center, 64, 32);

        // 3) Write file
        File saveFolder = world.getSaveHandler().getWorldDirectory();
        File out = new File(saveFolder, filename + ".txt");

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {

            pw.println("=== Millénaire Village Debug Dump ===");
            pw.println("Center: " + vi.center);
            pw.println("Name: " + vi.name);
            pw.println("Culture: " + vi.cultureKey);
            pw.println("Type: " + vi.typeKey);
            pw.println("Is real village: " + vi.isVillage);
            pw.println();
            pw.println("=== Buildings (" + buildings.size() + ") ===");

            for (MillVillageIndex.BuildingInfo bi : buildings) {
                pw.println("-------------------------------------");
                pw.println("ChestPos : " + bi.chestPos);
                pw.println("Display  : " + bi.displayName);
                pw.println("PlanKey  : " + bi.planKey);
                pw.println("Culture  : " + bi.cultureKey);
                pw.println("TownHall : " + bi.isTownhall);
                pw.println("Tags     : " + bi.tags);

                if (bi.location != null) {
                    pw.println("Bounds   : min=" +
                            bi.location.minx + "," + bi.location.miny + "," + bi.location.minz +
                            "  max=" +
                            bi.location.maxx + "," + bi.location.maxy + "," + bi.location.maxz);
                }

                pw.println();
            }

        } catch (IOException e) {
            throw new CommandException("Failed writing file: " + e.getMessage());
        }

        sender.sendMessage(new TextComponentString(
                "Millénaire info dumped to: " + out.getAbsolutePath()));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP only (recommended)
    }
}
