package com.jubitus.traveller.traveller.utils;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.SpawnThrottles;
import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.entityAI.EntityTravellerRaider;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import com.jubitus.traveller.traveller.utils.villages.MillenaireVillageDirectory;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.util.List;
import java.util.Random;

import static com.jubitus.traveller.traveller.entityAI.EntityAITravel.intersectsForbiddenLiquid;

@Mod.EventBusSubscriber
public final class TravellerSoftSpawner {
    private static int lastVillageReloadTick = -1;
    private static final int REGIONAL_RADIUS = 1000; // or pull from config later
    private static int raiderTickCounter = 0;
    private static int tickCounter = 0;
    // radius around a Town Hall that we consider "inside" the village
    // (tweak to taste or move to config)
    private static final int INNER_VILLAGE_NO_SPAWN_RADIUS = 128;
    private TravellerSoftSpawner() {
    }

    // ----------------- helpers -----------------

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world.isRemote) return;
        WorldServer ws = (WorldServer) e.world;

        // ---------- Traveller soft top-up ----------
        // ---------- Traveller soft top-up ----------
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

                        // ✅ use traveller distances, not raider ones
                        BlockPos pos = pickSpawnPosAround(ws, playerPos, r,
                                TravellersModConfig.travellerSpawnMinDist,
                                TravellersModConfig.travellerSpawnMaxDist);
                        if (pos == null) continue;
                        if (!chunkExists(ws, pos)) continue;

                        // World-data based density (villages + lone buildings)
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
                        if (net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(probe, ws, sx, sy, sz)
                                == net.minecraftforge.fml.common.eventhandler.Event.Result.DENY) {
                            continue;
                        }

                        // ---------- group spawn ----------
                        int gMin = Math.max(1, TravellersModConfig.MinGroupSizeAtSpawn);
                        int gMax = Math.max(gMin, TravellersModConfig.MaxGroupSizeAtSpawn);

                        double d = Math.max(0.0, Math.min(1.0, density));
                        double rawGroup = gMin + (gMax - gMin) * d;
                        int group = (int) Math.round(rawGroup * (0.8 + r.nextDouble() * 0.4));
                        group = Math.max(1, Math.min(group, gMax));

                        IEntityLivingData sharedSpawnData = null;

                        // decide culture once for the whole group, based on nearby villages
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

                            ws.spawnEntity(t);
                            spawned++;
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
// --- mob farm / enclosure detection ---

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
     * Heuristic: "mob farm–like" = many mobs inside + fence ring around.
     */
    private static boolean isMobFarmLikeArea(WorldServer w, BlockPos pos) {
        // Tunable knobs – consider putting in config if you want it tweakable
        final int radius = TravellersModConfig.mobFarmCheckRadius;
        final int minEntities = TravellersModConfig.mobFarmEntityThreshold;   // how many mobs before we call it crowded
        final int fenceRadius = TravellersModConfig.mobFarmFenceRadius;
        final int minFenceBlocks = TravellersModConfig.mobFarmFenceThreshold;

        if (!isCrowdedArea(w, pos, radius, minEntities)) return false;
        if (!hasFencePerimeter(w, pos, fenceRadius, minFenceBlocks)) return false;
        return true;
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


}
