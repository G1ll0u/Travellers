package com.jubitus.travellers.utils.commands;

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

        msg(sender, String.format("§fTravellers: §a%d§f loaded + §e%d§f virtual = §a%d§f / target §e%d§f",
                loadedTravellers, virtualTravellers, loadedTravellers + virtualTravellers, worldTravellerTarget));
        msg(sender, String.format("§fRaiders:    §a%d§f loaded + §e%d§f virtual = §a%d§f / target §e%d§f",
                loadedRaiders, virtualRaiders, loadedRaiders + virtualRaiders, worldRaiderTarget));
        msg(sender, String.format("§7  shared hard cap (travellers+raiders combined): §c%d",
                TravellersConfig.OFFSCREEN.globalPopulationCap));

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

        int globalWorkerCap = TravellersConfig.SPAWN_WORKERS.workerLoadedCap > 0
                ? TravellersConfig.SPAWN_WORKERS.workerLoadedCap
                : TravellersConfig.ATTRIBUTES.workerGlobalAssignedCap;

        msg(sender, String.format("§fWorkers: §a%d§f loaded + §a%d§f virtual = §a%d§f / §e%d§f cap  (min pop §a%d§f, §a%d§f pop/worker, max §a%d§f/village)",
                loadedWorkers, virtualWorkers, loadedWorkers + virtualWorkers, globalWorkerCap,
                TravellersConfig.SPAWN_WORKERS.workerMinPop,
                TravellersConfig.SPAWN_WORKERS.workerPopPerWorker,
                TravellersConfig.SPAWN_WORKERS.workerMaxPerVillage));
        msg(sender, String.format("§fMerchants: §a%d§f loaded / §e%d§f cap  (min pop §a%d§f, §a%d§f pop/merchant, max §a%d§f/village)",
                loadedMerchants, TravellersConfig.SPAWN_WORKERS.merchantLoadedCap,
                TravellersConfig.SPAWN_WORKERS.merchantMinPop,
                TravellersConfig.SPAWN_WORKERS.merchantPopPerMerchant,
                TravellersConfig.SPAWN_WORKERS.merchantMaxPerVillage));

        // --------------------------------------------------
        // 4) Regional Caps
        // --------------------------------------------------
        msg(sender, "§b--- Regional Caps ---");

        int travRegionalCap = MillVillageIndex.computeRegionalTravellerCap(ws, pos,
                TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius);
        int travRegionalCount = TravellerSoftSpawner.countTravellersInRegion(ws, pos,
                TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius);

        int raidRegionalCap = MillVillageIndex.computeRegionalRaiderCap(ws, pos,
                TravellersConfig.SPAWN_RAIDERS.raiderRegionalRadius);
        int raidRegionalCount = TravellerSoftSpawner.countRaidersInRegion(ws, pos,
                TravellersConfig.SPAWN_RAIDERS.raiderRegionalRadius);

        String travRegColor = travRegionalCount >= travRegionalCap ? "§c" : "§a";
        String raidRegColor = raidRegionalCount >= raidRegionalCap ? "§c" : "§a";

        msg(sender, String.format("§fTravellers (%dm r): %s%d§f / §e%d§f cap",
                TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius, travRegColor, travRegionalCount, travRegionalCap));
        msg(sender, String.format("§fRaiders    (%dm r): %s%d§f / §e%d§f cap",
                TravellersConfig.SPAWN_RAIDERS.raiderRegionalRadius, raidRegColor, raidRegionalCount, raidRegionalCap));

        // --------------------------------------------------
        // 5) Nearby Settlements breakdown
        // --------------------------------------------------
        msg(sender, "§b--- Nearby Settlements (within " + TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius + "m) ---");

        int shown = 0;
        for (Map.Entry<MillVillageIndex.VillageInfo, Integer> e : pops.entrySet()) {
            MillVillageIndex.VillageInfo vi = e.getKey();
            int dx = vi.center.getX() - pos.getX();
            int dz = vi.center.getZ() - pos.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius) continue;

            int villagers = e.getValue();

            int travBudget = (int) Math.floor(villagers * TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerVillager);
            if (travBudget < TravellersConfig.SPAWN_TRAVELLERS.travellerCapMinPerVillage)
                travBudget = TravellersConfig.SPAWN_TRAVELLERS.travellerCapMinPerVillage;
            if (travBudget > TravellersConfig.SPAWN_TRAVELLERS.travellerCapMaxPerVillage)
                travBudget = TravellersConfig.SPAWN_TRAVELLERS.travellerCapMaxPerVillage;

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
                if (dist > TravellersConfig.SPAWN_TRAVELLERS.travellerRegionalRadius) continue;
                msg(sender, String.format("§f[Lone] dist=§a%.0f§7m  travBudget=§a%d",
                        dist, TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerLoneBuilding));
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
