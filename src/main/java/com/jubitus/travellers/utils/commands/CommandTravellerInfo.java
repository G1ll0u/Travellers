package com.jubitus.travellers.utils.commands;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.spawn.SpawnThrottles;
import com.jubitus.travellers.utils.world.spawn.TravellerSoftSpawner;
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

    // ----------------------------------------------------------
    // Dynamic target (same logic we discussed earlier)
    // ----------------------------------------------------------
    public static int computeDynamicTarget(WorldServer ws) {

        Map<MillVillageIndex.VillageInfo, Integer> pops =
                MillVillageIndex.countVillagersPerVillage(ws);

        final double perVillager = 1.0 / 3.0;
        final int minPerVillage = 2;
        final int maxPerVillage = 8;

        final int globalMin = 0;
        final int globalMax = 64;

        final int perLone = 1;

        int total = 0;

        // Villages
        for (Map.Entry<MillVillageIndex.VillageInfo, Integer> e : pops.entrySet()) {
            int villagers = e.getValue();
            if (villagers <= 0) continue;

            int t = (int) Math.floor(villagers * perVillager);
            if (t < minPerVillage) t = minPerVillage;
            if (t > maxPerVillage) t = maxPerVillage;
            total += t;
        }

        // Lone Buildings
        MillWorldData wd = MillVillageIndex.getWD(ws);
        if (wd != null && wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            total += wd.loneBuildingsList.pos.size() * perLone;
        }

        if (total < globalMin) total = globalMin;
        if (total > globalMax) total = globalMax;

        return total;
    }

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

        msg(sender, "§e---- Traveller Info ----");
        msg(sender, String.format("§fPosition: §b%d %d %d", pos.getX(), pos.getY(), pos.getZ()));

        // --------------------------------------------------
        // 1) Global travellers vs target
        // --------------------------------------------------
        int loadedTotal = 0;
        for (WorldServer w : server.worlds) {
            loadedTotal += w.getEntities(EntityTraveller.class, e -> e.isEntityAlive()).size();
        }
        int dynamicTarget = MillVillageIndex.computeWorldTravellerTarget(ws);
        msg(sender, String.format("§fGlobal Travellers: §b%d§f / %d (dynamic target)",
                loadedTotal, dynamicTarget));


        // --------------------------------------------------
        // 2) Density / spawn chance at this position
        // --------------------------------------------------
        double baseDensity = MillVillageIndex.travellerDensityAt(ws, pos);
        double popMult = MillVillageIndex.populationWeightAt(ws, pos);
        double combined = baseDensity * popMult;
        double chanceGate = Math.min(1.0, combined);

        msg(sender, String.format("§fBase Density: §a%.4f", baseDensity));
        msg(sender, String.format("§fPopulation Multiplier: §a%.4f", popMult));
        msg(sender, String.format("§fCombined Density: §a%.4f", combined));
        msg(sender, String.format("§fSpawn Chance Gate: §a%.2f", chanceGate));


// Regional budget around the player (example radius 1000 blocks)
        int regionalCap = MillVillageIndex.computeRegionalTravellerCap(ws, pos,
                TravellersModConfig.travellerRegionalRadius);
        msg(sender, String.format("§fRegional Traveller Budget (%dm): §b%d",
                TravellersModConfig.travellerRegionalRadius, regionalCap));

        int raiderRegionalCap = MillVillageIndex.computeRegionalRaiderCap(ws, pos,
                TravellersModConfig.raiderRegionalRadius);
        int raiderRegionalCount = TravellerSoftSpawner.countRaidersInRegion(ws, pos,
                TravellersModConfig.raiderRegionalRadius);
        msg(sender, String.format("§fRaiders (%dm): §c%d§f / %d",
                TravellersModConfig.raiderRegionalRadius, raiderRegionalCount, raiderRegionalCap));


        // --------------------------------------------------
        // 3) Nearest settlement info (village OR lone)
        // --------------------------------------------------
        MillVillageIndex.VillageInfo nearestAny =
                MillVillageIndex.findNearest2D(ws, pos, 2000); // big radius just for debug

        if (nearestAny != null) {
            int dx = nearestAny.center.getX() - pos.getX();
            int dz = nearestAny.center.getZ() - pos.getZ();
            double dist2D = Math.sqrt(dx * dx + dz * dz);

            String kind = nearestAny.isVillage ? "Village" : "Lone building";
            msg(sender, String.format("§fNearest Settlement: §b%s§f (%s)", nearestAny.name, kind));
            msg(sender, String.format("§f  Center: §b%d %d %d  §f(%.1f blocks away)",
                    nearestAny.center.getX(), nearestAny.center.getY(), nearestAny.center.getZ(), dist2D));

            if (nearestAny.isVillage) {
                int villagers = MillVillageIndex.countVillagersInVillage(ws, nearestAny.center);
                msg(sender, String.format("§f  Population: §a%d", villagers));
            }
        } else {
            msg(sender, "§cNo Millénaire village or lone building found within 2000 blocks.");
        }

        // --------------------------------------------------
        // 4) Local travellers & local cap
        // --------------------------------------------------
        int localCap = TravellersModConfig.travellerLocalCap;
        int localRadius = TravellersModConfig.travellerLocalCapRadius;

        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - localRadius, pos.getY() - 32, pos.getZ() - localRadius,
                pos.getX() + localRadius, pos.getY() + 32, pos.getZ() + localRadius
        );
        int localCount = ws.getEntitiesWithinAABB(EntityTraveller.class, box, e -> e.isEntityAlive()).size();

        msg(sender, String.format("§fLocal Travellers (%dm): §b%d§f / %d (cap)",
                localRadius, localCount, localCap));

        // --------------------------------------------------
        // 5) Would this position be a valid soft-spawn anchor?
        //     (ignoring random roll, just pure conditions)
        // --------------------------------------------------
        boolean anchorOk = true;
        StringBuilder reasons = new StringBuilder();

        if (combined <= 0.0) {
            anchorOk = false;
            reasons.append("density<=0 ");
        }


        // local cap at this anchor
        boolean localUnderCap = SpawnThrottles.underLocalCap(
                ws, pos, EntityTraveller.class,
                TravellersModConfig.travellerLocalCap,
                TravellersModConfig.travellerLocalCapRadius
        );
        if (!localUnderCap) {
            anchorOk = false;
            reasons.append("localCapReached ");
        }

        // NOTE: we could also check canEntitySpawn() here with a probe, but usually
        // if standable + light + caps are OK, the anchor is valid.

        msg(sender, "§fAnchor spawnable (ignoring RNG & global cap): " +
                (anchorOk ? "§aYES" : "§cNO") +
                (reasons.length() > 0 ? (" §7[" + reasons.toString().trim() + "]") : ""));

        msg(sender, "§e------------------------");
    }

    private void msg(ICommandSender sender, String text) {
        sender.sendMessage(new TextComponentString(text));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2; // OP
    }
}
