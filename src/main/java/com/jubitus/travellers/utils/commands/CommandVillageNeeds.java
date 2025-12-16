package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.utils.world.mill.TravellerMillHelper;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.item.InvItem;
import org.millenaire.common.utilities.MillLog;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.village.BuildingProject;
import org.millenaire.common.village.ConstructionIP;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommandVillageNeeds extends CommandBase {

    @Override
    public String getName() {
        return "villageneeds";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/villageneeds [radius]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // op-only
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender instanceof EntityPlayerMP)) {
            sender.sendMessage(new TextComponentString("Player only."));
            return;
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer ws = (WorldServer) player.world;

        BlockPos approx = player.getPosition(); // or parse coords from args

        // Find nearest village center
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findNearest3D(ws, approx, 256);
        if (vi == null) {
            player.sendMessage(new TextComponentString("[travellers] No Millénaire village found nearby."));
            return;
        }

        Building townHall = MillVillageIndex.getTownHallBuilding(MillVillageIndex.getWD(ws), vi.center);
        if (townHall == null) {
            player.sendMessage(new TextComponentString("[travellers] Could not resolve Town Hall building at " + vi.center));
            return;
        }

        TravellerMillHelper.debugVillageNeeds(player, townHall);
    }


    // ---------------------------------------------------------------------
    //  Actual construction needs (buildings + walls)
    // ---------------------------------------------------------------------

    private void dumpConstructionNeeds(Building th, ICommandSender sender) {
        BlockPos thPos = new BlockPos(th.getPos().getiX(), th.getPos().getiY(), th.getPos().getiZ());

        sender.sendMessage(new TextComponentString(
                "§6=== Village needs for §e" + th.getGameBuildingName() + "§6 at §e"
                        + thPos.getX() + "/" + thPos.getY() + "/" + thPos.getZ() + "§6 ==="));

        dumpActiveConstructions(th, sender);
        dumpWallNeeds(th, sender);

        MillLog.major(th, "[Traveller] /villageneeds full dump at " + thPos);
    }

    private void dumpActiveConstructions(Building th, ICommandSender sender) {
        List<ConstructionIP> cips = th.getConstructionsInProgress();
        sender.sendMessage(new TextComponentString(
                "§e--- Active constructions (CIPs) ---"));

        if (cips.isEmpty()) {
            sender.sendMessage(new TextComponentString("§7No ConstructionIP in progress."));
            return;
        }

        Map<InvItem, Integer> totalRequired = new LinkedHashMap<>();

        for (ConstructionIP cip : cips) {
            if (cip == null) continue;

            BuildingLocation bl = cip.getBuildingLocation();
            BuildingPlan plan = (bl != null) ? bl.getPlan() : null;

            String kind = cip.isWallConstruction() ? "§c[WALL CIP]" : "§a[BUILD CIP]";
            String label;
            if (plan != null) {
                label = kind + " §f" + plan.buildingKey + "§7 lvl " + plan.level;
            } else if (bl != null) {
                label = kind + " §f" + bl.planKey + "§7 (no plan?)";
            } else {
                label = kind + " §f<no location/plan>";
            }
            sender.sendMessage(new TextComponentString("  " + label));

            if (plan == null || plan.resCost == null || plan.resCost.isEmpty()) {
                sender.sendMessage(new TextComponentString("    §7(resCost empty)"));
                continue;
            }

            for (Map.Entry<InvItem, Integer> e : plan.resCost.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                if (ii == null || ii.getItem() == null || req <= 0) continue;

                Item item = ii.getItem();
                int meta = ii.meta;

                int inVillage = th.countGoods(item, meta);

                sender.sendMessage(new TextComponentString(
                        String.format("    §b%s§7 (meta %d): planReq=%d, chest=%d",
                                ii.getName(), meta, req, inVillage)));

                totalRequired.merge(ii, req, Integer::sum);
            }
        }

        sender.sendMessage(new TextComponentString("§6--- Combined construction requirements (CIPs) ---"));
        if (totalRequired.isEmpty()) {
            sender.sendMessage(new TextComponentString("§cNo resCost entries on any CIP."));
        } else {
            for (Map.Entry<InvItem, Integer> e : totalRequired.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                Item item = ii.getItem();
                int meta = ii.meta;
                int inVillage = th.countGoods(item, meta);
                int missing = Math.max(0, req - inVillage);

                sender.sendMessage(new TextComponentString(
                        String.format("  §d%s§7 (meta %d): totalReq=%d, chest=%d, missing=%d",
                                ii.getName(), meta, req, inVillage, missing)));
            }
        }
    }

    private void dumpWallNeeds(Building th, ICommandSender sender) {
        sender.sendMessage(new TextComponentString("§e--- Wall needs (projects) ---"));

        List<BuildingProject> projects = th.getFlatProjectList();
        if (projects == null || projects.isEmpty()) {
            sender.sendMessage(new TextComponentString("§7No building projects found (flat list empty)."));
            return;
        }

        int wallLevel = th.computeCurrentWallLevel();
        sender.sendMessage(new TextComponentString(
                "  §7computeCurrentWallLevel() => " + wallLevel));

        Map<InvItem, Integer> totalWallReq = new LinkedHashMap<>();

        for (BuildingProject project : projects) {
            if (project == null) continue;

            BuildingPlan initial = project.getPlan(0, 0);
            BuildingPlan next = project.getNextBuildingPlan(false);

            boolean isWallProject =
                    (next != null && next.isWallSegment) ||
                            (initial != null && initial.isWallSegment);

            if (!isWallProject) {
                continue;
            }

            String projLabel = "  §c[WALL PROJECT]§7 " + project.toString();
            if (next != null) {
                projLabel += " -> nextPlan=" + next.planName + " lvl=" + next.level;
            }
            sender.sendMessage(new TextComponentString(projLabel));

            BuildingPlan planForNeeds = (next != null && next.isWallSegment) ? next : initial;
            if (planForNeeds == null || planForNeeds.resCost == null) {
                sender.sendMessage(new TextComponentString("    §7(no resCost on wall plan)"));
                continue;
            }

            for (Map.Entry<InvItem, Integer> e : planForNeeds.resCost.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                if (ii == null || ii.getItem() == null || req <= 0) continue;

                Item item = ii.getItem();
                int meta = ii.meta;
                int inVillage = th.countGoods(item, meta);
                int missing = Math.max(0, req - inVillage);

                sender.sendMessage(new TextComponentString(
                        String.format("    §b%s§7 (meta %d): wallReq=%d, chest=%d, missing=%d",
                                ii.getName(), meta, req, inVillage, missing)));

                totalWallReq.merge(ii, req, Integer::sum);
            }
        }

        sender.sendMessage(new TextComponentString("§6--- Combined wall requirements (projects) ---"));
        if (totalWallReq.isEmpty()) {
            sender.sendMessage(new TextComponentString("§cNo wall plans with resCost found."));
        } else {
            for (Map.Entry<InvItem, Integer> e : totalWallReq.entrySet()) {
                InvItem ii = e.getKey();
                int req = e.getValue();
                Item item = ii.getItem();
                int meta = ii.meta;
                int inVillage = th.countGoods(item, meta);
                int missing = Math.max(0, req - inVillage);

                sender.sendMessage(new TextComponentString(
                        String.format("  §d%s§7 (meta %d): totalWallReq=%d, chest=%d, missing=%d",
                                ii.getName(), meta, req, inVillage, missing)));
            }
        }
    }
}

