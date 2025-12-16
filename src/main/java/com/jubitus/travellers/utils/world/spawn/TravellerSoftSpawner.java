package com.jubitus.travellers.utils.world.spawn;

import com.jubitus.travellers.Reference;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.utils.world.mill.MillVillageIndex;
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
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.jubitus.travellers.entity.entityAI.travel.EntityAITravel.intersectsForbiddenLiquid;

@Mod.EventBusSubscriber(modid = Reference.MOD_ID)
public final class TravellerSoftSpawner {

    private static int raiderTickCounter = 0;
    private static int tickCounter = 0;
    // NEW: merchant maintenance tick
    private static int merchantTickCounter = 0;
    private static int workerTickCounter = 0;

    private TravellerSoftSpawner() {
    }

    // ----------------- helpers -----------------

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world.isRemote) return;
        WorldServer ws = (WorldServer) e.world;
// Merchant maintenance
        if ((++merchantTickCounter % Math.max(1, TravellersModConfig.merchantRecheckEveryTicks)) == 0) {
            ensureMerchantsForVillages(ws);
        }

// Worker maintenance (separate knob)
        if ((++workerTickCounter % Math.max(1, TravellersModConfig.workerRecheckEveryTicks)) == 0) {
            ensureWorkersForVillages(ws);
        }
    }
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        WorldServer ws = server.getWorld(0); // overworld only
        if (ws == null) return;

        // now your counters are truly "per server tick"
        if (TravellersModConfig.enableTravellerSoftSpawns) {
            if ((++tickCounter % Math.max(1, TravellersModConfig.travellerTopUpEveryTicks)) == 0) {
                runTravellerTopUp(ws);
            }
        }

        if (TravellersModConfig.enableRaiderSoftSpawns) {
            if ((++raiderTickCounter % Math.max(1, TravellersModConfig.raiderTopUpEveryTicks)) == 0) {
                runRaiderTopUp(ws);
            }
        }
    }

    private static void runRaiderTopUp(WorldServer ws) {
        if (TravellersModConfig.enableTravellerSoftSpawns) {

            if ((++tickCounter % Math.max(1, TravellersModConfig.travellerTopUpEveryTicks)) == 0) {

                for (EntityPlayerMP p : ws.getMinecraftServer().getPlayerList().getPlayers()) {
                    if (p.world != ws) continue;

                    BlockPos playerPos = p.getPosition();

                    int regionalCap = MillVillageIndex.computeRegionalTravellerCap(ws, playerPos,
                            TravellersModConfig.travellerRegionalRadius);
                    if (regionalCap <= 0) {
                        continue;
                    }

                    int regionalCount = countTravellersInRegion(ws, playerPos,
                            TravellersModConfig.travellerRegionalRadius);
                    if (regionalCount >= regionalCap) {
                        continue;
                    }

                    Random r = p.getRNG();
                    boolean spawnedAnyForPlayer = false;

                    for (int attempt = 0; attempt < TravellersModConfig.travellerMaxPositionChecksPerAttempt; attempt++) {

                        // ✅ use travellers distances, not raider ones
                        BlockPos pos = pickSpawnPosAround(ws, playerPos, r,
                                TravellersModConfig.travellerSpawnMinDist,
                                TravellersModConfig.travellerSpawnMaxDist);
                        if (pos == null) continue;
                        if (!chunkExists(ws, pos)) continue;

                        // World-data based density (mill + lone buildings)
                        double density = MillVillageIndex.travellerDensityAt(ws, pos);

                        // Boost density based on village population (nearest village)
                        double popMult = MillVillageIndex.populationWeightAt(ws, pos);
                        density *= popMult;

                        // If density is zero, no known center nearby -> skip
                        if (density <= 0.0) {
                            continue;
                        }

                        // Treat density as a chance gate (between 0 and ~1.0+ after boost)
                        double gate = Math.min(1.0, density);
                        if (ws.rand.nextDouble() > gate) {
                            continue; // failed density roll
                        }

                        // Still keep local per-spot cap (prevents crazy clumps)
                        if (!SpawnThrottles.underLocalCap(ws, pos, EntityTraveller.class,
                                TravellersModConfig.travellerLocalCap, TravellersModConfig.travellerLocalCapRadius))
                            continue;

                        // Vanilla-style veto at the anchor (cheap early bail)
                        float sx = (float) (pos.getX() + 0.5);
                        float sy = (float) pos.getY();
                        float sz = (float) (pos.getZ() + 0.5);
                        EntityTraveller probe = new EntityTraveller(ws);
                        probe.setLocationAndAngles(sx, sy, sz, 0f, 0f);

                        net.minecraftforge.fml.common.eventhandler.Event.Result res =
                                net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(probe, ws, sx, sy, sz);


                        // ---------- group spawn ----------
                        int gMin = Math.max(1, TravellersModConfig.MinGroupSizeAtSpawn);
                        int gMax = Math.max(gMin, TravellersModConfig.MaxGroupSizeAtSpawn);

                        double d = Math.max(0.0, Math.min(1.0, density));
                        double rawGroup = gMin + (gMax - gMin) * d;
                        int group = (int) Math.round(rawGroup * (0.8 + r.nextDouble() * 0.4));
                        group = Math.max(1, Math.min(group, gMax));

                        IEntityLivingData sharedSpawnData = null;

                        // decide culture once for the whole group, based on nearby mill
                        EntityTraveller.SpawnData cultureSpawnData = null;
                        if (TravellersModConfig.travellerCultureWeightedSpawn) {
                            String cultureId = MillVillageIndex.pickCultureForTravellerSpawn(ws, pos, ws.rand);
                            if (cultureId != null && !cultureId.isEmpty()) {
                                cultureSpawnData = new EntityTraveller.SpawnData(cultureId);
                            }
                        }

                        int spawned = 0;
                        for (int i = 0; i < group && regionalCount < regionalCap; i++) {
                            int ox = r.nextInt(5) - 2;
                            int oz = r.nextInt(5) - 2;
                            BlockPos posI = new BlockPos(pos.getX() + ox, pos.getY(), pos.getZ() + oz);

                            if (!chunkExists(ws, posI)) continue;

                            if (!SpawnThrottles.underLocalCap(ws, posI, EntityTraveller.class,
                                    TravellersModConfig.travellerLocalCap, TravellersModConfig.travellerLocalCapRadius))
                                continue;

                            float mx = posI.getX() + 0.5f, my = posI.getY(), mz = posI.getZ() + 0.5f;

                            EntityTraveller t = new EntityTraveller(ws);
                            t.setLocationAndAngles(mx, my, mz, r.nextFloat() * 360f, 0f);

                            if (tooCloseOrVisibleToPlayer(ws, t, p)) {
                                t.setDead();
                                continue;
                            }

                            if (!ws.checkNoEntityCollision(t.getEntityBoundingBox())) {
                                t.setDead();
                                continue;
                            }
                            if (!ws.getCollisionBoxes(t, t.getEntityBoundingBox()).isEmpty()) {
                                t.setDead();
                                continue;
                            }
                            if (intersectsForbiddenLiquid(ws, t.getEntityBoundingBox())) {
                                t.setDead();
                                continue;
                            }

                            // If we pre-picked a culture, feed it into onInitialSpawn
                            if (sharedSpawnData == null && cultureSpawnData != null) {
                                sharedSpawnData = cultureSpawnData;
                            }

                            sharedSpawnData = t.onInitialSpawn(ws.getDifficultyForLocation(posI), sharedSpawnData);

                            // Keep the HARD global safety cap as last resort
                            int nowLoaded = ws.getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive).size();
                            if (nowLoaded >= TravellersModConfig.loadedTravellerCap) {
                                t.setDead();
                                break;
                            }

                            boolean ok = ws.spawnEntity(t);
                            if (!ok) {
                                t.setDead();
                                continue;
                            }
                            spawned++;
                            regionalCount++;

                            regionalCount++; // IMPORTANT: keep regional usage in sync
                        }

                        if (spawned > 0) {
                            spawnedAnyForPlayer = true;
                            break; // one successful group per player per cycle
                        }
                    }

                    if (spawnedAnyForPlayer) {
                        // move to next player
                    }
                }
            }
        }
    }

    private static void runTravellerTopUp(WorldServer ws) {
        // ---------- Raider soft top-up ----------
// ---------- Raider soft top-up ----------
// ---------- Raider soft top-up ----------
        if (TravellersModConfig.enableRaiderSoftSpawns) {
            if ((++raiderTickCounter % Math.max(1, TravellersModConfig.raiderTopUpEveryTicks)) == 0) {

                // Global safety cap from config (keep as an overall guard)
                int loadedRaiders = 0;
                for (WorldServer w : ws.getMinecraftServer().worlds) {
                    loadedRaiders += w.getEntities(EntityTravellerRaider.class, net.minecraft.entity.Entity::isEntityAlive).size();
                }

                if (loadedRaiders < TravellersModConfig.raiderTargetLoaded
                        && ws.getDifficulty() != EnumDifficulty.PEACEFUL) {

                    for (EntityPlayerMP p : ws.getMinecraftServer().getPlayerList().getPlayers()) {
                        if (p.world != ws) continue;
                        Random r = p.getRNG();

                        BlockPos playerPos = p.getPosition();

                        // --- NEW: regional raider cap (same inputs as travellers) ---
                        int regionalCap = MillVillageIndex.computeRegionalRaiderCap(ws, playerPos,
                                TravellersModConfig.raiderRegionalRadius);
                        if (regionalCap <= 0) {
                            // no Millénaire “threat budget” here → no raiders around this player
                            continue;
                        }

                        int regionalCount = countRaidersInRegion(ws, playerPos,
                                TravellersModConfig.raiderRegionalRadius);
                        if (regionalCount >= regionalCap) {
                            // this area is already at or above its raider budget
                            continue;
                        }

                        boolean spawnedAnyForPlayer = false;

                        for (int k = 0; k < TravellersModConfig.raiderMaxPositionChecksPerAttempt; k++) {
                            if (r.nextFloat() > TravellersModConfig.raiderSoftSpawnChancePerAttempt) continue;

                            BlockPos pos = pickSpawnPosAround(ws, playerPos, r,
                                    TravellersModConfig.raiderSpawnMinDist,
                                    TravellersModConfig.raiderSpawnMaxDist);
                            if (pos == null) continue;
                            if (!chunkExists(ws, pos)) continue;

                            // --- NEW: never spawn *inside* village ---
                            // use a “too close” radius; tune this in config
                            if (MillVillageIndex.isNearVillage(ws, pos, TravellersModConfig.raiderMinVillageDistance)) {
                                // too close to Town Hall / inside village bubble → skip
                                continue;
                            }

                            // Raiders should still hang around Millénaire influence,
                            // so require some density
                            double density = MillVillageIndex.travellerDensityAt(ws, pos);
                            if (density <= 0.0) {
                                continue; // no influence ⇒ no raiders
                            }

                            // Base chance from config
                            double baseChance = Math.max(0.0, Math.min(1.0, TravellersModConfig.raiderBaseSpawnChance));
                            double effectiveChance = Math.min(1.0, baseChance * density);

                            if (ws.rand.nextDouble() > effectiveChance) {
                                continue; // this anchor fails, try another
                            }

                            // Raider local cap
                            if (!SpawnThrottles.underLocalCap(ws, pos, EntityTravellerRaider.class,
                                    TravellersModConfig.raiderLocalCap, TravellersModConfig.raiderLocalCapRadius))
                                continue;

                            if (!ws.getBlockState(pos.down()).isOpaqueCube()) continue;

                            float sx = (float) (pos.getX() + 0.5);
                            float sy = (float) pos.getY();
                            float sz = (float) (pos.getZ() + 0.5);

                            // quick preflight at anchor with a probe
                            EntityTravellerRaider anchorProbe = new EntityTravellerRaider(ws);
                            anchorProbe.setLocationAndAngles(sx, sy, sz, r.nextFloat() * 360f, 0f);
                            if (!ws.checkNoEntityCollision(anchorProbe.getEntityBoundingBox())) continue;
                            if (!ws.getCollisionBoxes(anchorProbe, anchorProbe.getEntityBoundingBox()).isEmpty())
                                continue;
                            if (ws.containsAnyLiquid(anchorProbe.getEntityBoundingBox())) continue;

                            // ---------- group spawn ----------
                            int gMin = Math.max(1, TravellersModConfig.RaiderMinGroup);
                            int gMax = Math.max(gMin, TravellersModConfig.RaiderMaxGroup);
                            int group = gMin + r.nextInt(gMax - gMin + 1);

                            IEntityLivingData sharedRaiderSpawnData = null;

                            int spawned = 0;
                            for (int i = 0; i < group; i++) {
                                // ALSO respect regional cap inside the group loop
                                if (regionalCount >= regionalCap) break;

                                int ox = r.nextInt(5) - 2;
                                int oz = r.nextInt(5) - 2;
                                BlockPos posI = new BlockPos(pos.getX() + ox, pos.getY(), pos.getZ() + oz);
                                if (!chunkExists(ws, posI)) continue;

                                if (!SpawnThrottles.underLocalCap(ws, posI, EntityTravellerRaider.class,
                                        TravellersModConfig.raiderLocalCap, TravellersModConfig.raiderLocalCapRadius))
                                    continue;

                                if (!ws.getBlockState(posI.down()).isOpaqueCube()) continue;

                                float mx = posI.getX() + 0.5f, my = posI.getY(), mz = posI.getZ() + 0.5f;

                                EntityTravellerRaider raider = new EntityTravellerRaider(ws);
                                raider.setLocationAndAngles(mx, my, mz, r.nextFloat() * 360f, 0f);

                                if (tooCloseOrVisibleToPlayer(ws, raider, p)) {
                                    raider.setDead();
                                    continue;
                                }

                                if (!ws.checkNoEntityCollision(raider.getEntityBoundingBox())) {
                                    raider.setDead();
                                    continue;
                                }
                                if (!ws.getCollisionBoxes(raider, raider.getEntityBoundingBox()).isEmpty()) {
                                    raider.setDead();
                                    continue;
                                }
                                if (ws.containsAnyLiquid(raider.getEntityBoundingBox())) {
                                    raider.setDead();
                                    continue;
                                }

                                sharedRaiderSpawnData = raider.onInitialSpawn(
                                        ws.getDifficultyForLocation(posI),
                                        sharedRaiderSpawnData
                                );

                                int nowR = ws.getEntities(EntityTravellerRaider.class, net.minecraft.entity.Entity::isEntityAlive).size();
                                if (nowR >= TravellersModConfig.loadedRaiderCap) {
                                    raider.setDead();
                                    break; // stop group
                                }

                                ws.spawnEntity(raider);
                                spawned++;
                                regionalCount++; // <--- IMPORTANT: track per-region budget
                            }

                            if (spawned > 0) {
                                spawnedAnyForPlayer = true;
                                break; // one successful group per player-cycle
                            }
                        }

                        if (spawnedAnyForPlayer) {
                            // move to next player
                        }
                    }
                }
            }
        }
    }

    public static int countTravellersInRegion(WorldServer ws, BlockPos center, int radiusXZ) {
        AxisAlignedBB box = new AxisAlignedBB(
                center.getX() - radiusXZ, 0, center.getZ() - radiusXZ,
                center.getX() + radiusXZ, ws.getActualHeight(), center.getZ() + radiusXZ
        );
        return ws.getEntitiesWithinAABB(EntityTraveller.class, box, net.minecraft.entity.Entity::isEntityAlive).size();
    }

    private static BlockPos pickSpawnPosAround(WorldServer ws, BlockPos center, Random r, int min, int max) {
        final int tries = 12;
        for (int i = 0; i < tries; i++) {
            double theta = r.nextDouble() * Math.PI * 2.0;
            int dist = min + r.nextInt(Math.max(1, (max - min + 1)));
            int x = center.getX() + (int) Math.round(Math.cos(theta) * dist);
            int z = center.getZ() + (int) Math.round(Math.sin(theta) * dist);

            // pick ground Y (never load chunks)
            BlockPos guess = new BlockPos(x, center.getY(), z);
            if (!chunkExists(ws, guess)) continue;

            BlockPos top = ws.getTopSolidOrLiquidBlock(guess);
            // small up/down polish to avoid leaves/low ceilings
            BlockPos p = top;
            for (int up = 0; up < 2 && p.getY() < ws.getActualHeight() - 1; up++) {
                if (isStandable(ws, p)) break;
                p = p.up();
            }
            if (!isStandable(ws, p)) {
                // quick downward probe
                BlockPos q = top.down();
                for (int down = 0; down < 6 && q.getY() > 1; down++, q = q.down()) {
                    if (isStandable(ws, q)) {
                        p = q;
                        break;
                    }
                }
            }
            if (isStandable(ws, p)) return p;
        }
        return null;
    }

    private static boolean chunkExists(WorldServer ws, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        return ws.getChunkProvider().chunkExists(cx, cz);
    }

    private static boolean tooCloseOrVisibleToPlayer(WorldServer ws, EntityTraveller t, EntityPlayerMP p) {
        double d2 = t.getDistanceSq(p);
        if (d2 < 26 * 26) return true; // keep a little reveal distance
        // Optionally avoid direct LoS “pop-in”
        AxisAlignedBB bb = t.getEntityBoundingBox().grow(0.5);
        return ws.rayTraceBlocks(p.getPositionEyes(1f),
                p.getPositionEyes(1f).add(p.getLookVec().scale(Math.sqrt(d2))),
                false, true, false) == null && p.getEntityBoundingBox().intersects(bb);
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
// --- mob farm / enclosure detection ---

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
                        || path.contains("pillar") || path.contains("chiseled") || path.contains("fuck")) return false;
            }
            // For modded blocks, be conservative: treat unknown ROCK as man-made unless it looks like raw stone/sandstone/end
            return path.contains("stone") || path.contains("andesite") || path.contains("diorite") || path.contains("granite")
                    || path.contains("end_stone") || path.contains("netherrack") || path.contains("sandstone");
        }

        // Special vanilla naturals that aren’t covered only by materials
        return b == net.minecraft.init.Blocks.MYCELIUM;
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

    /**
     * Heuristic: "mob farm–like" = many mobs inside + fence ring around.
     */
    private static boolean isMobFarmLikeArea(WorldServer w, BlockPos pos) {
        // Tunable knobs – consider putting in config if you want it tweakable
        final int radius = TravellersModConfig.mobFarmCheckRadius;
        final int minEntities = TravellersModConfig.mobFarmEntityThreshold;   // how many mobs before we call it crowded
        final int fenceRadius = TravellersModConfig.mobFarmFenceRadius;
        final int minFenceBlocks = TravellersModConfig.mobFarmFenceThreshold;

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
        if (wd == null) {
            return;
        }

        List<BlockPos> centers = MillVillageIndex.getAllVillageCenters(ws);
        if (centers.isEmpty()) {
            return;
        }

        // Count existing merchants per home village (only LOADED entities)
        Map<BlockPos, Integer> merchantsByVillage = new java.util.HashMap<>();
        List<EntityTravellerMerchant> merchants =
                ws.getEntities(EntityTravellerMerchant.class, net.minecraft.entity.Entity::isEntityAlive);

        for (EntityTravellerMerchant m : merchants) {
            BlockPos home = m.getHomeVillage();
            if (home == null) continue;
            merchantsByVillage.merge(home.toImmutable(), 1, Integer::sum);
        }

        // Global loaded merchant cap (per world)
        int globalLoadedMerchants = merchants.size();
        final int globalCap = TravellersModConfig.merchantLoadedCap;

        // --- NEW: "anchor" villages that are allowed to have merchants ---
        // Start with all villages that ALREADY have at least 1 merchant
        java.util.Set<BlockPos> merchantAnchors = new java.util.HashSet<>();
        int minDist = TravellersModConfig.merchantVillageMinDistance;
        if (minDist < 0) minDist = 0;

        for (Map.Entry<BlockPos, Integer> e : merchantsByVillage.entrySet()) {
            if (e.getValue() > 0) {
                merchantAnchors.add(e.getKey());
            }
        }

        // Optional: sort centers by population descending,
        // so big villages “win” as anchors when close together.
        centers.sort((a, b) -> {
            int pa = MillVillageIndex.countVillagersInVillage(ws, a);
            int pb = MillVillageIndex.countVillagersInVillage(ws, b);
            return Integer.compare(pb, pa); // descending
        });

        for (BlockPos center : centers) {
            BlockPos immutableCenter = center.toImmutable();

            // Skip ghost villages with no TH
            Building th = MillVillageIndex.getTownHallBuilding(wd, immutableCenter);
            if (th == null) {
                continue;
            }

            // --- NEW: if this village is "too close" to an anchor, skip it entirely ---
            if (minDist > 0 && withinXZOfAny(immutableCenter, merchantAnchors, minDist)) {
                continue; // another village nearby already has / will have merchants
            }

            int villagers = MillVillageIndex.countVillagersInVillage(ws, immutableCenter);

            int desired = MillVillageIndex.desiredMerchantsForPopulation(villagers);
            if (desired <= 0) {
                continue;
            }

            int current = merchantsByVillage.getOrDefault(immutableCenter, 0);
            int missing = desired - current;
            if (missing <= 0) {
                // This center still acts as an "anchor" for neighbors if it already has merchants.
                if (current > 0) {
                    merchantAnchors.add(immutableCenter);
                }
                continue;
            }

            // We are going to actually assign merchants to this village → mark as anchor
            merchantAnchors.add(immutableCenter);

            for (int i = 0; i < missing; i++) {
                // Honor global merchant cap
                if (globalCap > 0 && globalLoadedMerchants >= globalCap) {
                    return; // stop spawning anywhere once cap is hit
                }

                spawnMerchantAtVillage(ws, immutableCenter);
                globalLoadedMerchants++;
            }
        }
    }





    /**
     * Spawns a traveller_merchant at (or near) the given village center,
     * sets its home village, and puts it into the world.
     */
    public static void spawnMerchantAtVillage(WorldServer ws, BlockPos villageCenter) {

        ensureVillageChunksLoaded(ws, villageCenter, 1);

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

        // Pre-seed travel route BEFORE spawning
        TravelCommon tc = m.getTravelCommon();
        tc.updateNearestVillage();
        tc.ensureRoutePlanned();
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        IEntityLivingData spawnData =
                createCultureSpawnDataForPos(ws, spawnPos, ws.rand);
        m.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        ws.spawnEntity(m);

    }




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
        if (!head.getMaterial().isReplaceable()) return false;

        return true;
    }
    @Nullable
    private static IEntityLivingData createCultureSpawnDataForPos(WorldServer ws, BlockPos pos, Random rand) {
        if (!TravellersModConfig.travellerCultureWeightedSpawn) return null;

        String cultureId = MillVillageIndex.pickCultureForTravellerSpawn(ws, pos, rand);
        if (cultureId == null || cultureId.isEmpty()) return null;

        // Same spawn data class you already use for normal travellers
        return new EntityTraveller.SpawnData(cultureId);
    }
    private static void ensureVillageChunksLoaded(WorldServer ws, BlockPos center, int radiusChunks) {
        int cx = center.getX() >> 4;
        int cz = center.getZ() >> 4;

        // WorldServer#getChunkProvider() returns IChunkProvider, cast to ChunkProviderServer
        ChunkProviderServer cps = (ChunkProviderServer) ws.getChunkProvider();

        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                // This will load or generate the chunk if needed
                Chunk c = cps.provideChunk(cx + dx, cz + dz);
                // you can ignore 'c', just forcing load is enough
            }
        }
    }
    /**
     * Ensure each (Millénaire) village has a *population-based* number of travellers workers.
     * Uses the same kind of formula as merchants, but with worker config knobs.
     */
    private static void ensureWorkersForVillages(WorldServer ws) {
        MillWorldData wd = MillVillageIndex.getWD(ws);
        if (wd == null) {
            return;
        }

        List<BlockPos> centers = MillVillageIndex.getAllVillageCenters(ws);
        if (centers.isEmpty()) {
            return;
        }

        // Count existing workers per home village (only LOADED entities)
        Map<BlockPos, Integer> workersByVillage = new java.util.HashMap<>();
        List<EntityTravellerWorker> workers =
                ws.getEntities(EntityTravellerWorker.class, net.minecraft.entity.Entity::isEntityAlive);

        for (EntityTravellerWorker w : workers) {
            BlockPos home = w.getHomeVillage();
            if (home == null) continue;
            workersByVillage.merge(home.toImmutable(), 1, Integer::sum);
        }

        // Global loaded worker cap (per world)
        int globalLoadedWorkers = workers.size();
        final int globalCap = TravellersModConfig.workerLoadedCap;

        // NEW: "anchor" villages that are allowed to have workers
        java.util.Set<BlockPos> workerAnchors = new java.util.HashSet<>();
        int minDist = TravellersModConfig.workerVillageMinDistance;
        if (minDist < 0) minDist = 0;

        for (Map.Entry<BlockPos, Integer> e : workersByVillage.entrySet()) {
            if (e.getValue() > 0) {
                workerAnchors.add(e.getKey());
            }
        }

        // Optional: prefer big villages as anchors
        centers.sort((a, b) -> {
            int pa = MillVillageIndex.countVillagersInVillage(ws, a);
            int pb = MillVillageIndex.countVillagersInVillage(ws, b);
            return Integer.compare(pb, pa);
        });

        for (BlockPos center : centers) {
            BlockPos immutableCenter = center.toImmutable();

            // Skip ghost villages with no TH
            Building th = MillVillageIndex.getTownHallBuilding(wd, immutableCenter);
            if (th == null) {
                continue;
            }

            // NEW: if this village is close to an anchor village, skip workers here
            if (minDist > 0 && withinXZOfAny(immutableCenter, workerAnchors, minDist)) {
                continue;
            }

            int villagers = MillVillageIndex.countVillagersInVillage(ws, immutableCenter);

            // Population-based desired workers
            int desired = MillVillageIndex.desiredWorkersForPopulation(villagers);
            if (desired <= 0) {
                continue;
            }

            int current = workersByVillage.getOrDefault(immutableCenter, 0);
            int missing = desired - current;
            if (missing <= 0) {
                if (current > 0) {
                    workerAnchors.add(immutableCenter);
                }
                continue;
            }

            // We are actually going to give workers to this village: mark as anchor
            workerAnchors.add(immutableCenter);

            for (int i = 0; i < missing; i++) {
                if (globalCap > 0 && globalLoadedWorkers >= globalCap) {
                    return; // stop once global worker cap hit
                }

                spawnWorkerAtVillage(ws, immutableCenter);
                globalLoadedWorkers++;
            }
        }
    }



    /**
     * Spawns a traveller_worker at (or near) the given village center,
     * links it to that village, and gives it an initial travel route.
     */
    public static void spawnWorkerAtVillage(WorldServer ws, BlockPos villageCenter) {

        ensureVillageChunksLoaded(ws, villageCenter, 1);

        BlockPos spawnPos = findGroundSpawnNear(ws, villageCenter, 6);
        if (spawnPos == null) {
            spawnPos = findGroundSpawnNear(ws, villageCenter, 12);
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

        // Pre-seed travel route BEFORE spawning, like merchant
        TravelCommon tc = w.getTravelCommon();
        tc.updateNearestVillage();
        tc.ensureRoutePlanned();
        tc.setReadyToDepart(true);
        tc.setDepartCooldownTicks(0);

        IEntityLivingData spawnData =
                createCultureSpawnDataForPos(ws, spawnPos, ws.rand);
        w.onInitialSpawn(ws.getDifficultyForLocation(spawnPos), spawnData);

        ws.spawnEntity(w);

        // NEW: register this worker for that village
        TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);
        reg.setWorkerFor(villageCenter, w.getUniqueID());
    }

    public static void respawnWorkerAtHome(WorldServer ws, BlockPos villageCenter) {
        if (villageCenter == null) return;

        ensureVillageChunksLoaded(ws, villageCenter, 0);

        BlockPos spawnPos = findGroundSpawnNear(ws, villageCenter, 6);
        if (spawnPos == null) {
            spawnPos = findGroundSpawnNear(ws, villageCenter, 12);
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

        ws.spawnEntity(w);

        // NEW: re-register worker
        TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);
        reg.setWorkerFor(villageCenter, w.getUniqueID());
    }


// TravellerSoftSpawner.java

    public static void respawnMerchantAtHome(WorldServer ws, BlockPos villageCenter) {
        if (villageCenter == null) return;

        // 1) force-load chunks around the village
        ensureVillageChunksLoaded(ws, villageCenter, 0);

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

        ws.spawnEntity(m);

        TravellerVillageRegistry reg = TravellerVillageRegistry.get(ws);
        reg.addMerchantFor(villageCenter, m.getUniqueID());
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

        TravellerSoftSpawner.respawnMerchantAtHome((WorldServer) m.world, home);
    }



}
