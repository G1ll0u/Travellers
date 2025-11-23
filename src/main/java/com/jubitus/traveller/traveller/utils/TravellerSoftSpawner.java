package com.jubitus.traveller.traveller.utils;

import com.jubitus.traveller.TravellersModConfig;
import com.jubitus.traveller.traveller.SpawnThrottles;
import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import com.jubitus.traveller.traveller.entityAI.EntityTravellerRaider;
import com.jubitus.traveller.traveller.utils.villages.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;
import java.util.Random;

import static com.jubitus.traveller.traveller.entityAI.EntityAITravel.intersectsForbiddenLiquid;

@Mod.EventBusSubscriber
public final class TravellerSoftSpawner {

    private static int raiderTickCounter = 0;
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onWorldTick(TickEvent.WorldTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (e.world.isRemote) return;
        WorldServer ws = (WorldServer) e.world;

        // ---------- Traveller soft top-up ----------
        if (TravellersModConfig.enableTravellerSoftSpawns) {

            if ((++tickCounter % Math.max(1, TravellersModConfig.travellerTopUpEveryTicks)) == 0) {

                // Global loaded cap across ALL worlds (fast)
                int loadedTotal = 0;
                for (WorldServer w : ws.getMinecraftServer().worlds) {
                    loadedTotal += w.getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive).size();
                }
                if (loadedTotal < TravellersModConfig.travellerTargetLoaded) {

                    // Try near each player on this world
                    for (EntityPlayerMP p : ws.getMinecraftServer().getPlayerList().getPlayers()) {
                        if (p.world != ws) continue;

                        Random r = p.getRNG();
                        boolean spawnedAnyForPlayer = false; // NEW
                        for (int attempt = 0; attempt < TravellersModConfig.travellerAttemptsPerPlayer; attempt++) {
                            BlockPos pos = pickSpawnPosAround(ws, p.getPosition(), r,
                                    TravellersModConfig.travellerSpawnMinDist,
                                    TravellersModConfig.travellerSpawnMaxDist);
                            if (pos == null) continue;

                            // Must stay in loaded area
                            if (!chunkExists(ws, pos)) continue;

                            // Village proximity (your own rule)
                            if (!MillVillageIndex.isNearVillage(ws, pos, TravellersModConfig.villagePickRadius))
                                continue;

                            // Respect your local density cap (reuse your helper)
                            if (!SpawnThrottles.underLocalCap(ws, pos, EntityTraveller.class,
                                    TravellersModConfig.travellerLocalCap, TravellersModConfig.travellerLocalCapRadius))
                                continue;

                            // Light & ground sanity (mirrors your getCanSpawnHere())
                            int blockLight = ws.getLightFor(EnumSkyBlock.BLOCK, pos);
                            if (blockLight < TravellersModConfig.travellerSpawnLightMin) continue;

                            // Vanilla-style veto at the anchor (cheap early bail)
                            float sx = (float) (pos.getX() + 0.5);
                            float sy = (float) pos.getY();
                            float sz = (float) (pos.getZ() + 0.5);
                            EntityTraveller probe = new EntityTraveller(ws);
                            if (net.minecraftforge.event.ForgeEventFactory.canEntitySpawn(probe, ws, sx, sy, sz)
                                    == net.minecraftforge.fml.common.eventhandler.Event.Result.DENY) {
                                continue;
                            }

                            // ---------- CHANGED: spawn a group ----------
                            int gMin = Math.max(1, TravellersModConfig.MinGroupSizeAtSpawn);
                            int gMax = Math.max(gMin, TravellersModConfig.MaxGroupSizeAtSpawn);
                            int group = gMin + r.nextInt(gMax - gMin + 1);

                            int spawned = 0;
                            for (int i = 0; i < group; i++) {
                                // small XY jitter around anchor; keep chunks already loaded
                                int ox = r.nextInt(5) - 2;
                                int oz = r.nextInt(5) - 2;
                                BlockPos posI = new BlockPos(pos.getX() + ox, pos.getY(), pos.getZ() + oz);

                                if (!chunkExists(ws, posI)) continue;

                                // local cap per member location
                                if (!SpawnThrottles.underLocalCap(ws, posI, EntityTraveller.class,
                                        TravellersModConfig.travellerLocalCap, TravellersModConfig.travellerLocalCapRadius))
                                    continue;

                                // light/ground sanity per member (cheap)
                                if (ws.getLightFor(EnumSkyBlock.BLOCK, posI) < TravellersModConfig.travellerSpawnLightMin) continue;

                                float mx = posI.getX() + 0.5f, my = posI.getY(), mz = posI.getZ() + 0.5f;

                                EntityTraveller t = new EntityTraveller(ws);
                                t.setLocationAndAngles(mx, my, mz, r.nextFloat() * 360f, 0f);

                                // avoid pop-in (line of sight / too close)
                                if (tooCloseOrVisibleToPlayer(ws, t, p)) {
                                    t.setDead();
                                    continue;
                                }

                                // cheap collision/liquid sanity before init (prevents noisy fails)
                                if (!ws.checkNoEntityCollision(t.getEntityBoundingBox())) { t.setDead(); continue; }
                                if (!ws.getCollisionBoxes(t, t.getEntityBoundingBox()).isEmpty()) { t.setDead(); continue; }
                                if (intersectsForbiddenLiquid(ws, t.getEntityBoundingBox())) { t.setDead(); continue; }

                                // normal initial spawn logic
                                t.onInitialSpawn(ws.getDifficultyForLocation(posI), (IEntityLivingData) null);

                                // Final safety: hard cap (global, live)
                                int nowLoaded = ws.getEntities(EntityTraveller.class, net.minecraft.entity.Entity::isEntityAlive).size();
                                if (nowLoaded >= TravellersModConfig.loadedTravellerCap) {
                                    t.setDead();
                                    break; // stop group
                                }

                                ws.spawnEntity(t);
                                spawned++;
                            }

                            if (spawned > 0) {
                                spawnedAnyForPlayer = true; // NEW
                                break; // one successful group per player-cycle (keeps perf predictable)
                            }
                            // else: try another anchor attempt around this player
                        }

                        if (spawnedAnyForPlayer) {
                            // optional: continue to next player, or keep trying this player if you want more groups
                        }
                    }
                }
            }

            // ---------- Raider soft top-up ----------
            if (TravellersModConfig.enableRaiderSoftSpawns) {
                if ((++raiderTickCounter % Math.max(1, TravellersModConfig.raiderTopUpEveryTicks)) == 0) {

                    // Count loaded raiders across ALL worlds
                    int loadedRaiders = 0;
                    for (WorldServer w : ws.getMinecraftServer().worlds) {
                        loadedRaiders += w.getEntities(EntityTravellerRaider.class, net.minecraft.entity.Entity::isEntityAlive).size();
                    }

                    if (loadedRaiders < TravellersModConfig.raiderTargetLoaded
                            && ws.getDifficulty() != EnumDifficulty.PEACEFUL) {

                        for (EntityPlayerMP p : ws.getMinecraftServer().getPlayerList().getPlayers()) {
                            if (p.world != ws) continue;
                            Random r = p.getRNG();

                            boolean spawnedAnyForPlayer = false; // NEW
                            for (int k = 0; k < TravellersModConfig.raiderAttemptsPerPlayer; k++) {
                                if (r.nextFloat() > TravellersModConfig.raiderSoftSpawnChancePerAttempt) continue;

                                BlockPos pos = pickSpawnPosAround(ws, p.getPosition(), r,
                                        TravellersModConfig.raiderSpawnMinDist,
                                        TravellersModConfig.raiderSpawnMaxDist);
                                if (pos == null) continue;
                                if (!chunkExists(ws, pos)) continue;

                                // Raiders should hang around villages too
                                if (!MillVillageIndex.isNearVillage(ws, pos, TravellersModConfig.villagePickRadius))
                                    continue;

                                // Raider local cap
                                if (!SpawnThrottles.underLocalCap(ws, pos, EntityTravellerRaider.class,
                                        TravellersModConfig.raiderLocalCap, TravellersModConfig.raiderLocalCapRadius))
                                    continue;

                                // Darkness + ground
                                int blockLight = ws.getLightFor(EnumSkyBlock.BLOCK, pos);
                                if (blockLight < TravellersModConfig.travellerSpawnLightMin) continue;
                                if (!ws.getBlockState(pos.down()).isOpaqueCube()) continue;

                                float sx = (float) (pos.getX() + 0.5);
                                float sy = (float) pos.getY();
                                float sz = (float) (pos.getZ() + 0.5);

                                // quick preflight at anchor with a probe
                                EntityTravellerRaider anchorProbe = new EntityTravellerRaider(ws);
                                anchorProbe.setLocationAndAngles(sx, sy, sz, r.nextFloat() * 360f, 0f);
                                if (!ws.checkNoEntityCollision(anchorProbe.getEntityBoundingBox())) continue;
                                if (!ws.getCollisionBoxes(anchorProbe, anchorProbe.getEntityBoundingBox()).isEmpty()) continue;
                                if (ws.containsAnyLiquid(anchorProbe.getEntityBoundingBox())) continue;

                                // ---------- CHANGED: spawn a group ----------
                                int gMin = Math.max(1, TravellersModConfig.RaiderMinGroup);
                                int gMax = Math.max(gMin, TravellersModConfig.RaiderMaxGroup);
                                int group = gMin + r.nextInt(gMax - gMin + 1);

                                int spawned = 0;
                                for (int i = 0; i < group; i++) {
                                    int ox = r.nextInt(5) - 2;
                                    int oz = r.nextInt(5) - 2;
                                    BlockPos posI = new BlockPos(pos.getX() + ox, pos.getY(), pos.getZ() + oz);
                                    if (!chunkExists(ws, posI)) continue;

                                    if (!SpawnThrottles.underLocalCap(ws, posI, EntityTravellerRaider.class,
                                            TravellersModConfig.raiderLocalCap, TravellersModConfig.raiderLocalCapRadius))
                                        continue;

                                    if (ws.getLightFor(EnumSkyBlock.BLOCK, posI) < TravellersModConfig.travellerSpawnLightMin) continue;
                                    if (!ws.getBlockState(posI.down()).isOpaqueCube()) continue;

                                    float mx = posI.getX() + 0.5f, my = posI.getY(), mz = posI.getZ() + 0.5f;

                                    EntityTravellerRaider raider = new EntityTravellerRaider(ws);
                                    raider.setLocationAndAngles(mx, my, mz, r.nextFloat() * 360f, 0f);

                                    // avoid popping right in front of the player
                                    if (tooCloseOrVisibleToPlayer(ws, raider, p)) {
                                        raider.setDead();
                                        continue;
                                    }

                                    if (!ws.checkNoEntityCollision(raider.getEntityBoundingBox())) { raider.setDead(); continue; }
                                    if (!ws.getCollisionBoxes(raider, raider.getEntityBoundingBox()).isEmpty()) { raider.setDead(); continue; }
                                    if (ws.containsAnyLiquid(raider.getEntityBoundingBox())) { raider.setDead(); continue; }

                                    raider.onInitialSpawn(ws.getDifficultyForLocation(posI), null);

                                    int nowR = ws.getEntities(EntityTravellerRaider.class, net.minecraft.entity.Entity::isEntityAlive).size();
                                    if (nowR >= TravellersModConfig.loadedRaiderCap) {
                                        raider.setDead();
                                        break; // stop group
                                    }

                                    ws.spawnEntity(raider);
                                    spawned++;
                                }

                                if (spawned > 0) {
                                    spawnedAnyForPlayer = true; // NEW
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

    // ----------------- helpers -----------------

    private static boolean chunkExists(WorldServer ws, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        return ws.getChunkProvider().chunkExists(cx, cz);
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
                    if (isStandable(ws, q)) { p = q; break; }
                }
            }
            if (isStandable(ws, p)) return p;
        }
        return null;
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

    private static boolean tooCloseOrVisibleToPlayer(WorldServer ws, EntityTraveller t, EntityPlayerMP p) {
        double d2 = t.getDistanceSq(p);
        if (d2 < 26 * 26) return true; // keep a little reveal distance
        // Optionally avoid direct LoS “pop-in”
        AxisAlignedBB bb = t.getEntityBoundingBox().grow(0.5);
        return ws.rayTraceBlocks(p.getPositionEyes(1f),
                p.getPositionEyes(1f).add(p.getLookVec().scale(Math.sqrt(d2))),
                false, true, false) == null && p.getEntityBoundingBox().intersects(bb);
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
        if (looksManMadeAround(w, feet, 2)) return false;

        return true;
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
        if (m == Material.GRASS || m == Material.GROUND || m == Material.SAND || m == Material.CLAY || m == Material.SNOW) return true;

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
                        || path.contains("pillar") || path.contains("chiseled") ||path.contains("fuck")) return false;
            }
            // For modded blocks, be conservative: treat unknown ROCK as man-made unless it looks like raw stone/sandstone/end
            if (path.contains("stone") || path.contains("andesite") || path.contains("diorite") || path.contains("granite")
                    || path.contains("end_stone") || path.contains("netherrack") || path.contains("sandstone")) return true;
            return false;
        }

        // Special vanilla naturals that aren’t covered only by materials
        if (b == net.minecraft.init.Blocks.MYCELIUM) return true;

        return false;
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
                    if (b instanceof net.minecraft.block.BlockFence || b instanceof net.minecraft.block.BlockFenceGate) return true;
                    if (b instanceof net.minecraft.block.BlockDoor || b instanceof net.minecraft.block.BlockTrapDoor) return true;
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
                                || path.contains("plaster") || path.contains("metal") || path.contains("factory")) return true;
                        if (path.contains("roof") || path.contains("wall") || path.contains("window") || path.contains("lamp")) return true;
                        if (path.contains("stairs") || path.contains("slab") || path.contains("planks")) return true;
                    }
                }
            }
        }
        return false;
    }

    private TravellerSoftSpawner() {}
}
