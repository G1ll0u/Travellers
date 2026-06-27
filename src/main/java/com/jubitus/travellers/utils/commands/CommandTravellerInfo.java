package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.spawn.TravellerSoftSpawner;
import com.jubitus.travellers.utils.world.spawn.TravellerVillageRegistry;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import org.millenaire.common.world.MillWorldData;

import java.util.Map;

public class CommandTravellerInfo extends CommandBase {

    @Override
    public String getName() {
        return "travellersinfo";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/travellersinfo";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args)
            throws CommandException {

        if (!(sender instanceof EntityPlayerMP)) {
            throw new CommandException("Player only.");
        }

        EntityPlayerMP player = (EntityPlayerMP) sender;
        WorldServer ws = (WorldServer) player.world;
        BlockPos pos = player.getPosition();

        msg(sender, "§e=== Traveller Info ===");
        msg(sender, String.format("§7Position: %d %d %d", pos.getX(), pos.getY(), pos.getZ()));

        // --------------------------------------------------
        // 1) Global Population Controller
        // --------------------------------------------------
        msg(sender, "§b--- Global Population Controller ---");

        TravellersConfig.Offscreen cfg = TravellersConfig.OFFSCREEN;
        boolean popEnabled = cfg.globalPopulationEnabled;
        msg(sender, String.format("§fEnabled: %s  refresh every §a%ds§f  max §a%d§f spawns/cycle  global cap §a%d",
                popEnabled ? "§aON" : "§cOFF",
                cfg.globalPopulationRefreshTicks / 20,
                cfg.globalPopulationMaxSpawnsPerCycle,
                cfg.globalPopulationCap));

        // --------------------------------------------------
        // 2) Global Counts
        // --------------------------------------------------
        msg(sender, "§b--- Global Counts ---");

        int loadedTravellers = 0;
        int loadedRaiders = 0;
        int loadedWorkers = 0;
        int loadedMerchants = 0;
        for (WorldServer w : server.worlds) {
            loadedTravellers += w.getEntities(EntityTraveller.class, e -> e.isEntityAlive()
                    && !(e instanceof EntityTravellerRaider)
                    && !(e instanceof EntityTravellerWorker)
                    && !(e instanceof EntityTravellerMerchant)).size();
            loadedRaiders += w.getEntities(EntityTravellerRaider.class, e -> e.isEntityAlive()).size();
            loadedWorkers += w.getEntities(EntityTravellerWorker.class, e -> e.isEntityAlive()).size();
            loadedMerchants += w.getEntities(EntityTravellerMerchant.class, e -> e.isEntityAlive()).size();
        }

        VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
        int virtualTravellers = vtm.countByClass("TRAVELLER");
        int virtualRaiders = vtm.countByClass("RAIDER");
        int virtualWorkers = vtm.countByClass("WORKER");

        int worldTravellerTarget = MillVillageIndex.computeWorldTravellerTarget(ws);
        int worldRaiderTarget = MillVillageIndex.computeWorldRaiderTarget(ws);

        msg(sender, String.format("§fTravellers: §a%d§f loaded + §e%d§f virtual = §a%d§f / target §e%d§f / hard cap §c%d",
                loadedTravellers, virtualTravellers, loadedTravellers + virtualTravellers,
                worldTravellerTarget, TravellersModConfig.loadedTravellerCap));
        msg(sender, String.format("§fRaiders:    §a%d§f loaded + §e%d§f virtual = §a%d§f / target §e%d§f / hard cap §c%d",
                loadedRaiders, virtualRaiders, loadedRaiders + virtualRaiders,
                worldRaiderTarget, TravellersModConfig.loadedRaiderCap));

        // Village/lone breakdown for targets
        Map<MillVillageIndex.VillageInfo, Integer> pops = MillVillageIndex.countVillagersPerVillage(ws);
        int villageCount = pops.size();
        int loneCount = 0;
        MillWorldData wd = MillVillageIndex.getWD(ws);
        if (wd != null && wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            loneCount = wd.loneBuildingsList.pos.size();
        }
        msg(sender, String.format("§7  target from §a%d§7 villages + §a%d§7 lone buildings",
                villageCount, loneCount));

        // --------------------------------------------------
        // 3) Workers & Merchants (global)
        // --------------------------------------------------
        msg(sender, "§b--- Workers & Merchants (global) ---");

        int globalWorkerCap = TravellersModConfig.workerLoadedCap > 0
                ? TravellersModConfig.workerLoadedCap
                : TravellersModConfig.workerGlobalAssignedCap;

        msg(sender, String.format("§fWorkers: §a%d§f loaded + §a%d§f virtual = §a%d§f / §e%d§f cap  (min pop §a%d§f, §a%d§f pop/worker, max §a%d§f/village)",
                loadedWorkers, virtualWorkers, loadedWorkers + virtualWorkers, globalWorkerCap,
                TravellersModConfig.workerMinPop,
                TravellersModConfig.workerPopPerWorker,
                TravellersModConfig.workerMaxPerVillage));
        msg(sender, String.format("§fMerchants: §a%d§f loaded / §e%d§f cap  (min pop §a%d§f, §a%d§f pop/merchant, max §a%d§f/village)",
                loadedMerchants, TravellersModConfig.merchantLoadedCap,
                TravellersModConfig.merchantMinPop,
                TravellersModConfig.merchantPopPerMerchant,
                TravellersModConfig.merchantMaxPerVillage));

        // --------------------------------------------------
        // 4) Regional Caps
        // --------------------------------------------------
        msg(sender, "§b--- Regional Caps ---");

        int travRegionalCap = MillVillageIndex.computeRegionalTravellerCap(ws, pos,
                TravellersModConfig.travellerRegionalRadius);
        int travRegionalCount = TravellerSoftSpawner.countTravellersInRegion(ws, pos,
                TravellersModConfig.travellerRegionalRadius);

        int raidRegionalCap = MillVillageIndex.computeRegionalRaiderCap(ws, pos,
                TravellersModConfig.raiderRegionalRadius);
        int raidRegionalCount = TravellerSoftSpawner.countRaidersInRegion(ws, pos,
                TravellersModConfig.raiderRegionalRadius);

        String travRegColor = travRegionalCount >= travRegionalCap ? "§c" : "§a";
        String raidRegColor = raidRegionalCount >= raidRegionalCap ? "§c" : "§a";

        msg(sender, String.format("§fTravellers (%dm r): %s%d§f / §e%d§f cap",
                TravellersModConfig.travellerRegionalRadius, travRegColor, travRegionalCount, travRegionalCap));
        msg(sender, String.format("§fRaiders    (%dm r): %s%d§f / §e%d§f cap",
                TravellersModConfig.raiderRegionalRadius, raidRegColor, raidRegionalCount, raidRegionalCap));

        // --------------------------------------------------
        // 5) Local Cap
        // --------------------------------------------------
        int localCap = TravellersModConfig.travellerLocalCap;
        int localRadius = TravellersModConfig.travellerLocalCapRadius;
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - localRadius, pos.getY() - 32, pos.getZ() - localRadius,
                pos.getX() + localRadius, pos.getY() + 32, pos.getZ() + localRadius);
        int localCount = ws.getEntitiesWithinAABB(EntityTraveller.class, box, e -> e.isEntityAlive()).size();
        String localColor = localCount >= localCap ? "§c" : "§a";
        msg(sender, String.format("§fLocal (%dm r): %s%d§f / §e%d§f cap",
                localRadius, localColor, localCount, localCap));

        // --------------------------------------------------
        // 6) Nearby Settlements breakdown
        // --------------------------------------------------
        msg(sender, "§b--- Nearby Settlements (within " + TravellersModConfig.travellerRegionalRadius + "m) ---");

        int shown = 0;
        for (Map.Entry<MillVillageIndex.VillageInfo, Integer> e : pops.entrySet()) {
            MillVillageIndex.VillageInfo vi = e.getKey();
            int dx = vi.center.getX() - pos.getX();
            int dz = vi.center.getZ() - pos.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > TravellersModConfig.travellerRegionalRadius) continue;

            int villagers = e.getValue();

            int travBudget = (int) Math.floor(villagers * TravellersModConfig.travellerCapPerVillager);
            if (travBudget < TravellersModConfig.travellerCapMinPerVillage)
                travBudget = TravellersModConfig.travellerCapMinPerVillage;
            if (travBudget > TravellersModConfig.travellerCapMaxPerVillage)
                travBudget = TravellersModConfig.travellerCapMaxPerVillage;

            int desiredWorkers = MillVillageIndex.desiredWorkersForPopulation(villagers);
            int desiredMerchants = MillVillageIndex.desiredMerchantsForPopulation(villagers);

            String name = (vi.name != null && !vi.name.isEmpty()) ? vi.name : "?";
            String culture = (vi.cultureKey != null && !vi.cultureKey.isEmpty()) ? vi.cultureKey : "?";
            msg(sender, String.format("§f[Village] §e%s §7(%s)  pop=§a%d§7  dist=§a%.0f§7m  travBudget=§a%d§7  workers=§a%d§7  merchants=§a%d",
                    name, culture, villagers, dist, travBudget, desiredWorkers, desiredMerchants));
            shown++;
            if (shown >= 8) {
                msg(sender, "§7  ... (more villages outside display limit)");
                break;
            }
        }

        if (wd != null && wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            int loneShown = 0;
            for (org.millenaire.common.utilities.Point p : wd.loneBuildingsList.pos) {
                int dx = p.getiX() - pos.getX();
                int dz = p.getiZ() - pos.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > TravellersModConfig.travellerRegionalRadius) continue;
                msg(sender, String.format("§f[Lone] dist=§a%.0f§7m  travBudget=§a%d",
                        dist, TravellersModConfig.travellerCapPerLoneBuilding));
                loneShown++;
                if (loneShown >= 4) {
                    msg(sender, "§7  ... (more lone buildings outside display limit)");
                    break;
                }
            }
        }

        if (shown == 0) {
            msg(sender, "§cNo villages in regional radius.");
        }

        msg(sender, "§e===========================");
    }

    private void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }
}
