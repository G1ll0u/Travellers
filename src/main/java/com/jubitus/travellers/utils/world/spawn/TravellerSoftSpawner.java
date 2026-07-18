package com.jubitus.travellers.utils.world.spawn;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.utils.world.MPBIntegration;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.jubitus.travellers.entity.entityAI.travel.EntityAITravel.intersectsForbiddenLiquid;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class TravellerSoftSpawner {

    private static int merchantTickCounter = 0;
    private static int merchantVillageCursor = 0;
    private static int populationTickCounter = 0;


    private TravellerSoftSpawner() {
    }

    // ----------------- helpers -----------------


    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        WorldServer ws = server.getWorld(0); // overworld only
        if (ws == null) return;

        // Merchants maintenance
        if ((++merchantTickCounter % Math.max(1, TravellersConfig.SPAWN_WORKERS.merchantRecheckEveryTicks)) == 0) {
            ensureMerchantsForVillages(ws);
        }

        // Global population controller: keeps travellers/raiders/workers populated world-wide, including unloaded areas
        if (TravellersConfig.OFFSCREEN.globalPopulationEnabled) {
            int interval = Math.max(40, TravellersConfig.OFFSCREEN.globalPopulationRefreshTicks);
            if ((++populationTickCounter % interval) == 0) {
                runVirtualPopulationTopUp(ws);
            }
        }
    }


    /**
     * World-wide global population controller.
     * Iterates all known Millénaire settlements (villages + lone buildings) regardless of chunk state,
     * and spawns virtual traveller/raider/worker records to fill any deficit vs. the target population.
     * Virtual entities materialize automatically when a player approaches.
     */
    private static void runVirtualPopulationTopUp(WorldServer ws) {
        VirtualTravellerManager mgr = VirtualTravellerManager.get(ws);

        int totalVirtual = mgr.getTotalCount();
        int totalLoaded = ws.getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive).size();
        int globalCap = TravellersConfig.OFFSCREEN.globalPopulationCap;

        // Workers have their own budget so tuning traveller/raider population never starves workers or vice versa.
        List<EntityTravellerWorker> loadedWorkersList =
                ws.getEntities(EntityTravellerWorker.class, net.minecraft.entity.Entity::isEntityAlive);
        Map<BlockPos, Integer> loadedWorkersByVillage = new java.util.HashMap<>();
        for (EntityTravellerWorker w : loadedWorkersList) {
            BlockPos home = w.getHomeVillage();
            if (home == null) continue;
            loadedWorkersByVillage.merge(new BlockPos(home.getX(), 0, home.getZ()), 1, Integer::sum);
        }
        int totalWorkers = loadedWorkersList.size() + mgr.countByClass("WORKER");
        final int workerCap = TravellersConfig.SPAWN_WORKERS.workerLoadedCap;
        // Anti-duplication guard for villages whose Town Hall got registered more than once at nearby positions.
        java.util.Set<BlockPos> workerAnchors = new java.util.HashSet<>(loadedWorkersByVillage.keySet());
        final int workerMinDist = Math.max(0, TravellersConfig.WORKER.workerVillageMinDistance);

        if (totalVirtual + totalLoaded >= globalCap && (workerCap <= 0 || totalWorkers >= workerCap)) return;

        // Villages — uses Millenaire WorldSavedData, no chunk loading required.
        // Population may read as 0 for unloaded villages; travellerCapMinPerVillage/raiderCapMinPerVillage
        // still produce a non-zero target, so population always recovers after a reset.
        Map<MillVillageIndex.VillageInfo, Integer> populations = MillVillageIndex.countVillagersPerVillage(ws);

        int spawnsThisCycle = 0;
        final int maxSpawnsPerCycle = Math.max(1, TravellersConfig.OFFSCREEN.globalPopulationMaxSpawnsPerCycle);

        for (Map.Entry<MillVillageIndex.VillageInfo, Integer> entry : populations.entrySet()) {
            if (spawnsThisCycle >= maxSpawnsPerCycle) break;

            MillVillageIndex.VillageInfo vi = entry.getKey();
            int pop = entry.getValue();

            BlockPos villageCenter = vi.center;
            String cultureId = vi.cultureKey != null && !vi.cultureKey.isEmpty() ? vi.cultureKey : null;

            if (totalVirtual + totalLoaded < globalCap) {
                // Use per-villager formula; min cap ensures population recovers even when chunks are unloaded (pop=0)
                int targetTravellers = (int) Math.floor(pop * TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerVillager);
                targetTravellers = Math.max(targetTravellers, TravellersConfig.SPAWN_TRAVELLERS.travellerCapMinPerVillage);
                targetTravellers = Math.min(targetTravellers, TravellersConfig.SPAWN_TRAVELLERS.travellerCapMaxPerVillage);

                int targetRaiders = (int) Math.floor(pop * TravellersConfig.SPAWN_RAIDERS.raiderCapPerVillager);
                targetRaiders = Math.max(targetRaiders, TravellersConfig.SPAWN_RAIDERS.raiderCapMinPerVillage);
                targetRaiders = Math.min(targetRaiders, TravellersConfig.SPAWN_RAIDERS.raiderCapMaxPerVillage);

                int virtualTravellers = mgr.countByHomeVillage(villageCenter, "TRAVELLER");
                int loadedTravellers = countTravellersNearVillage(ws, villageCenter, 256, false);
                int needTravellers = targetTravellers - virtualTravellers - loadedTravellers;

                int virtualRaiders = mgr.countByHomeVillage(villageCenter, "RAIDER");
                int loadedRaiders = countTravellersNearVillage(ws, villageCenter, 256, true);
                int needRaiders = targetRaiders - virtualRaiders - loadedRaiders;

                for (int i = 0; i < needTravellers && spawnsThisCycle < maxSpawnsPerCycle
                        && totalVirtual + totalLoaded < globalCap; i++) {
                    spawnVirtualTraveller(ws, mgr, villageCenter, "TRAVELLER", cultureId);
                    spawnsThisCycle++;
                    totalVirtual++;
                }
                for (int i = 0; i < needRaiders && spawnsThisCycle < maxSpawnsPerCycle
                        && totalVirtual + totalLoaded < globalCap; i++) {
                    spawnVirtualTraveller(ws, mgr, villageCenter, "RAIDER", cultureId);
                    spawnsThisCycle++;
                    totalVirtual++;
                }
            }

            // Workers: same population-driven target formula used by the old loaded-only spawner
            // (MillVillageIndex.desiredWorkersForPopulation), now grown the same way as travellers/raiders.
            if ((workerCap <= 0 || totalWorkers < workerCap) && spawnsThisCycle < maxSpawnsPerCycle) {
                BlockPos workerKey = new BlockPos(villageCenter.getX(), 0, villageCenter.getZ());
                if (workerMinDist <= 0 || !withinXZOfAny(workerKey, workerAnchors, workerMinDist)) {
                    int targetWorkers = MillVillageIndex.desiredWorkersForPopulation(pop);
                    int haveLoaded = loadedWorkersByVillage.getOrDefault(workerKey, 0);
                    int haveVirtual = mgr.countByHomeVillage(workerKey, "WORKER");
                    int haveWorkers = haveLoaded + haveVirtual;
                    if (haveWorkers > 0) workerAnchors.add(workerKey);

                    int needWorkers = targetWorkers - haveWorkers;
                    for (int i = 0; i < needWorkers && spawnsThisCycle < maxSpawnsPerCycle
                            && (workerCap <= 0 || totalWorkers < workerCap); i++) {
                        spawnVirtualWorker(ws, mgr, workerKey, cultureId);
                        spawnsThisCycle++;
                        totalWorkers++;
                        workerAnchors.add(workerKey);
                    }
                }
            }
        }

        // Lone buildings — use fixed per-building caps, culture from the lone building itself
        if (spawnsThisCycle < maxSpawnsPerCycle) {
            List<net.minecraft.util.Tuple<BlockPos, String>> loneBuildings =
                    MillVillageIndex.getAllLoneBuildingCentersWithCulture(ws);
            int travellerPerLone = TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerLoneBuilding;
            int raiderPerLone = TravellersConfig.SPAWN_RAIDERS.raiderCapPerLoneBuilding;
            int workerPerLone = TravellersConfig.SPAWN_WORKERS.workerCapPerLoneBuilding;

            for (net.minecraft.util.Tuple<BlockPos, String> lone : loneBuildings) {
                if (spawnsThisCycle >= maxSpawnsPerCycle) break;

                BlockPos loneCenter = lone.getFirst();
                String loneCulture = lone.getSecond();

                if (totalVirtual + totalLoaded < globalCap) {
                    int need = travellerPerLone - mgr.countByHomeVillage(loneCenter, "TRAVELLER")
                            - countTravellersNearVillage(ws, loneCenter, 128, false);
                    for (int i = 0; i < need && spawnsThisCycle < maxSpawnsPerCycle
                            && totalVirtual + totalLoaded < globalCap; i++) {
                        spawnVirtualTraveller(ws, mgr, loneCenter, "TRAVELLER", loneCulture);
                        spawnsThisCycle++;
                        totalVirtual++;
                    }

                    int needR = raiderPerLone - mgr.countByHomeVillage(loneCenter, "RAIDER")
                            - countTravellersNearVillage(ws, loneCenter, 128, true);
                    for (int i = 0; i < needR && spawnsThisCycle < maxSpawnsPerCycle
                            && totalVirtual + totalLoaded < globalCap; i++) {
                        spawnVirtualTraveller(ws, mgr, loneCenter, "RAIDER", loneCulture);
                        spawnsThisCycle++;
                        totalVirtual++;
                    }
                }

                if (workerPerLone > 0 && (workerCap <= 0 || totalWorkers < workerCap)) {
                    int needW = workerPerLone - mgr.countByHomeVillage(loneCenter, "WORKER");
                    for (int i = 0; i < needW && spawnsThisCycle < maxSpawnsPerCycle
                            && (workerCap <= 0 || totalWorkers < workerCap); i++) {
                        spawnVirtualWorker(ws, mgr, loneCenter, loneCulture);
                        spawnsThisCycle++;
                        totalWorkers++;
                    }
                }
            }
        }
    }

    /** Count real (loaded) travellers or raiders near a village center within given radius. */
    private static int countTravellersNearVillage(WorldServer ws, BlockPos center, int radius, boolean raidersOnly) {
        AxisAlignedBB box = new AxisAlignedBB(
                center.getX() - radius, 0, center.getZ() - radius,
                center.getX() + radius, ws.getActualHeight(), center.getZ() + radius);
        if (raidersOnly) {
            return ws.getEntitiesWithinAABB(EntityTravellerRaider.class, box,
                    net.minecraft.entity.Entity::isEntityAlive).size();
        } else {
            int total = ws.getEntitiesWithinAABB(EntityTraveller.class, box,
                    net.minecraft.entity.Entity::isEntityAlive).size();
            // Subtract workers and raiders (we only want plain travellers)
            total -= ws.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                    net.minecraft.entity.Entity::isEntityAlive).size();
            total -= ws.getEntitiesWithinAABB(EntityTravellerRaider.class, box,
                    net.minecraft.entity.Entity::isEntityAlive).size();
            return Math.max(0, total);
        }
    }

    /**
     * Creates VirtualTravellerRecords for a traveller or raider (and optional group followers).
     * The leader spawns at a random mid-route point; followers share the same route and track the leader.
     * No world entity is created.
     */
    private static void spawnVirtualTraveller(WorldServer ws, VirtualTravellerManager mgr,
                                              BlockPos homeVillage, String entityClass, @Nullable String cultureId) {
        List<BlockPos> allCenters = MillVillageIndex.getAllSettlementCenters(ws);
        if (allCenters.size() < 2) return;

        // Pick a random destination different from home
        List<BlockPos> candidates = new ArrayList<>(allCenters);
        candidates.removeIf(c -> c.getX() == homeVillage.getX() && c.getZ() == homeVillage.getZ());
        if (candidates.isEmpty()) return;
        BlockPos dest = candidates.get(ws.rand.nextInt(candidates.size()));

        // Build the full route from home to destination
        List<BlockPos> fullRoute = new ArrayList<>();
        boolean useRoads = MPBIntegration.PRESENT
                && (!"RAIDER".equals(entityClass) || TravellersConfig.SPAWN_RAIDERS.raidersUseUltreiaPaths);
        if (useRoads) {
            List<BlockPos> mpbRoute = MPBIntegration.findRoadRoute(
                    ws, homeVillage.getX(), homeVillage.getZ(), dest.getX(), dest.getZ(), 48);
            if (mpbRoute != null && !mpbRoute.isEmpty()) {
                fullRoute.addAll(mpbRoute);
            }
        }
        // Ensure destination is the final waypoint
        if (fullRoute.isEmpty()
                || fullRoute.get(fullRoute.size() - 1).getX() != dest.getX()
                || fullRoute.get(fullRoute.size() - 1).getZ() != dest.getZ()) {
            fullRoute.add(dest);
        }

        // Pick a random waypoint in the middle 20-80% of the route as the spawn point.
        // This places the entity somewhere between two villages rather than at a village center.
        int routeSize = fullRoute.size();
        int lo = routeSize / 5;
        int hi = (routeSize * 4) / 5;
        if (lo >= hi) lo = 0;
        int spawnIdx = (lo >= hi) ? lo : lo + ws.rand.nextInt(hi - lo);
        BlockPos spawnWaypoint = fullRoute.get(spawnIdx);

        // Resolve Y: use real terrain surface if chunk is loaded, otherwise keep waypoint Y or use 64
        BlockPos spawnPos = resolveVirtualSpawnY(ws, spawnWaypoint);

        // Raiders must not spawn inside village perimeters
        if ("RAIDER".equals(entityClass)
                && MillVillageIndex.isNearVillage(ws, spawnPos, TravellersConfig.SPAWN_RAIDERS.raiderMinVillageDistance)) {
            return;
        }

        // Build un-spawned temp entity to populate NBT (culture, name, equipment)
        EntityTraveller tempEntity = "RAIDER".equals(entityClass)
                ? new EntityTravellerRaider(ws)
                : new EntityTraveller(ws);
        tempEntity.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        net.minecraft.entity.IEntityLivingData spawnData = (cultureId != null && !cultureId.isEmpty())
                ? new EntityTraveller.SpawnData(cultureId) : null;
        tempEntity.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        net.minecraft.nbt.NBTTagCompound entityNbt = new net.minecraft.nbt.NBTTagCompound();
        tempEntity.writeToNBT(entityNbt);

        VirtualTravellerRecord r = new VirtualTravellerRecord(
                tempEntity.getUniqueID(),
                spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                ws.rand.nextFloat() * 360f,
                ws.provider.getDimension(),
                entityNbt
        );
        r.entityClass = entityClass;
        r.homeVillage = new BlockPos(homeVillage.getX(), 0, homeVillage.getZ());

        // Route = remaining waypoints from spawnIdx onward
        r.travelRoute = new ArrayList<>(fullRoute.subList(spawnIdx, routeSize));
        r.routeIndex = 0;
        r.goal = r.travelRoute.isEmpty() ? dest : r.travelRoute.get(0);

        r.virtualizedTick = ws.getTotalWorldTime();
        mgr.addRecord(r);

        // Roll for group followers — same culture as the leader, same route
        int groupSize = rollGroupSize(ws.rand, entityClass);
        for (int i = 1; i < groupSize; i++) {
            spawnVirtualFollower(ws, mgr, r, entityClass, cultureId, spawnPos);
        }
    }

    private static int rollGroupSize(java.util.Random rand, String entityClass) {
        boolean isTraveller = "TRAVELLER".equals(entityClass);
        int chance = isTraveller
                ? TravellersConfig.SPAWN_TRAVELLERS.groupSpawnChance
                : TravellersConfig.SPAWN_RAIDERS.groupSpawnChance;
        if (chance <= 0 || rand.nextInt(100) >= chance) return 1;
        int min = isTraveller
                ? TravellersConfig.SPAWN_TRAVELLERS.groupMinSize
                : TravellersConfig.SPAWN_RAIDERS.groupMinSize;
        int max = isTraveller
                ? TravellersConfig.SPAWN_TRAVELLERS.groupMaxSize
                : TravellersConfig.SPAWN_RAIDERS.groupMaxSize;
        if (min >= max) return min;
        return min + rand.nextInt(max - min + 1);
    }

    private static void spawnVirtualFollower(WorldServer ws, VirtualTravellerManager mgr,
                                             VirtualTravellerRecord leader, String entityClass,
                                             @Nullable String cultureId, BlockPos spawnPos) {
        EntityTraveller tempEntity = "RAIDER".equals(entityClass)
                ? new EntityTravellerRaider(ws)
                : new EntityTraveller(ws);
        tempEntity.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        net.minecraft.entity.IEntityLivingData spawnData = (cultureId != null && !cultureId.isEmpty())
                ? new EntityTraveller.SpawnData(cultureId) : null;
        tempEntity.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);
        tempEntity.setGroupLeaderUUID(leader.uuid);

        net.minecraft.nbt.NBTTagCompound entityNbt = new net.minecraft.nbt.NBTTagCompound();
        tempEntity.writeToNBT(entityNbt);

        VirtualTravellerRecord r = new VirtualTravellerRecord(
                tempEntity.getUniqueID(),
                spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                ws.rand.nextFloat() * 360f,
                ws.provider.getDimension(),
                entityNbt
        );
        r.entityClass = entityClass;
        r.homeVillage = leader.homeVillage;
        r.groupLeaderUUID = leader.uuid;
        r.travelRoute = new ArrayList<>(leader.travelRoute);
        r.routeIndex = leader.routeIndex;
        r.goal = leader.goal;
        r.virtualizedTick = ws.getTotalWorldTime();
        mgr.addRecord(r);
    }

    /**
     * Creates a VirtualTravellerRecord for a worker at its home village, with a route already
     * planned so materialization behaves exactly like a freshly spawned worker. No world entity
     * is created.
     */
    private static void spawnVirtualWorker(WorldServer ws, VirtualTravellerManager mgr,
                                           BlockPos homeVillage, @Nullable String cultureId) {
        BlockPos spawnPos = resolveVirtualSpawnY(ws, homeVillage);

        EntityTravellerWorker tempEntity = new EntityTravellerWorker(ws);
        tempEntity.setHomeVillage(homeVillage);
        tempEntity.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);

        TravelCommon tc = tempEntity.getTravelCommon();
        tc.updateNearestVillage();
        tc.ensureRoutePlanned();
        if (tc.route.isEmpty()) return; // not enough destinations to travel
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        net.minecraft.entity.IEntityLivingData spawnData = (cultureId != null && !cultureId.isEmpty())
                ? new EntityTraveller.SpawnData(cultureId) : null;
        tempEntity.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        net.minecraft.nbt.NBTTagCompound entityNbt = new net.minecraft.nbt.NBTTagCompound();
        tempEntity.writeToNBT(entityNbt);

        VirtualTravellerRecord r = new VirtualTravellerRecord(
                tempEntity.getUniqueID(),
                spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                ws.rand.nextFloat() * 360f,
                ws.provider.getDimension(),
                entityNbt
        );
        r.entityClass = "WORKER";
        r.homeVillage = new BlockPos(homeVillage.getX(), 0, homeVillage.getZ());
        r.workerMode = "IDLE";
        // Non-hired IDLE workers never get their goal re-derived by refreshGoal() (it only
        // tracks dynamic targets like an owner or catchup); it must be seeded here once, exactly
        // like virtualizeWorker() does via getVirtualGoal(), or advanceVirtual() has nothing to
        // walk toward and the worker sits frozen at home until it materializes.
        r.goal = tempEntity.getVirtualGoal();
        r.virtualizedTick = ws.getTotalWorldTime();
        mgr.addRecord(r);
    }

    /** Returns a spawn position for a virtual entity: real surface Y if chunk is loaded, waypoint Y or 64 otherwise. */
    private static BlockPos resolveVirtualSpawnY(WorldServer ws, BlockPos xzPos) {
        // Never land a virtual spawn point on/inside a Millénaire building (e.g. a worker's home
        // village center is the Town Hall itself). Pure metadata check, works without chunk loading.
        BlockPos safeXZ = MillVillageIndex.resolveOutsideBuildings(ws, xzPos, 256);
        if (chunkExists(ws, safeXZ)) {
            BlockPos top = ws.getTopSolidOrLiquidBlock(safeXZ);
            if (top != null && top.getY() > 0) return top;
        }
        return safeXZ.getY() > 0 ? safeXZ : new BlockPos(safeXZ.getX(), 64, safeXZ.getZ());
    }

    public static int countTravellersInRegion(WorldServer ws, BlockPos center, int radiusXZ) {
        AxisAlignedBB box = new AxisAlignedBB(
                center.getX() - radiusXZ, 0, center.getZ() - radiusXZ,
                center.getX() + radiusXZ, ws.getActualHeight(), center.getZ() + radiusXZ
        );
        return ws.getEntitiesWithinAABB(EntityTraveller.class, box, net.minecraft.entity.Entity::isEntityAlive).size();
    }

    public static int countRaidersInRegion(WorldServer ws, BlockPos center, int radiusXZ) {
        AxisAlignedBB box = new AxisAlignedBB(
                center.getX() - radiusXZ, 0, center.getZ() - radiusXZ,
                center.getX() + radiusXZ, ws.getActualHeight(), center.getZ() + radiusXZ
        );
        return ws.getEntitiesWithinAABB(EntityTravellerRaider.class, box, net.minecraft.entity.Entity::isEntityAlive).size();
    }

    private static boolean isStandable(WorldServer w, BlockPos feet) {
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;
        if (w.getBlockState(feet).getMaterial().isLiquid()) return false;
        if (w.getBlockState(feet.up()).getMaterial().isLiquid()) return false;

        // allow replaceable at feet/head, require solid-ish below
        boolean feetFree = w.getBlockState(feet).getMaterial().isReplaceable();
        boolean headFree = w.getBlockState(feet.up()).getMaterial().isReplaceable();
        boolean support = w.getBlockState(feet.down()).isOpaqueCube()
                || w.getBlockState(feet.down()).getBlockFaceShape(w, feet.down(), EnumFacing.UP)
                == net.minecraft.block.state.BlockFaceShape.SOLID;
        return feetFree && headFree && support;
    }

    /**
     * Heuristic: "mob farm–like" = many mobs inside + fence ring around.
     */
    private static boolean isMobFarmLikeArea(WorldServer w, BlockPos pos) {
        // Tunable knobs – consider putting in config if you want it tweakable
        final int radius = TravellersConfig.MOB_FARM.mobFarmCheckRadius;
        final int minEntities = TravellersConfig.MOB_FARM.mobFarmEntityThreshold;   // how many mobs before we call it crowded
        final int fenceRadius = TravellersConfig.MOB_FARM.mobFarmFenceRadius;
        final int minFenceBlocks = TravellersConfig.MOB_FARM.mobFarmFenceThreshold;

        if (!isCrowdedArea(w, pos, radius, minEntities)) return false;
        return hasFencePerimeter(w, pos, fenceRadius, minFenceBlocks);
    }

    private static boolean isCrowdedArea(WorldServer w, BlockPos pos, int radius, int minEntities) {
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - radius, pos.getY() - 2, pos.getZ() - radius,
                pos.getX() + radius + 1, pos.getY() + 4, pos.getZ() + radius + 1
        );

        // Count living mobs except players + travellers/raiders
        List<EntityLivingBase> list = w.getEntitiesWithinAABB(EntityLivingBase.class, box, e ->
                !(e instanceof EntityPlayer) &&
                        !(e instanceof EntityTraveller) &&
                        !(e instanceof EntityTravellerRaider)
        );

        return list.size() >= minEntities;
    }
