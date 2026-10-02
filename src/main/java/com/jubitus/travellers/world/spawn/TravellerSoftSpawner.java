package com.jubitus.travellers.world.spawn;

import com.jubitus.travellers.Tags;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.world.mill.MillSettlements;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
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
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.Level;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.world.pathing.MPBIntegration;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.jubitus.travellers.entity.entityai.travel.TravelTerrain.intersectsForbiddenLiquid;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class TravellerSoftSpawner {

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

        // globalPopulationCap governs travellers + raiders only (see its config comment) — workers have
        // their own budget (workerLoadedCap) and must not be counted against this one.
        int totalLoaded = ws.getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive).size()
                - loadedWorkersList.size();

        // Same identity-based accounting as workers above (home village, not proximity to it) — a
        // traveller/raider is meant to roam far from home and can materialize anywhere along its
        // route. Counting "loaded population" by proximity to the village instead used to undercount
        // any traveller that had wandered off and wasn't near its home village anymore, making the
        // controller think population was missing and spawn more forever (see 2026-08-05 fix notes).
        List<EntityTraveller> loadedTravellerList = ws.getEntities(EntityTraveller.class, e -> e.isEntityAlive()
                && !(e instanceof EntityTravellerWorker)
                && !(e instanceof EntityTravellerRaider));
        Map<BlockPos, Integer> loadedTravellersByVillage = new java.util.HashMap<>();
        for (EntityTraveller t : loadedTravellerList) {
            BlockPos home = t.getHomeVillage();
            if (home == null) continue;
            loadedTravellersByVillage.merge(new BlockPos(home.getX(), 0, home.getZ()), 1, Integer::sum);
        }

        List<EntityTravellerRaider> loadedRaiderList =
                ws.getEntities(EntityTravellerRaider.class, net.minecraft.entity.Entity::isEntityAlive);
        Map<BlockPos, Integer> loadedRaidersByVillage = new java.util.HashMap<>();
        for (EntityTravellerRaider r0 : loadedRaiderList) {
            BlockPos home = r0.getHomeVillage();
            if (home == null) continue;
            loadedRaidersByVillage.merge(new BlockPos(home.getX(), 0, home.getZ()), 1, Integer::sum);
        }

        if (totalVirtual + totalLoaded >= globalCap && (workerCap <= 0 || totalWorkers >= workerCap)) return;

        // Villages — uses Millenaire WorldSavedData, no chunk loading required.
        // Population may read as 0 for unloaded villages; travellerCapMinPerVillage/raiderCapMinPerVillage
        // still produce a non-zero target, so population always recovers after a reset.

        int spawnsThisCycle = 0;
        final int maxSpawnsPerCycle = Math.max(1, TravellersConfig.OFFSCREEN.globalPopulationMaxSpawnsPerCycle);

        for (MillSettlements.Settlement vi : MillSettlements.villages(ws)) {
            if (spawnsThisCycle >= maxSpawnsPerCycle) break;

            int pop = vi.population(ws);

            BlockPos villageCenter = vi.center;
            BlockPos villageKey = new BlockPos(villageCenter.getX(), 0, villageCenter.getZ());
            String cultureId = vi.cultureKey != null && !vi.cultureKey.isEmpty() ? vi.cultureKey : null;

            if (totalVirtual + totalLoaded < globalCap) {
                // Use per-villager formula; min cap ensures population recovers even when chunks are unloaded (pop=0)
                int targetTravellers = PopulationQuota.travellers(pop);
                int targetRaiders = PopulationQuota.raiders(pop);

                int virtualTravellers = mgr.countByHomeVillage(villageCenter, "TRAVELLER");
                int loadedTravellers = loadedTravellersByVillage.getOrDefault(villageKey, 0);
                int needTravellers = targetTravellers - virtualTravellers - loadedTravellers;

                int virtualRaiders = mgr.countByHomeVillage(villageCenter, "RAIDER");
                int loadedRaiders = loadedRaidersByVillage.getOrDefault(villageKey, 0);
                int needRaiders = targetRaiders - virtualRaiders - loadedRaiders;

                int spawnedTravellers = 0;
                int travellerAttempts = 0;
                int maxTravellerAttempts = attemptBudget(needTravellers);
                while (spawnedTravellers < needTravellers && spawnsThisCycle < maxSpawnsPerCycle
                        && totalVirtual + totalLoaded < globalCap && travellerAttempts < maxTravellerAttempts) {
                    travellerAttempts++;
                    // May add more than 1 record at once (group leader + followers) — count the real total,
                    // not just this call, or group spawns silently bypass every budget/cap check above.
                    // The remaining-need argument also caps the group's own size (see rollGroupSize).
                    int added = spawnVirtualTraveller(ws, mgr, villageCenter, "TRAVELLER", cultureId,
                            needTravellers - spawnedTravellers);
                    if (added <= 0) continue; // this roll failed (e.g. no valid destination) — try another roll
                    spawnedTravellers += added;
                    spawnsThisCycle += added;
                    totalVirtual += added;
                }
                int spawnedRaiders = 0;
                int raiderAttempts = 0;
                int maxRaiderAttempts = attemptBudget(needRaiders);
                while (spawnedRaiders < needRaiders && spawnsThisCycle < maxSpawnsPerCycle
                        && totalVirtual + totalLoaded < globalCap && raiderAttempts < maxRaiderAttempts) {
                    raiderAttempts++;
                    // Raiders are frequently vetoed for landing too close to a village (random per roll) —
                    // must retry with a fresh random roll rather than give up on the first veto, or raiders
                    // can go long stretches without spawning at all whenever villages sit close together.
                    int added = spawnVirtualTraveller(ws, mgr, villageCenter, "RAIDER", cultureId,
                            needRaiders - spawnedRaiders);
                    if (added <= 0) continue;
                    spawnedRaiders += added;
                    spawnsThisCycle += added;
                    totalVirtual += added;
                }

                if (TravellersConfig.OFFSCREEN.populationDebugLog) {
                    FMLLog.log(Level.INFO,
                            "[Travellers/Population] village=%s pop=%d | travellers: target=%d have=%d need=%d spawned=%d attempts=%d/%d | raiders: target=%d have=%d need=%d spawned=%d attempts=%d/%d",
                            vi.name, pop,
                            targetTravellers, virtualTravellers + loadedTravellers, needTravellers, spawnedTravellers, travellerAttempts, maxTravellerAttempts,
                            targetRaiders, virtualRaiders + loadedRaiders, needRaiders, spawnedRaiders, raiderAttempts, maxRaiderAttempts);
                }
            }

            // Workers: same population-driven target formula used by the old loaded-only spawner
            // (PopulationQuota.workers), now grown the same way as travellers/raiders.
            if ((workerCap <= 0 || totalWorkers < workerCap) && spawnsThisCycle < maxSpawnsPerCycle) {
                BlockPos workerKey = new BlockPos(villageCenter.getX(), 0, villageCenter.getZ());
                if (workerMinDist <= 0 || !withinXZOfAny(workerKey, workerAnchors, workerMinDist)) {
                    int targetWorkers = PopulationQuota.workers(pop);
                    int haveLoaded = loadedWorkersByVillage.getOrDefault(workerKey, 0);
                    int haveVirtual = mgr.countByHomeVillage(workerKey, "WORKER");
                    int haveWorkers = haveLoaded + haveVirtual;
                    if (haveWorkers > 0) workerAnchors.add(workerKey);

                    int needWorkers = targetWorkers - haveWorkers;
                    int spawnedWorkers = 0;
                    int workerAttempts = 0;
                    int maxWorkerAttempts = attemptBudget(needWorkers);
                    while (spawnedWorkers < needWorkers && spawnsThisCycle < maxSpawnsPerCycle
                            && (workerCap <= 0 || totalWorkers < workerCap) && workerAttempts < maxWorkerAttempts) {
                        workerAttempts++;
                        // May fail a roll (e.g. landed too close to a player) — retry with a fresh roll
                        // rather than give up, same reasoning as the traveller/raider loops above.
                        if (!spawnVirtualWorker(ws, mgr, workerKey, cultureId)) continue;
                        spawnedWorkers++;
                        spawnsThisCycle++;
                        totalWorkers++;
                        workerAnchors.add(workerKey);
                    }
                }
            }
        }

        // Lone buildings — use fixed per-building caps, culture from the lone building itself
        if (spawnsThisCycle < maxSpawnsPerCycle) {
            List<MillSettlements.Settlement> loneBuildings = MillSettlements.loneBuildings(ws);
            int travellerPerLone = TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerLoneBuilding;
            int raiderPerLone = TravellersConfig.SPAWN_RAIDERS.raiderCapPerLoneBuilding;
            int workerPerLone = TravellersConfig.SPAWN_WORKERS.workerCapPerLoneBuilding;

            for (MillSettlements.Settlement lone : loneBuildings) {
                if (spawnsThisCycle >= maxSpawnsPerCycle) break;

                BlockPos loneCenter = lone.center;
                BlockPos loneKey = new BlockPos(loneCenter.getX(), 0, loneCenter.getZ());
                String loneCulture = lone.cultureKey.isEmpty() ? null : lone.cultureKey;

                if (totalVirtual + totalLoaded < globalCap) {
                    int need = travellerPerLone - mgr.countByHomeVillage(loneCenter, "TRAVELLER")
                            - loadedTravellersByVillage.getOrDefault(loneKey, 0);
                    int spawnedLoneTravellers = 0;
                    int loneTravellerAttempts = 0;
                    int maxLoneTravellerAttempts = attemptBudget(need);
                    while (spawnedLoneTravellers < need && spawnsThisCycle < maxSpawnsPerCycle
                            && totalVirtual + totalLoaded < globalCap && loneTravellerAttempts < maxLoneTravellerAttempts) {
                        loneTravellerAttempts++;
                        int added = spawnVirtualTraveller(ws, mgr, loneCenter, "TRAVELLER", loneCulture,
                                need - spawnedLoneTravellers);
                        if (added <= 0) continue;
                        spawnedLoneTravellers += added;
                        spawnsThisCycle += added;
                        totalVirtual += added;
                    }

                    int needR = raiderPerLone - mgr.countByHomeVillage(loneCenter, "RAIDER")
                            - loadedRaidersByVillage.getOrDefault(loneKey, 0);
                    int spawnedLoneRaiders = 0;
                    int loneRaiderAttempts = 0;
                    int maxLoneRaiderAttempts = attemptBudget(needR);
                    while (spawnedLoneRaiders < needR && spawnsThisCycle < maxSpawnsPerCycle
                            && totalVirtual + totalLoaded < globalCap && loneRaiderAttempts < maxLoneRaiderAttempts) {
                        loneRaiderAttempts++;
                        int added = spawnVirtualTraveller(ws, mgr, loneCenter, "RAIDER", loneCulture,
                                needR - spawnedLoneRaiders);
                        if (added <= 0) continue;
                        spawnedLoneRaiders += added;
                        spawnsThisCycle += added;
                        totalVirtual += added;
                    }
                }

                if (workerPerLone > 0 && (workerCap <= 0 || totalWorkers < workerCap)) {
                    int needW = workerPerLone - mgr.countByHomeVillage(loneCenter, "WORKER");
                    int spawnedLoneWorkers = 0;
                    int loneWorkerAttempts = 0;
                    int maxLoneWorkerAttempts = attemptBudget(needW);
                    while (spawnedLoneWorkers < needW && spawnsThisCycle < maxSpawnsPerCycle
                            && (workerCap <= 0 || totalWorkers < workerCap) && loneWorkerAttempts < maxLoneWorkerAttempts) {
                        loneWorkerAttempts++;
                        if (!spawnVirtualWorker(ws, mgr, loneCenter, loneCulture)) continue;
                        spawnedLoneWorkers++;
                        spawnsThisCycle++;
                        totalWorkers++;
                    }
                }
            }
        }
    }

    /**
     * How many spawn rolls to allow for a given "need" count before giving up for this cycle.
     * A single roll can fail for reasons that are worth retrying with a fresh random roll (e.g. a raider
     * landing too close to a village) — a bounded multiple of the need gives it several fresh chances
     * without ever risking a runaway loop when a roll can never succeed (e.g. only one settlement exists).
     */
    private static int attemptBudget(int need) {
        return Math.max(4, need * 4);
    }

    /**
     * Picks a random point in a ring [minDist, maxDist] around a settlement — shared dissemination
     * logic for travellers/raiders/workers, so a freshly discovered village/lone building never shows
     * its population clumped on top of it. XZ only; Y is resolved separately (see resolveVirtualSpawnY).
     */
    private static BlockPos pickRingPoint(Random rand, BlockPos center, int minDist, int maxDist) {
        int lo = Math.max(0, minDist);
        int hi = Math.max(lo, maxDist);
        double angle = rand.nextDouble() * Math.PI * 2.0;
        int dist = hi <= lo ? lo : lo + rand.nextInt(hi - lo + 1);
        int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
        int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
        return new BlockPos(x, center.getY(), z);
    }

    /**
     * True if pos is within any online player's view distance in this dimension. Used to stop a
     * traveller/raider/worker from ever spawning right in front of a player — e.g. the moment a
     * player's own movement triggers a new village/lone building to generate, its population must
     * not immediately materialize in their face; it should appear already dispersed, out of sight,
     * exactly like the rest of the world's population does.
     */
    private static boolean isNearAnyPlayer(WorldServer ws, BlockPos pos) {
        double radius = ws.getMinecraftServer().getPlayerList().getViewDistance() * 16.0;
        double r2 = radius * radius;
        for (EntityPlayer p : ws.playerEntities) {
            double dx = p.posX - pos.getX();
            double dz = p.posZ - pos.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }


    /**
     * Creates VirtualTravellerRecords for a traveller or raider (and optional group followers).
     * The leader spawns at a random mid-route point; followers share the same route and track the leader.
     * No world entity is created.
     *
     * @return the total number of records actually created (1 leader + any group followers), or 0 if
     *         nothing was spawned — callers must add this to their spawn/population counters themselves,
     *         since a single call can add an entire group at once.
     */
    private static int spawnVirtualTraveller(WorldServer ws, VirtualTravellerManager mgr,
                                              BlockPos homeVillage, String entityClass, @Nullable String cultureId,
                                              int remainingNeed) {
        if (MillSettlements.all(ws).size() < 2) return 0;

        boolean isRaider = "RAIDER".equals(entityClass);

        // Disperse the spawn point in a ring around the HOME settlement instead of along the route to
        // the destination — a village/lone building discovered "at the frontier" must not show its
        // whole population clustered on top of it just because the rolled destination happened to be
        // close by. The entity then travels from this dispersed point onward to its destination.
        int minDist = isRaider ? TravellersConfig.SPAWN_RAIDERS.raiderSpawnMinDistanceFromHome
                : TravellersConfig.SPAWN_TRAVELLERS.travellerSpawnMinDistanceFromHome;
        int maxDist = isRaider ? TravellersConfig.SPAWN_RAIDERS.raiderSpawnMaxDistanceFromHome
                : TravellersConfig.SPAWN_TRAVELLERS.travellerSpawnMaxDistanceFromHome;
        BlockPos ringPoint = pickRingPoint(ws.rand, homeVillage, minDist, maxDist);

        // Resolve Y: use real terrain surface if chunk is loaded, otherwise keep waypoint Y or use 64
        BlockPos spawnPos = resolveVirtualSpawnY(ws, ringPoint);

        // Never land on/near ANY settlement — village or lone building, home, destination, or an
        // unrelated one the ring roll happened to land close to.
        int avoidRadius = TravellersConfig.SPAWN.spawnSettlementAvoidanceRadius;
        if (MillSettlements.isNear(ws, spawnPos, avoidRadius)) {
            if (TravellersConfig.OFFSCREEN.populationDebugLog) {
                FMLLog.log(Level.INFO,
                        "[Travellers/Population] %s spawn vetoed at %s (within %d blocks of a settlement)",
                        entityClass, spawnPos, avoidRadius);
            }
            return 0;
        }

        // Never spawn within a player's view distance — most commonly hit right when a player's own
        // movement triggers a brand new village/lone building to generate: without this, the population
        // controller could immediately place (and therefore instantly materialize) a traveller/raider
        // right in front of them instead of appearing already dispersed like the rest of the world.
        if (isNearAnyPlayer(ws, spawnPos)) {
            if (TravellersConfig.OFFSCREEN.populationDebugLog) {
                FMLLog.log(Level.INFO,
                        "[Travellers/Population] %s spawn vetoed at %s (within a player's view distance)",
                        entityClass, spawnPos);
            }
            return 0;
        }

        // Build un-spawned temp entity to populate NBT (culture, name, equipment)
        EntityTraveller tempEntity = "RAIDER".equals(entityClass)
                ? new EntityTravellerRaider(ws)
                : new EntityTraveller(ws);
        tempEntity.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        BlockPos homeKey = new BlockPos(homeVillage.getX(), 0, homeVillage.getZ());
        // Baked into entityNbt below so a materialized entity keeps its home identity — this is what
        // lets the population controller count it correctly once it has wandered away (see fix notes).
        tempEntity.setHomeVillage(homeKey);
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
        r.homeVillage = homeKey;
        r.captureIdentityFromNbt();

        BlockPos dest = mgr.pickInitialDestination(ws, r);
        if (dest == null) return 0;
        // The itinerary toward dest is built by the offscreen simulation on its first goal refresh.
        r.setTrip(java.util.Collections.singletonList(dest), 0);
        r.goal = dest;

        r.virtualizedTick = ws.getTotalWorldTime();
        // Group-atomic mount roll: decided once here and copied to every follower below, so the
        // whole group materializes uniformly mounted or on-foot regardless of when each member
        // actually materializes (see VirtualTravellerRecord.mounted).
        r.mounted = TravellerMountSupport.rollGroupMount(ws.rand, entityClass);
        // Group-atomic formation-style roll, same reasoning as the mount roll above: decided once
        // here, copied to every follower, so the group's shape doesn't depend on materialize order.
        r.groupFormationStyle = com.jubitus.travellers.entity.entityai.follow.formations.WorkerFormationStyle
                .randomGroupStyle(ws.rand).name();
        mgr.addRecord(r);

        // Roll for group followers — same culture as the leader, same route
        int groupSize = rollGroupSize(ws.rand, entityClass, remainingNeed);
        for (int i = 1; i < groupSize; i++) {
            spawnVirtualFollower(ws, mgr, r, entityClass, cultureId, spawnPos);
        }
        return groupSize;
    }

    /**
     * @param remainingNeed how many more of this type the village actually still needs. A group's size
     *                       is capped to this — a village that only needs 2 more travellers must never
     *                       get an 8-person group just because that's within the configured global max;
     *                       the overshoot from a single group spawn should never dwarf the village's own
     *                       target. Pass a large number (e.g. Integer.MAX_VALUE) to disable the cap.
     */
    private static int rollGroupSize(java.util.Random rand, String entityClass, int remainingNeed) {
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
        max = Math.min(max, Math.max(1, remainingNeed));
        if (min > max) return 1; // not enough room left for even the smallest configured group
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
        tempEntity.setHomeVillage(leader.homeVillage);
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
        r.captureIdentityFromNbt();
        r.groupLeaderUUID = leader.uuid;
        r.setTrip(leader.tripStops, leader.tripIndex);
        r.goal = leader.goal;
        r.virtualizedTick = ws.getTotalWorldTime();
        // Copy, never re-roll: the whole group shares the leader's mount roll (group-atomic).
        r.mounted = leader.mounted;
        r.groupFormationStyle = leader.groupFormationStyle;
        mgr.addRecord(r);
    }

    /**
     * Creates a VirtualTravellerRecord for a worker at its home village, with a route already
     * planned so materialization behaves exactly like a freshly spawned worker. No world entity
     * is created.
     *
     * @return true if a record was created, false if this roll failed (e.g. landed too close to a
     *         player) — callers should retry with a fresh roll rather than treat this as permanent.
     */
    private static boolean spawnVirtualWorker(WorldServer ws, VirtualTravellerManager mgr,
                                              BlockPos homeVillage, @Nullable String cultureId) {
        BlockPos homePos = resolveVirtualSpawnY(ws, homeVillage);

        EntityTravellerWorker tempEntity = new EntityTravellerWorker(ws);
        tempEntity.setHomeVillage(homeVillage);
        tempEntity.setPosition(homePos.getX() + 0.5, homePos.getY(), homePos.getZ() + 0.5);

        // Disperse the spawn point in a ring around the home village instead of dropping it right on
        // the village center — a freshly discovered village otherwise shows its whole workforce
        // standing in one clump. Same dissemination ring as travellers/raiders (see spawnVirtualTraveller).
        BlockPos ringPoint = pickRingPoint(ws.rand,
                homeVillage, TravellersConfig.SPAWN_WORKERS.workerSpawnMinDistanceFromHome,
                TravellersConfig.SPAWN_WORKERS.workerSpawnMaxDistanceFromHome);
        BlockPos spawnPos = resolveVirtualSpawnY(ws, ringPoint);

        // Never land on/near ANY settlement, and never spawn (and therefore instantly materialize) a
        // worker right in front of a player — same reasoning as spawnVirtualTraveller().
        int avoidRadius = TravellersConfig.SPAWN.spawnSettlementAvoidanceRadius;
        if (MillSettlements.isNear(ws, spawnPos, avoidRadius)) {
            if (TravellersConfig.OFFSCREEN.populationDebugLog) {
                FMLLog.log(Level.INFO,
                        "[Travellers/Population] WORKER spawn vetoed at %s (within %d blocks of a settlement)",
                        spawnPos, avoidRadius);
            }
            return false;
        }
        if (isNearAnyPlayer(ws, spawnPos)) {
            if (TravellersConfig.OFFSCREEN.populationDebugLog) {
                FMLLog.log(Level.INFO,
                        "[Travellers/Population] WORKER spawn vetoed at %s (within a player's view distance)",
                        spawnPos);
            }
            return false;
        }

        // Culture and trip tuning must be rolled before the first destination is chosen.
        net.minecraft.entity.IEntityLivingData spawnData = (cultureId != null && !cultureId.isEmpty())
                ? new EntityTraveller.SpawnData(cultureId) : null;
        tempEntity.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        TravelCommon tc = tempEntity.getTravelCommon();
        tc.updateNearestVillage();
        tc.noteChosenDestination(homeVillage);
        tc.ensureRoutePlanned();
        if (tc.route.isEmpty()) return false; // not enough destinations to travel
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        tempEntity.setPosition(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);

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
        r.captureIdentityFromNbt();
        r.workerMode = "IDLE";
        // Non-hired IDLE workers never get their goal re-derived by refreshGoal() (it only
        // tracks dynamic targets like an owner or catchup); it must be seeded here once, exactly
        // like virtualizeWorker() does via getVirtualGoal(), or advanceVirtual() has nothing to
        // walk toward and the worker sits frozen at home until it materializes.
        r.goal = tempEntity.getVirtualGoal();
        r.virtualizedTick = ws.getTotalWorldTime();
        // Non-hired workers spawn individually (no group loop here) — roll per worker. Hired-worker
        // groups are formed later by players via WorkerGroupService, not at spawn time.
        r.mounted = TravellerMountSupport.rollGroupMount(ws.rand, "WORKER");
        mgr.addRecord(r);
        return true;
    }

    /** Returns a spawn position for a virtual entity: real surface Y if chunk is loaded, waypoint Y or 64 otherwise. */
    private static BlockPos resolveVirtualSpawnY(WorldServer ws, BlockPos xzPos) {
        // Never land a virtual spawn point on/inside a Millénaire building (e.g. a worker's home
        // village center is the Town Hall itself). Pure metadata check, works without chunk loading.
        BlockPos safeXZ = com.jubitus.travellers.world.mill.MillFootprints.pushOutside(ws, xzPos);
        if (chunkExists(ws, safeXZ)) {
            BlockPos top = ws.getTopSolidOrLiquidBlock(safeXZ);
            if (top != null && top.getY() > 0) return top;
        }
        return safeXZ.getY() > 0 ? safeXZ : new BlockPos(safeXZ.getX(), 64, safeXZ.getZ());
    }

    private static boolean chunkExists(WorldServer ws, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        return ws.getChunkProvider().chunkExists(cx, cz);
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


}
