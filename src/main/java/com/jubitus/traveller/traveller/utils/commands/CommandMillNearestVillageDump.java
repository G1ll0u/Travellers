package com.jubitus.traveller.traveller.utils.commands;

import com.jubitus.traveller.traveller.utils.villages.MillChestHelper;
import com.jubitus.traveller.traveller.utils.villages.MillNeeds;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.items.IItemHandler;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CommandMillNearestVillageDump extends CommandBase {

    @Override
    public String getName() {
        return "mill_nearest_village_dump";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/mill_nearest_village_dump [filename]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        if (!(sender instanceof EntityPlayer)) {
            throw new CommandException("Must be run by a player.");
        }
        EntityPlayer player = (EntityPlayer) sender;
        World world = player.world;

        BlockPos playerPos = player.getPosition();
        sender.sendMessage(new TextComponentString("Searching nearest Millénaire settlement from " + playerPos));

        // 1) Find nearest settlement center (village or lone building)
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, playerPos);
        if (vi == null) vi = MillVillageIndex.findNearest3D(world, playerPos, 128);
        if (vi == null) vi = MillVillageIndex.findNearest2D(world, playerPos, 160);

        if (vi == null) {
            throw new CommandException("No Millénaire village or lone building found near you.");
        }

        // NEW: population at this center
        int population = MillVillageIndex.countVillagersInVillage(world, vi.center);

        sender.sendMessage(new TextComponentString("Nearest settlement center: " + vi.center +
                " name=" + vi.name + " culture=" + vi.cultureKey + " type=" + vi.typeKey +
                " (isVillage=" + vi.isVillage + ", pop=" + population + ")"));

        // 2) Resolve VillageInfo -> Town Hall Building
        Building th = getBuildingAt(world, vi.center);
        if (th == null) {
            throw new CommandException("Failed to resolve Town Hall building at " + vi.center);
        }

        // 3) List buildings around this TH
        List<MillVillageIndex.BuildingInfo> buildings = MillVillageIndex.listBuildingsAroundVillage(world, vi.center, 64, 32);

        // Find the BuildingInfo corresponding to the Town Hall, if any
        MillVillageIndex.BuildingInfo townHallBI = null;
        for (MillVillageIndex.BuildingInfo bi : buildings) {
            if (bi.isTownhall) {
                townHallBI = bi;
                break;
            }
        }

        // 4) Compute needs via MillNeeds
        List<MillNeeds.ItemNeed> deficits = MillNeeds.deficitsFor(th,
                true,  // forConstruction
                true,  // forExport
                true   // forShop
        );

        // 5) Decide filename
        String baseName;
        if (args.length >= 1) {
            baseName = args[0];
        } else {
            baseName = "mill_village_" + vi.center.getX() + "_" + vi.center.getZ();
        }

        File saveFolder = world.getSaveHandler().getWorldDirectory();
        File outFile = new File(saveFolder, baseName + ".txt");

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outFile), StandardCharsets.UTF_8))) {

            // ---------- VILLAGE SUMMARY ----------
            pw.println("=== Millénaire Nearest Settlement Dump ===");
            pw.println("Player at:  " + playerPos.getX() + "," + playerPos.getY() + "," + playerPos.getZ());
            pw.println("Center:     " + vi.center.getX() + "," + vi.center.getY() + "," + vi.center.getZ());
            pw.println("Name:       " + vi.name);
            pw.println("Culture:    " + vi.cultureKey);
            pw.println("TypeKey:    " + vi.typeKey);
            pw.println("IsVillage:  " + vi.isVillage);
            pw.println("Population: " + population);
            pw.println();
            pw.println("TownHall building: " + th);
            if (th.location != null) {
                pw.println("  TH chestPos: " + th.location.chestPos);
            }
            pw.println();

            // ---------- BUILDINGS LIST ----------
            pw.println("=== Buildings Around Village (radius 64/32) ===");
            pw.println("Count: " + buildings.size());
            int shops = 0;
            for (MillVillageIndex.BuildingInfo bi : buildings) {
                if (bi.tags.contains("shop")) shops++;
            }
            pw.println("Shops: " + shops);
            pw.println();

            for (MillVillageIndex.BuildingInfo bi : buildings) {
                pw.println("-------------------------------------");
                pw.println("ChestPos  : " + bi.chestPos);
                pw.println("Display   : " + bi.displayName);
                pw.println("PlanKey   : " + bi.planKey);
                pw.println("Culture   : " + bi.cultureKey);
                pw.println("TownHall  : " + bi.isTownhall);
                pw.println("Tags      : " + bi.tags);

                if (bi.location != null) {
                    pw.println("Bounds    : min=" +
                            bi.location.minx + "," + bi.location.miny + "," + bi.location.minz +
                            "  max=" +
                            bi.location.maxx + "," + bi.location.maxy + "," + bi.location.maxz);
                }
                pw.println();
            }

            // ---------- TH CHEST CONTENTS ----------
            pw.println();
            pw.println("=== Town Hall Chest Contents ===");
            if (townHallBI != null) {
                dumpChestContents(world, townHallBI, pw);
            } else {
                pw.println("No BuildingInfo for Town Hall found; trying chest at TH location.");
                MillVillageIndex.BuildingInfo fakeTH = new MillVillageIndex.BuildingInfo(
                        th.location != null && th.location.chestPos != null
                                ? new BlockPos(th.location.chestPos.getiX(), th.location.chestPos.getiY(), th.location.chestPos.getiZ())
                                : vi.center,
                        true,
                        th.location != null ? th.location.planKey : "",
                        th.location != null && th.location.culture != null ? th.location.culture.key : "",
                        th.toString(),
                        new ArrayList<>(),
                        th.location
                );
                dumpChestContents(world, fakeTH, pw);
            }

            // ---------- RESOURCE NEEDS ----------
            pw.println();
            pw.println("=== Resource Needs (MillNeeds.deficitsFor) ===");
            pw.println("Total items with deficits: " + deficits.size());
            pw.println();

            int maxLines = 64; // don’t spam too much
            int printed = 0;
            for (MillNeeds.ItemNeed need : deficits) {
                if (printed++ >= maxLines) {
                    pw.println("... (truncated) ...");
                    break;
                }
                InvItem ii = need.item;
                pw.println("Item: " + ii); // toString() usually includes name/meta
                pw.println("  onHand            = " + need.onHand);
                pw.println("  freeNow           = " + need.freeNow);
                pw.println("  reservedConstr    = " + need.reservedConstruction);
                pw.println("  reservedTrade     = " + need.reservedTrade);
                pw.println("  reservedUpkeep    = " + need.reservedUpkeep);
                pw.println("  neededForGoal     = " + need.neededForGoal);
                pw.println("  tradeTarget       = " + need.tradeTarget);
                pw.println("  neededTotal       = " + need.neededTotal);
                pw.println();
            }

        } catch (IOException e) {
            throw new CommandException("Failed writing dump: " + e.getMessage());
        }

        sender.sendMessage(new TextComponentString(
                "Dump written to: " + outFile.getAbsolutePath()));
    }

    /**
     * Resolve a Millénaire Building at exactly given center BlockPos.
     */
    @Nullable
    private Building getBuildingAt(World world, BlockPos pos) {
        if (!(world instanceof WorldServer)) return null;
        WorldServer ws = (WorldServer) world;
        MillWorldData wd = Mill.getMillWorld(ws);
        if (wd == null) return null;
        Point p = new Point(pos.getX(), pos.getY(), pos.getZ());
        return wd.getBuilding(p);
    }

    private void dumpChestContents(World world, MillVillageIndex.BuildingInfo bi, PrintWriter pw) {
        IItemHandler handler = MillChestHelper.getBuildingChestItemHandler(world, bi);
        if (handler != null) {
            pw.println("Using ITEM_HANDLER capability. Slots: " + handler.getSlots());
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    pw.println("  [" + slot + "] " + stack.getCount() + "x " + stack.getDisplayName());
                }
            }
            return;
        }

        IInventory inv = MillChestHelper.getBuildingChestInventory(world, bi);
        if (inv != null) {
            pw.println("Using IInventory. Size: " + inv.getSizeInventory());
            for (int slot = 0; slot < inv.getSizeInventory(); slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    pw.println("  [" + slot + "] " + stack.getCount() + "x " + stack.getDisplayName());
                }
            }
            return;
        }

        pw.println("No usable inventory / item handler at chestPos " + bi.chestPos);
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP
    }
}

