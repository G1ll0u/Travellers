package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerManager;
import com.jubitus.travellers.utils.world.spawn.SpawnThrottles;
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

import java.util.List;
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

        msg(sender, "§e=== Traveller Info === §7(run /travellersconfigreload first to get live values)");
        msg(sender, String.format("§7Position: %d %d %d", pos.getX(), pos.getY(), pos.getZ()));

        // --------------------------------------------------
        // 1) Soft spawn status
        // --------------------------------------------------
        msg(sender, "§b--- Soft Spawn ---");
        String travEnabled = TravellersModConfig.enableTravellerSoftSpawns ? "§aON" : "§cOFF";
        String raidEnabled = TravellersModConfig.enableRaiderSoftSpawns ? "§aON" : "§cOFF";
        msg(sender, String.format("§fTravellers: %s§f  (every §a%ds§f, §a%d§f pos checks/attempt, group §a%d§f–§a%d§f)",
                travEnabled,
                TravellersModConfig.travellerTopUpEveryTicks / 20,
                TravellersModConfig.travellerMaxPositionChecksPerAttempt,
                TravellersModConfig.MinGroupSizeAtSpawn,
                TravellersModConfig.MaxGroupSizeAtSpawn));
        msg(sender, String.format("§fRaiders:    %s§f  (every §a%ds§f, §a%d§f pos checks/attempt, group §a%d§f–§a%d§f)",
                raidEnabled,
                TravellersModConfig.raiderTopUpEveryTicks / 20,
                TravellersModConfig.raiderMaxPositionChecksPerAttempt,
                TravellersModConfig.RaiderMinGroup,
                TravellersModConfig.RaiderMaxGroup));

        // --------------------------------------------------
        // 2) Global counts
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

        int worldTarget = MillVillageIndex.computeWorldTravellerTarget(ws);

        // Break down world target composition
        Map<MillVillageIndex.VillageInfo, Integer> pops = MillVillageIndex.countVillagersPerVillage(ws);
        int villageCount = (int) pops.entrySet().stream().filter(e -> e.getValue() > 0).count();
        int loneCount = 0;
        MillWorldData wd = MillVillageIndex.getWD(ws);
        if (wd != null && wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            loneCount = wd.loneBuildingsList.pos.size();
        }

        msg(sender, String.format("§fTravellers: §a%d§f loaded / §e%d§f world target / §c%d§f hard cap",
                loadedTravellers, worldTarget, TravellersModConfig.loadedTravellerCap));
        msg(sender, String.format("§7  (target from §a%d§f villages + §a%d§f lone buildings, formula: §a%.2f§f/villager, §a%d§f–§a%d§f per village)",
                villageCount, loneCount,
                TravellersModConfig.travellerCapPerVillager,
                TravellersModConfig.travellerCapMinPerVillage,
                TravellersModConfig.travellerCapMaxPerVillage));

        msg(sender, String.format("§fRaiders: §a%d§f loaded / §e%d§f soft target / §c%d§f hard cap",
                loadedRaiders, TravellersModConfig.raiderTargetLoaded, TravellersModConfig.loadedRaiderCap));

        // --------------------------------------------------
        // 3) Workers & Merchants (global)
        // --------------------------------------------------
        msg(sender, "§b--- Workers & Merchants (global) ---");

        VirtualWorkerManager vwm = VirtualWorkerManager.get(ws);
        int virtualWorkers = vwm.getTotalCount();
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
        // 4) Density at player position
        // --------------------------------------------------
        msg(sender, "§b--- Density at Your Position ---");

        double baseDensity = MillVillageIndex.travellerDensityAt(ws, pos);
        double popMult = MillVillageIndex.populationWeightAt(ws, pos);
        double combined = baseDensity * popMult;
        double chanceGate = Math.min(1.0, combined);

        msg(sender, String.format("§fBase density: §a%.4f§7  (village r=§a%d§7 w=§a%.2f§7, lone r=§a%d§7 w=§a%.2f§7, outside=§a%.2f§7, maxDensity=§a%.2f§7)",
                baseDensity,
                TravellersModConfig.travellerVillageInfluenceRadius, TravellersModConfig.travellerVillageWeight,
                TravellersModConfig.travellerLoneInfluenceRadius, TravellersModConfig.travellerLoneWeight,
                TravellersModConfig.travellerOutsideVillageBaseDensity,
                TravellersModConfig.travellerMaxDensity));
        msg(sender, String.format("§fPop multiplier: §a%.4f§7  (radius=§a%d§7, §a%.3f§7/villager, max=§a%.2f§7)",
                popMult,
                TravellersModConfig.travellerPopulationWeightRadius,
                TravellersModConfig.travellerPopulationWeightPerVillager,
                TravellersModConfig.travellerPopulationWeightMaxMultiplier));
        msg(sender, String.format("§fCombined: §a%.4f  §fChance gate: %s%.1f%%",
                combined,
                chanceGate >= 0.7 ? "§a" : chanceGate >= 0.3 ? "§e" : "§c",
                chanceGate * 100.0));

        // --------------------------------------------------
        // 5) Regional caps (travellers + raiders)
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
        // 6) Local cap
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
        // 7) Spawn eligibility at this position
        // --------------------------------------------------
        msg(sender, "§b--- Spawn Eligibility ---");
        boolean anchorOk = true;
        StringBuilder reasons = new StringBuilder();

        if (combined <= 0.0) {
            anchorOk = false;
            reasons.append("density=0 ");
        }
        if (!SpawnThrottles.underLocalCap(ws, pos, EntityTraveller.class, localCap, localRadius)) {
            anchorOk = false;
            reasons.append("localCapReached ");
        }
        if (travRegionalCount >= travRegionalCap) {
            anchorOk = false;
            reasons.append("regionalCapReached ");
        }
        if (loadedTravellers + loadedRaiders + loadedWorkers + loadedMerchants >= TravellersModConfig.loadedTravellerCap) {
            anchorOk = false;
            reasons.append("hardCapReached ");
        }

        msg(sender, "§fAnchor spawnable (ignoring RNG roll): " +
                (anchorOk ? "§aYES" : "§cNO") +
                (reasons.length() > 0 ? " §7[" + reasons.toString().trim() + "]" : ""));
        msg(sender, String.format("§7Spawn ring: §a%d§7–§a%d§7 blocks from player",
                TravellersModConfig.travellerSpawnMinDist, TravellersModConfig.travellerSpawnMaxDist));

        // --------------------------------------------------
        // 8) Nearby settlements breakdown
        // --------------------------------------------------
        msg(sender, "§b--- Nearby Settlements (within " + TravellersModConfig.travellerRegionalRadius + "m) ---");

        int shown = 0;
        int totalVillagersNearby = 0;
        for (Map.Entry<MillVillageIndex.VillageInfo, Integer> e : pops.entrySet()) {
            MillVillageIndex.VillageInfo vi = e.getKey();
            int dx = vi.center.getX() - pos.getX();
            int dz = vi.center.getZ() - pos.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > TravellersModConfig.travellerRegionalRadius) continue;

            int villagers = e.getValue();
            totalVillagersNearby += villagers;

            int budget = (int) Math.floor(villagers * TravellersModConfig.travellerCapPerVillager);
            if (budget < TravellersModConfig.travellerCapMinPerVillage)
                budget = TravellersModConfig.travellerCapMinPerVillage;
            if (budget > TravellersModConfig.travellerCapMaxPerVillage)
                budget = TravellersModConfig.travellerCapMaxPerVillage;

            int desiredWorkers = MillVillageIndex.desiredWorkersForPopulation(villagers);
            int desiredMerchants = MillVillageIndex.desiredMerchantsForPopulation(villagers);

            String name = (vi.name != null && !vi.name.isEmpty()) ? vi.name : "?";
            String culture = (vi.cultureKey != null && !vi.cultureKey.isEmpty()) ? vi.cultureKey : "?";
            msg(sender, String.format("§f[Village] §e%s §7(%s)  pop=§a%d§7  dist=§a%.0f§7m  travBudget=§a%d§7  workers=§a%d§7  merchants=§a%d",
                    name, culture, villagers, dist, budget, desiredWorkers, desiredMerchants));
            shown++;
            if (shown >= 8) {
                msg(sender, "§7  ... (more villages outside display limit)");
                break;
            }
        }

        // Also show lone buildings in range
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
            msg(sender, "§cNo villages in regional radius. Spawns blocked (density=0, regionalCap=0).");
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