// --- mob farm / enclosure detection ---

    /**
     * Detects a fence/wall "ring" around the position, typical of a mob farm pen.
     * It scans only the perimeter of a square with given radius.
     */
    private static boolean hasFencePerimeter(World w, BlockPos center, int radius, int minFenceBlocks) {
        int fences = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Only perimeter: at least one coord must be at max distance
                if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                for (int dy = 0; dy <= 1; dy++) {
                    p.setPos(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!w.isBlockLoaded(p)) continue;

                    Block b = w.getBlockState(p).getBlock();
                    if (b instanceof net.minecraft.block.BlockFence
                            || b instanceof net.minecraft.block.BlockWall
                            || b instanceof net.minecraft.block.BlockFenceGate) {
                        fences++;
                        if (fences >= minFenceBlocks) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Ensure each village has the desired number of travellers merchants
     * based on its Millénaire population. Loads chunks near the village
     * center if needed.
     */
    private static void ensureMerchantsForVillages(WorldServer ws) {
        MillWorldData wd = MillVillageIndex.getWD(ws);
        if (wd == null) return;

        List<BlockPos> centers = MillVillageIndex.getAllVillageCenters(ws);
        if (centers.isEmpty()) return;

        // Count existing merchants per home village (only LOADED entities)
        Map<BlockPos, Integer> merchantsByVillage = new java.util.HashMap<>();
        List<EntityTravellerMerchant> merchants =
                ws.getEntities(EntityTravellerMerchant.class, net.minecraft.entity.Entity::isEntityAlive);

        for (EntityTravellerMerchant m : merchants) {
            BlockPos home = m.getHomeVillage();
            if (home == null) continue;
            merchantsByVillage.merge(home.toImmutable(), 1, Integer::sum);
        }

        int globalLoadedMerchants = merchants.size();
        final int globalCap = TravellersConfig.SPAWN_WORKERS.merchantLoadedCap;
        if (globalCap > 0 && globalLoadedMerchants >= globalCap) return;

        int minDist = TravellersConfig.WORKER.merchantVillageMinDistance;
        if (minDist < 0) minDist = 0;

        // Anchors start with villages that CURRENTLY have loaded merchants
        java.util.Set<BlockPos> merchantAnchors = new java.util.HashSet<>(merchantsByVillage.keySet());

        // --- NEW: only process a small batch per pass ---
        final int total = centers.size();
        final int n = Math.min(TravellersConfig.MERCHANT.merchantVillagesPerPass, total);

        for (int it = 0; it < n; it++) {
            if (globalCap > 0 && globalLoadedMerchants >= globalCap) return;

            BlockPos v = centers.get((merchantVillageCursor++ % total + total) % total).toImmutable();

            // Must have TH
            Building th = MillVillageIndex.getTownHallBuilding(wd, v);
            if (th == null) continue;

            // --- NEW: do NOT load chunks during maintenance ---
            if (!isVillageActiveForWorkerSpawn(ws, v)) {
                continue;
            }

            // Skip if too close to an existing anchor
            if (minDist > 0 && withinXZOfAny(v, merchantAnchors, minDist)) {
                continue;
            }

            int villagers = MillVillageIndex.countVillagersInVillage(ws, v);
            int desired = MillVillageIndex.desiredMerchantsForPopulation(villagers);
            if (desired <= 0) continue;

            int current = merchantsByVillage.getOrDefault(v, 0);
            int missing = desired - current;
            if (missing <= 0) {
                // Still an anchor if it already has merchants loaded
                if (current > 0) merchantAnchors.add(v);
                continue;
            }

            // Mark as anchor so we don’t populate neighbours in the same pass
            merchantAnchors.add(v);

            // Spawn missing, but honor global loaded cap
            for (int i = 0; i < missing; i++) {
                if (globalCap > 0 && globalLoadedMerchants >= globalCap) return;

                spawnMerchantAtVillage(ws, v);
                globalLoadedMerchants++;

                // Also update our local “loaded merchants per village” map,
                // so we don't keep spawning more in the same pass
                merchantsByVillage.merge(v, 1, Integer::sum);
            }
        }
    }

    /**
     * Spawns a traveller_merchant at (or near) the given village center,
     * sets its home village, and puts it into the world.
     */
    public static void spawnMerchantAtVillage(WorldServer ws, BlockPos villageCenter) {

// Load a slightly larger area because we might spawn OUTSIDE the village center chunk
// NEW:
        if (!isVillageActiveForWorkerSpawn(ws, villageCenter)) {
            return; // will be handled later when a player loads the area
        }

// ring spawn: near village, not necessarily inside
        final int minR = 10;  // don't spawn on the townhall roof
        final int maxR = 28;  // still "near" the village

        BlockPos spawnPos = findNaturalSpawnNearVillage(ws, villageCenter, minR, maxR, 80, 2);

// fallback: try a wider ring
        if (spawnPos == null) {
            spawnPos = findNaturalSpawnNearVillage(ws, villageCenter, 6, 40, 120, 1);
        }

// last resort: keep your old behavior if everything fails
        if (spawnPos == null) {
            spawnPos = ws.getTopSolidOrLiquidBlock(villageCenter);
        }

        EntityTravellerMerchant m = new EntityTravellerMerchant(ws);
        m.setHomeVillage(villageCenter);
        m.setLocationAndAngles(
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                ws.rand.nextFloat() * 360f,
                0
        );

        if (!ws.checkNoEntityCollision(m.getEntityBoundingBox())) return;
        if (!ws.getCollisionBoxes(m, m.getEntityBoundingBox()).isEmpty()) return;

        // Pre-seed travel route BEFORE spawning
        TravelCommon tc = m.getTravelCommon();
        tc.updateNearestVillage();
        tc.ensureRoutePlanned();
        if (tc.route.isEmpty()) return; // not enough destinations to travel
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        IEntityLivingData spawnData =
                createCultureSpawnDataForPos(ws, spawnPos, ws.rand);
        m.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        ws.spawnEntity(m);

    }

    private static boolean isVillageActiveForWorkerSpawn(WorldServer ws, BlockPos center) {
        ChunkProviderServer cps = ws.getChunkProvider();

        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;

        // Cas le plus simple et le moins cher :
        // si le chunk du Town Hall est chargé, on considère le village actif.
        if (cps.getLoadedChunk(cx, cz) != null) {
            return true;
        }

        // Petit fallback souple : on accepte si au moins 2 chunks dans le voisinage immédiat
        // sont chargés. Ça évite de rater certains villages partiellement loadés.
        int loaded = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (cps.getLoadedChunk(cx + dx, cz + dz) != null) {
                    loaded++;
                    if (loaded >= 2) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Nullable
    private static BlockPos findNaturalSpawnNearVillage(WorldServer ws, BlockPos center,
                                                        int minDist, int maxDist,
                                                        int tries, int manMadeScanRadius) {
        Random r = ws.rand;

        minDist = Math.max(0, minDist);
        maxDist = Math.max(minDist + 1, maxDist);

        for (int t = 0; t < tries; t++) {
            double theta = r.nextDouble() * (Math.PI * 2.0);
            int dist = minDist + r.nextInt(maxDist - minDist + 1);

            int x = center.getX() + (int) Math.round(Math.cos(theta) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(theta) * dist);

            BlockPos guess = new BlockPos(x, center.getY(), z);

            // Don’t load chunks unless you explicitly want to
            if (!chunkExists(ws, guess)) continue;

            BlockPos top = ws.getTopSolidOrLiquidBlock(guess);

            // probe downward to find a natural standable “feet” position
            for (int dy = 0; dy <= 10; dy++) {
                BlockPos feet = top.down(dy);

                if (!ws.isBlockLoaded(feet)) continue;

                // must be natural terrain + not “built” neighborhood
                if (!isNaturalStandable(ws, feet)) continue;
                if (manMadeScanRadius > 0 && looksManMadeAround(ws, feet, manMadeScanRadius)) continue;

                if (com.jubitus.travellers.utils.world.MPBIntegration.PRESENT) {
                    BlockPos roadPos = com.jubitus.travellers.utils.world.MPBIntegration
                            .findRoadSpawnPos(ws, feet.getX(), feet.getZ(), 24);
                    if (roadPos != null && isNaturalStandable(ws, roadPos)) {
                        return roadPos;
                    }
                }

                return feet;
            }
        }

        return null;
    }

    @Nullable
    private static IEntityLivingData createCultureSpawnDataForPos(WorldServer ws, BlockPos pos, Random rand) {
        if (!TravellersConfig.SPAWN.travellerCultureWeightedSpawn) return null;

        String cultureId = MillVillageIndex.pickCultureForTravellerSpawn(ws, pos, rand);
        if (cultureId == null || cultureId.isEmpty()) return null;

        // Same spawn data class you already use for normal travellers
        return new EntityTraveller.SpawnData(cultureId);
    }

    private static boolean chunkExists(WorldServer ws, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        return ws.getChunkProvider().chunkExists(cx, cz);
    }

    private static boolean isNaturalStandable(WorldServer w, BlockPos feet) {
        if (!w.isBlockLoaded(feet) || !w.isBlockLoaded(feet.up()) || !w.isBlockLoaded(feet.down())) return false;

        // feet & head must be free of liquids and be replaceable (air/tall grass/snow layer ok)
        if (w.getBlockState(feet).getMaterial().isLiquid()) return false;
        if (w.getBlockState(feet.up()).getMaterial().isLiquid()) return false;

        boolean feetFree = w.getBlockState(feet).getMaterial().isReplaceable();
        boolean headFree = w.getBlockState(feet.up()).getMaterial().isReplaceable();
        if (!(feetFree && headFree)) return false;

        // the supporting block must be natural terrain (not stairs/planks/etc.)
        BlockPos below = feet.down();
        if (!isNaturalGround(w, below)) return false;

        // optional but very effective: reject if the neighbourhood looks "built"
        return !looksManMadeAround(w, feet, 2);
    }

    private static boolean looksManMadeAround(World w, BlockPos center, int r) {
        // Scan a small cube; if we see any obviously crafted blocks, veto the spot.
        // This is cheap (<= 125 checks for r=2) and very effective at rejecting roofs/walls.
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 2; dy++) { // look slightly below and above feet
                for (int dz = -r; dz <= r; dz++) {
                    p.setPos(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!w.isBlockLoaded(p)) continue;
                    IBlockState st = w.getBlockState(p);
                    Block b = st.getBlock();

                    // Any tile entity nearby screams "built"
                    if (b.hasTileEntity(st)) return true;

                    // Quick instanceof checks for common structure blocks
                    if (b instanceof net.minecraft.block.BlockStairs) return true;
                    if (b instanceof net.minecraft.block.BlockSlab) return true;
                    if (b instanceof net.minecraft.block.BlockFence || b instanceof net.minecraft.block.BlockFenceGate)
                        return true;
                    if (b instanceof net.minecraft.block.BlockDoor || b instanceof net.minecraft.block.BlockTrapDoor)
                        return true;
                    if (b instanceof net.minecraft.block.BlockPane) return true;
                    if (b instanceof net.minecraft.block.BlockLadder) return true;
                    if (b instanceof net.minecraft.block.BlockCarpet) return true;
                    if (b instanceof net.minecraft.block.BlockWorkbench) return true;
                    if (b instanceof net.minecraft.block.BlockAnvil) return true;
                    if (b instanceof net.minecraft.block.BlockBed) return true;

                    // Material-based quick rejects (crafted building stuff)
                    Material m = st.getMaterial();
                    if (m == Material.WOOD) return true;   // planks etc.
                    if (m == Material.GLASS) return true;
                    if (m == Material.PISTON) return true;

                    // Registry-name heuristics catch modded content
                    ResourceLocation id = b.getRegistryName();
                    if (id != null) {
                        String path = id.getPath();
                        if (path.contains("brick") || path.contains("concrete") || path.contains("tile")
                                || path.contains("plaster") || path.contains("metal") || path.contains("factory"))
                            return true;
                        if (path.contains("roof") || path.contains("wall") || path.contains("window") || path.contains("lamp"))
                            return true;
                        if (path.contains("stairs") || path.contains("slab") || path.contains("planks")) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isNaturalGround(World w, BlockPos pos) {
        IBlockState st = w.getBlockState(pos);
        Block b = st.getBlock();
        Material m = st.getMaterial();

        // Must be solid on top (same as your old support check)
        boolean support = st.isOpaqueCube() ||
                st.getBlockFaceShape(w, pos, EnumFacing.UP) == net.minecraft.block.state.BlockFaceShape.SOLID;
        if (!support) return false;

        // Fast material whitelist: typical terrain materials
        if (m == Material.GRASS || m == Material.GROUND || m == Material.SAND || m == Material.CLAY || m == Material.SNOW)
            return true;

        // Rock: allow only truly-natural rock, not crafted rock blocks
        if (m == Material.ROCK) {
            // Permit vanilla natural stones; veto bricks, polished, stairs, etc.
            ResourceLocation id = b.getRegistryName();
            if (id == null) return false;
            String path = id.getPath(); // e.g. "stone", "andesite", "diorite", "granite", "end_stone", "netherrack", "sandstone"
            // Hard-allow common natural blocks by registry id substring
            if (id.getNamespace().equals("minecraft")) {
                if (path.equals("stone") || path.equals("netherrack") || path.equals("end_stone")
                        || path.equals("gravel") || path.equals("sandstone")) return true;
                // Reject obvious building blocks
                if (path.contains("brick") || path.contains("bricks") || path.contains("polished")
                        || path.contains("planks") || path.contains("stairs") || path.contains("slab")
                        || path.contains("pillar") || path.contains("chiseled")) return false;
            }
            // For modded blocks, be conservative: treat unknown ROCK as man-made unless it looks like raw stone/sandstone/end
            return path.contains("stone") || path.contains("andesite") || path.contains("diorite") || path.contains("granite")
                    || path.contains("end_stone") || path.contains("netherrack") || path.contains("sandstone");
        }

        // Special vanilla naturals that aren’t covered only by materials
        return b == net.minecraft.init.Blocks.MYCELIUM;
    }

    public static void respawnWorkerAtHome(WorldServer ws, BlockPos villageCenter) {
        if (villageCenter == null) return;

// NEW:
        if (!isVillageActiveForWorkerSpawn(ws, villageCenter)) {
            return; // will be handled later when a player loads the area
        }

        BlockPos spawnPos = findNaturalSpawnNearVillage(ws, villageCenter, 10, 28, 80, 2);
        if (spawnPos == null) {
            spawnPos = findNaturalSpawnNearVillage(ws, villageCenter, 6, 40, 120, 1);
        }
        if (spawnPos == null) {
            spawnPos = ws.getTopSolidOrLiquidBlock(villageCenter);
        }


        EntityTravellerWorker w = new EntityTravellerWorker(ws);
        w.setHomeVillage(villageCenter);
        w.setLocationAndAngles(
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                ws.rand.nextFloat() * 360f,
                0
        );

        if (!ws.checkNoEntityCollision(w.getEntityBoundingBox())) return;
        if (!ws.getCollisionBoxes(w, w.getEntityBoundingBox()).isEmpty()) return;

        IEntityLivingData spawnData =
                createCultureSpawnDataForPos(ws, spawnPos, ws.rand);
        w.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

// Optional: last gate (Forge/vanilla spawn checks) to avoid weird spawns
        Event.Result res = net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(w, ws,
                (float) w.posX, (float) w.posY, (float) w.posZ);
        if (res == Event.Result.DENY) return;

// Spawn and only then register
        boolean ok = ws.spawnEntity(w);
        if (!ok) return;

        TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);
        reg.setWorkerFor(villageCenter, w.getUniqueID());

    }

    public static void respawnMerchantAtHome(WorldServer ws, BlockPos villageCenter) {
        if (villageCenter == null) return;

        // 1) force-load chunks around the village
        if (!isVillageActiveForWorkerSpawn(ws, villageCenter)) {
            return; // will be handled later when a player loads the area
        }

        // 2) OPTIONAL: only respawn if the village actually still has a TH
        MillWorldData wd = MillVillageIndex.getWD(ws);
        if (wd == null) return;
        Building th = MillVillageIndex.getTownHallBuilding(wd, villageCenter);
        if (th == null) {
            // Town hall really gone -> do not respawn
            return;
        }

        // 3) existing spawn logic
        BlockPos spawnPos = findGroundSpawnNear(ws, villageCenter, 6);
        if (spawnPos == null) {
            spawnPos = findGroundSpawnNear(ws, villageCenter, 12);
        }
        if (spawnPos == null) {
            spawnPos = ws.getTopSolidOrLiquidBlock(villageCenter);
        }

        EntityTravellerMerchant m = new EntityTravellerMerchant(ws);
        m.setHomeVillage(villageCenter);
        m.setLocationAndAngles(
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                ws.rand.nextFloat() * 360f,
                0
        );

        if (!ws.checkNoEntityCollision(m.getEntityBoundingBox())) return;
        if (!ws.getCollisionBoxes(m, m.getEntityBoundingBox()).isEmpty()) return;

        IEntityLivingData spawnData =
                createCultureSpawnDataForPos(ws, spawnPos, ws.rand);
        m.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        Event.Result res = net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(m, ws,
                (float) m.posX, (float) m.posY, (float) m.posZ);
        if (res == Event.Result.DENY) return;

        boolean ok = ws.spawnEntity(m);
        if (!ok) return;

        TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);
        reg.addMerchantFor(villageCenter, m.getUniqueID());
    }


// TravellerSoftSpawner.java

    @Nullable
    private static BlockPos findGroundSpawnNear(WorldServer ws, BlockPos origin, int radius) {
        Random r = ws.rand;

        for (int tries = 0; tries < 30; tries++) {
            int dx = r.nextInt(radius * 2 + 1) - radius;
            int dz = r.nextInt(radius * 2 + 1) - radius;

            BlockPos guess = origin.add(dx, 0, dz);

            // Don't load chunks
            if (!ws.getChunkProvider().chunkExists(guess.getX() >> 4, guess.getZ() >> 4)) continue;

            // Find topmost solid block at this column
            BlockPos ground = ws.getTopSolidOrLiquidBlock(guess);

            // Probe downward until we find a standable spot
            for (int dy = 0; dy < 5; dy++) {
                BlockPos p = ground.down(dy);

                if (isStandable(p, ws)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static boolean isStandable(BlockPos pos, WorldServer w) {
        if (!w.isBlockLoaded(pos) || !w.isBlockLoaded(pos.up())) return false;

        // block below must be solid
        BlockPos below = pos.down();
        if (!w.getBlockState(below).isTopSolid()) return false;

        // feet + head must be airlike
        IBlockState feet = w.getBlockState(pos);
        IBlockState head = w.getBlockState(pos.up());

        if (!feet.getMaterial().isReplaceable()) return false;
        return head.getMaterial().isReplaceable();
    }

    private static boolean withinXZ(BlockPos a, BlockPos b, int radius) {
        if (a == null || b == null) return false;
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        int r2 = radius * radius;
        return dx * dx + dz * dz <= r2;
    }

    private static boolean withinXZOfAny(BlockPos center,
                                         java.util.Collection<BlockPos> anchors,
                                         int radius) {
        if (radius <= 0) return false;
        int r2 = radius * radius;

        for (BlockPos other : anchors) {
            if (other == null) continue;
            if (other.equals(center)) continue; // don't block yourself
            int dx = center.getX() - other.getX();
            int dz = center.getZ() - other.getZ();
            if (dx * dx + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onMerchantDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EntityTravellerMerchant)) return;
        EntityTravellerMerchant m = (EntityTravellerMerchant) event.getEntity();
        if (m.world.isRemote) return;

        BlockPos home = m.getHomeVillage();
        if (home == null) return;

        TravellerVillageRegistry reg = TravellerVillageRegistry.get((WorldServer) m.world);
        reg.removeMerchantFor(home, m.getUniqueID());

        // Do NOT respawn here. ensureMerchantsForVillages will respawn later (batched + loaded-only).
    }


}
