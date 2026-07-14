package com.jubitus.travellers.utils.world.pathing.chunkmanager;

import com.jubitus.travellers.Travellers;
import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerMerchant;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.entity.travellers.others.EntityTravellerBoat;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import net.minecraft.block.Block;
import net.minecraft.block.state.BlockFaceShape;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.util.*;


public class TravellerChunkManager implements ForgeChunkManager.LoadingCallback {
    public static final TravellerChunkManager INSTANCE = new TravellerChunkManager();
    private static final int HARD_STUCK_TICKS = 20 * 60 * 5; // 5 minutes
    private static final String NBT_FORCED_CHUNKS = "ForcedChunks";
    // Dimension -> state
    private final Map<Integer, WorldState> worldStates = new HashMap<>();
    private static final int OFFSCREEN_HOP_FORCE_TTL_TICKS = 20 * 8; // 8 seconds

    // Non-hired workers hop 10x less frequently (every ~60s instead of ~6s)
    private static final int NONHIRED_HOP_INTERVAL_MULT = 10;

    private TravellerChunkManager() {
    }

    public static void registerCallback() {
        ForgeChunkManager.setForcedChunkLoadingCallback(Travellers.INSTANCE, INSTANCE);
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        if (!(event.world instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.world;
        WorldState state = getOrCreateState(ws);
        long now = ws.getTotalWorldTime();

        // Virtual workers: simulate movement and handle materialization/virtualization
        VirtualTravellerManager.get(ws).tick(ws, now);

        // Campsite discovery probes + camp lifecycle (budgeted, no chunk force-loading)
        com.jubitus.travellers.utils.world.camp.CampsiteManager.get(ws).tick(ws, now);

        // Virtual battles: encounter detection, offscreen combat sim, deferred loot
        com.jubitus.travellers.utils.world.battle.VirtualBattleManager.get(ws).tick(ws, now);

        // Morning tick: process barracks respawns and reconcile deleted barracks for online players
        if (TravellersModConfig.workerRespawnInBarracks) {
            long currentDay = ws.getWorldTime() / 24000L;
            long dayTime = ws.getWorldTime() % 24000L;
            if (dayTime >= TravellersModConfig.workerRespawnMorningTime && currentDay > state.lastRespawnProcessDay) {
                state.lastRespawnProcessDay = currentDay;
                com.jubitus.travellers.utils.world.mill.BarracksSlotManager bsm =
                        com.jubitus.travellers.utils.world.mill.BarracksSlotManager.get(ws);
                bsm.processRespawns(ws);
                // Lone barrack bed respawns
                com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager.get(ws).processRespawns(ws);
                // Reconcile deleted barracks for all online players
                int dim = ws.provider.getDimension();
                for (net.minecraft.entity.player.EntityPlayer player : ws.playerEntities) {
                    java.util.List<org.millenaire.common.village.Building> live =
                            com.jubitus.travellers.utils.world.mill.MillControlledVillageHelper
                                    .getBarracksInControlledVillages(ws, player);
                    bsm.reconcileForPlayer(player.getUniqueID(), dim, live);
                }
            }
        }

        // cheap/transient maintenance
        tickTransientHopForcedChunks(state);
        tickOffscreenHops(state);
        batchLoadRestoredChunks(state);

        // expensive: recompute less often
        int interval = Math.max(1, TravellersModConfig.chunkRecomputeIntervalTicks);
        if ((ws.getTotalWorldTime() % interval) == 0) {
            recomputeForWorld(state);
        }
        boolean dirty = false;
        for (MerchantData md : state.merchants.values()) {
            if (md.movedChunk) {
                dirty = true;
                md.movedChunk = false;
            }
        }

        if (dirty && (ws.getTotalWorldTime() % 10L) == 0L) {
            recomputeForWorld(state);
        }

    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.getWorld();
        int dim = ws.provider.getDimension();

        WorldState state = worldStates.get(dim);
        if (state != null) {
            saveForcedToTicket(state); // <- persist latest list
        }

        worldStates.remove(dim);
    }

    private void saveForcedToTicket(WorldState state) {
        if (state.ticket == null) return;

        NBTTagCompound tag = state.ticket.getModData();
        NBTTagList list = new NBTTagList();

        // IMPORTANT:
        // Persist ONLY durable forced chunks, never transient hop-preload chunks.
        for (ChunkPos cp : state.forced) {
            NBTTagCompound c = new NBTTagCompound();
            c.setInteger("x", cp.x);
            c.setInteger("z", cp.z);
            list.appendTag(c);
        }

        tag.setTag(NBT_FORCED_CHUNKS, list);
    }

    // Called by Forge on world load to restore tickets
    @Override
    public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
        if (world.isRemote) return;
        WorldServer ws = (WorldServer) world;
        WorldState state = getOrCreateState(ws);

        System.out.println("[Travellers] ticketsLoaded: dim=" + ws.provider.getDimension()
                + " tickets=" + tickets.size());

        // 1) Read any saved chunk list from ANY ticket (NORMAL or ENTITY)
        Set<ChunkPos> restored = new HashSet<>();

        for (ForgeChunkManager.Ticket t : tickets) {
            try {
                NBTTagCompound tag = t.getModData();
                NBTTagList list = tag.getTagList(NBT_FORCED_CHUNKS, 10); // 10 = compound

                for (int i = 0; i < list.tagCount(); i++) {
                    NBTTagCompound c = list.getCompoundTagAt(i);
                    restored.add(new ChunkPos(c.getInteger("x"), c.getInteger("z")));
                }
            } catch (Throwable ignored) {
                // ignore bad/empty tickets
            }
        }

        // 2) Keep ONLY a NORMAL ticket (release entity tickets + extras)
        ForgeChunkManager.Ticket normalTicket = null;

        for (ForgeChunkManager.Ticket t : tickets) {
            if (t.getType() == ForgeChunkManager.Type.NORMAL && normalTicket == null) {
                normalTicket = t;
            } else {
                // release entity tickets and extra normal tickets
                ForgeChunkManager.releaseTicket(t);
            }
        }

        state.ticket = normalTicket;

        // 3) If we didn't have a NORMAL ticket, request one now
        if (state.ticket == null) {
            state.ticket = ForgeChunkManager.requestTicket(
                    Travellers.INSTANCE,
                    ws,
                    ForgeChunkManager.Type.NORMAL
            );

            if (state.ticket == null) {
                System.out.println("[Travellers] FAILED to get NORMAL ticket in dim " +
                        ws.provider.getDimension());
                return;
            }

            System.out.println("[Travellers] Created NORMAL ticket for dim " + ws.provider.getDimension());
            state.ticket.setChunkListDepth(TravellersModConfig.merchantMaxForcedChunks);
        } else {
            // Ensure depth is correct even on restored tickets
            state.ticket.setChunkListDepth(TravellersModConfig.merchantMaxForcedChunks);
        }

        // 4) Apply restored chunk forcing using the NORMAL ticket (safe)
        state.forced.clear();
        state.forced.addAll(restored);
        state.hopForced.clear();
        state.hopForcedUntil.clear();


        System.out.println("[Travellers] Restoring " + restored.size() + " forced chunks");

        ChunkProviderServer cps = ws.getChunkProvider();

        for (ChunkPos pos : restored) {
            ForgeChunkManager.forceChunk(state.ticket, pos);
        }

// Grace period so recompute doesn't instantly unforce restored chunks
        if (!restored.isEmpty()) {
            long now = ws.getTotalWorldTime();
            state.restoredForcedFromDisk = true;
            state.restoreGraceUntilTick = now + (20L * 10L); // 10 seconds, tune
        }

// Load them gradually to avoid lag spikes
        state.pendingRestoreLoad.clear();
        state.pendingRestoreLoad.addAll(restored);


        // 5) Save back into the ticket NBT so it persists cleanly
        saveForcedToTicket(state);
    }

    private WorldState getOrCreateState(WorldServer ws) {
        return worldStates.computeIfAbsent(ws.provider.getDimension(), dim -> new WorldState(ws));
    }

    // Called each tick by any travellers that should chunkload (merchant, worker, etc.)
    public void onChunkloaderTick(EntityTraveller traveller, int radius) {
        if (traveller.world.isRemote) return;
        if (!TravellersModConfig.merchantChunkloadingEnabled) return;
        if (!isChunkloader(traveller)) return; // ✅ only workers/merchants participate

        WorldServer ws = (WorldServer) traveller.world;
        WorldState state = getOrCreateState(ws);


        MerchantData data = state.merchants.get(traveller.getUniqueID());
        if (data == null) {
            data = new MerchantData(traveller, radius);
            state.merchants.put(traveller.getUniqueID(), data);
        } else {
            data.radius = radius;
            data.updateFromEntity(traveller);
        }
    }

    // ----------------------------------------------------------------
    // Per-merchant API
    // ----------------------------------------------------------------

    private static boolean isChunkloader(EntityTraveller t) {
        return (t instanceof EntityTravellerWorker) || (t instanceof EntityTravellerMerchant);
    }

    private static boolean isCatchupWorker(EntityTraveller t) {
        return t instanceof EntityTravellerWorker && ((EntityTravellerWorker) t).isCatchupTravel();
    }

    private void tickOffscreenHops(WorldState state) {
        if (!TravellersModConfig.travellerOffscreenHopEnabled) return;

        int hiredInterval = TravellersModConfig.travellerOffscreenHopIntervalTicks;
        if (hiredInterval <= 0) return;
        int nonHiredInterval = hiredInterval * NONHIRED_HOP_INTERVAL_MULT;

        long now = state.world.getTotalWorldTime();
        boolean anyHiredHop = false;
        ChunkProviderServer cps = state.world.getChunkProvider();

        for (MerchantData md : state.merchants.values()) {
            // 1) Check timing FIRST — cheapest gate, avoids loading chunks every tick
            int effectiveInterval = md.hiredFollower ? hiredInterval : nonHiredInterval;
            if (now - md.lastOffscreenHopTick < effectiveInterval) continue;

            // 2) Try to resolve entity (may need chunk load)
            Entity ent = state.world.getEntityFromUuid(md.id);
            if (!(ent instanceof EntityTraveller)) {
                if (md.hiredFollower) {
                    // Hired: load their chunk (it should be forced)
                    ChunkPos cp = new ChunkPos(md.lastChunkX, md.lastChunkZ);
                    if (!state.forced.contains(cp) && !state.hopForced.contains(cp)) continue;
                }
                // Load chunk to recover entity (hired: forced chunk; non-hired: temporary load)
                cps.provideChunk(md.lastChunkX, md.lastChunkZ);
                ent = state.world.getEntityFromUuid(md.id);
                if (!(ent instanceof EntityTraveller)) continue;
            }

            EntityTraveller t = (EntityTraveller) ent;

            // Workers are managed by VirtualTravellerManager — skip offscreen hops for them
            if (t instanceof EntityTravellerWorker) continue;

            // 3) Skip if player is ACTUALLY watching (normal AI handles movement).
            //    For hired workers in catchup, use a tighter check: only skip when
            //    truly tracked by a player. The approximate view-distance check has a
            //    1-chunk margin that creates a dead zone where the pathfinder can't work
            //    (chunks not loaded) but offscreen hops are blocked.
            boolean actuallyTracked = t.getTrackingPlayerCount() > 0;
            if (md.hiredFollower && isCatchupWorker(t)) {
                // Only skip if a player is truly sending tracking data
                if (actuallyTracked) continue;
            } else {
                if (actuallyTracked || t.isInPlayerViewDistanceApprox()) continue;
            }

            // 4) Dismount boat (offscreen hopping doesn't use boats)
            if (t.isRiding()) {
                Entity r = t.getRidingEntity();
                t.dismountRidingEntity();
                if (r instanceof EntityTravellerBoat) {
                    ((EntityTravellerBoat) r).killNow();
                } else if (r instanceof EntityBoat) {
                    r.setDead();
                }
            }

            // 5) Skip if busy
            if (t.isInCombat() || t.isAutoEating() || t.isHandActive()) continue;

            // 6) Get goal
            BlockPos goal = getChunkloadGoal(t);
            if (goal == null) continue;

            // 7) Perform hop (different strategies for hired vs non-hired)
            boolean ok;
            int step = TravellersModConfig.travellerOffscreenHopStepBlocks;

            if (md.hiredFollower) {
                // Hired workers: force transient chunks, always use normal step size.
                // Workers must travel naturally — no instant teleports or oversized hops.
                ok = tryOffscreenHopToward(state, t, goal, step);
                if (!ok) {
                    int fallback = Math.min(16, Math.max(1, step / 2));
                    if (fallback != step) ok = tryOffscreenHopToward(state, t, goal, fallback);
                }
            } else {
                // Non-hired workers: blind hop WITHOUT chunk forcing
                ok = tryBlindOffscreenHop(state, t, goal, step);
            }

            if (ok) {
                md.lastOffscreenHopTick = now;
                md.updateFromEntity(t);
                if (md.hiredFollower) anyHiredHop = true;
            }
        }

        if (anyHiredHop) {
            recomputeForWorld(state);
        }
    }

    private boolean tryOffscreenHopToward(WorldState state, EntityTraveller t, BlockPos goal, int stepBlocks) {
        if (goal == null || stepBlocks <= 0) return false;

        BlockPos from = t.getPosition();

        int dx = goal.getX() - from.getX();
        int dz = goal.getZ() - from.getZ();

        double dist = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
        if (dist < 1.0D) return false;

        int hop = Math.min(stepBlocks, Math.max(1, (int) Math.floor(dist)));

        double nx = dx / dist;
        double nz = dz / dist;

        int tx = from.getX() + (int) Math.round(nx * hop);
        int tz = from.getZ() + (int) Math.round(nz * hop);

        ChunkPos landingChunk = new ChunkPos(tx >> 4, tz >> 4);

        forceTransientHopChunk(state, landingChunk);
        ensureLandingAreaLoaded(state.world, landingChunk);

        Vec3d landing = findPermissiveOffscreenLanding(state.world, tx, tz, from.getY());
        if (landing == null) return false;

        t.setPositionAndUpdate(landing.x, landing.y, landing.z);
        t.motionX = 0.0D;
        t.motionY = 0.0D;
        t.motionZ = 0.0D;
        t.velocityChanged = true;

        return true;
    }


    /**
     * Hop a non-hired worker without forcing any chunks.
     * The landing chunk is loaded temporarily via provideChunk and will unload
     * naturally when no player is nearby, keeping memory usage bounded.
     */
    private boolean tryBlindOffscreenHop(WorldState state, EntityTraveller t, BlockPos goal, int stepBlocks) {
        if (goal == null || stepBlocks <= 0) return false;

        BlockPos from = t.getPosition();
        int dx = goal.getX() - from.getX();
        int dz = goal.getZ() - from.getZ();
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist < 1.0D) return false;

        int hop = Math.min(stepBlocks, Math.max(1, (int) Math.floor(dist)));
        double nx = dx / dist;
        double nz = dz / dist;
        int tx = from.getX() + (int) Math.round(nx * hop);
        int tz = from.getZ() + (int) Math.round(nz * hop);

        // Temporarily load landing chunk (NOT forced — will unload naturally)
        ChunkPos landingChunk = new ChunkPos(tx >> 4, tz >> 4);
        state.world.getChunkProvider().provideChunk(landingChunk.x, landingChunk.z);

        Vec3d landing = findPermissiveOffscreenLanding(state.world, tx, tz, from.getY());
        if (landing == null) {
            // Fallback: use terrain top at landing position
            BlockPos top = state.world.getTopSolidOrLiquidBlock(new BlockPos(tx, 0, tz));
            landing = new Vec3d(tx + 0.5D, top.getY(), tz + 0.5D);
        }

        t.setPositionAndUpdate(landing.x, landing.y, landing.z);
        t.motionX = 0.0D;
        t.motionY = 0.0D;
        t.motionZ = 0.0D;
        t.velocityChanged = true;
        return true;
    }

    private void ensureLandingAreaLoaded(WorldServer ws, ChunkPos center) {
        ws.getChunkProvider().provideChunk(center.x, center.z);
    }

    private boolean forceOffscreenHopToward(WorldServer ws, EntityTraveller t, BlockPos goal, int stepBlocks) {
        if (goal == null || stepBlocks <= 0) return false;

        BlockPos from = t.getPosition();

        int dx = goal.getX() - from.getX();
        int dz = goal.getZ() - from.getZ();

        double dist = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
        if (dist < 1.0D) return false;

        int hop = Math.min(stepBlocks, Math.max(1, (int) Math.floor(dist)));

        double nx = dx / dist;
        double nz = dz / dist;

        int tx = from.getX() + (int) Math.round(nx * hop);
        int tz = from.getZ() + (int) Math.round(nz * hop);

        ChunkPos landingChunk = new ChunkPos(tx >> 4, tz >> 4);
        ensureLandingAreaLoaded(ws, landingChunk);

        Vec3d landing = findPermissiveOffscreenLanding(ws, tx, tz, from.getY());
        if (landing == null) return false;

        t.setPositionAndUpdate(landing.x, landing.y, landing.z);
        t.motionX = 0.0D;
        t.motionY = 0.0D;
        t.motionZ = 0.0D;
        t.velocityChanged = true;

        return true;
    }

    @Nullable
    private Vec3d findPermissiveOffscreenLanding(WorldServer ws, int x, int z, int fallbackY) {
        BlockPos top = ws.getTopSolidOrLiquidBlock(new BlockPos(x, 0, z));
        BlockPos water = findTopWaterAt(ws, x, z, top.getY());

        if (water != null) {
            return new Vec3d(x + 0.5D, water.getY() + 0.02D, z + 0.5D);
        }

        BlockPos stand = findStandableFeetAt(ws, x, z, top.getY());
        if (stand != null) {
            return new Vec3d(x + 0.5D, stand.getY(), z + 0.5D);
        }

        int y = Math.max(2, Math.max(top.getY(), fallbackY));
        return new Vec3d(x + 0.5D, y, z + 0.5D);
    }

    @Nullable
    private BlockPos findTopWaterAt(WorldServer ws, int x, int z, int startY) {
        int maxY = ws.getActualHeight() - 1;
        int y = Math.max(1, Math.min(maxY, startY + 4));

        while (y > 1) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState s = ws.getBlockState(p);

            if (isVanillaWater(s)) {
                while (y < maxY && isVanillaWater(ws.getBlockState(new BlockPos(x, y + 1, z)))) {
                    y++;
                }
                return new BlockPos(x, y, z);
            }

            y--;
        }

        return null;
    }

    @Nullable
    private BlockPos findStandableFeetAt(WorldServer ws, int x, int z, int startY) {
        int maxY = ws.getActualHeight() - 2;
        int minY = 2;
        int y0 = Math.max(minY, Math.min(maxY, startY));

        for (int dy = 0; dy <= 8; dy++) {
            int up = y0 + dy;
            if (up <= maxY) {
                BlockPos feet = new BlockPos(x, up, z);
                if (isStandableFeet(ws, feet)) return feet;
            }

            if (dy > 0) {
                int down = y0 - dy;
                if (down >= minY) {
                    BlockPos feet = new BlockPos(x, down, z);
                    if (isStandableFeet(ws, feet)) return feet;
                }
            }
        }

        return null;
    }

    private boolean isStandableFeet(WorldServer ws, BlockPos feet) {
        if (!ws.isBlockLoaded(feet) || !ws.isBlockLoaded(feet.up()) || !ws.isBlockLoaded(feet.down())) {
            return false;
        }

        IBlockState atFeet = ws.getBlockState(feet);
        IBlockState atHead = ws.getBlockState(feet.up());
        IBlockState below = ws.getBlockState(feet.down());

        if (atFeet.getMaterial().isLiquid() || atHead.getMaterial().isLiquid()) {
            return false;
        }

        boolean feetFree = atFeet.getMaterial().isReplaceable()
                || atFeet.getCollisionBoundingBox(ws, feet) == null
                || atFeet.getBlock() == Blocks.SNOW_LAYER;

        boolean headFree = atHead.getMaterial().isReplaceable()
                || atHead.getCollisionBoundingBox(ws, feet.up()) == null
                || atHead.getBlock() == Blocks.SNOW_LAYER;

        if (!feetFree || !headFree) {
            return false;
        }

        BlockFaceShape shape = below.getBlockFaceShape(ws, feet.down(), EnumFacing.UP);
        if (shape == BlockFaceShape.SOLID) {
            return true;
        }

        AxisAlignedBB bb = below.getBoundingBox(ws, feet.down());
        return bb != Block.NULL_AABB && (bb.maxY - bb.minY) > 0.001D;
    }

    private boolean isVanillaWater(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }

    @Nullable
    public MerchantData getMerchantData(World world, UUID id) {
        if (!(world instanceof WorldServer)) return null;
        WorldState state = worldStates.get(world.provider.getDimension());
        return state == null ? null : state.merchants.get(id);
    }

    @Nullable
    public WorldState getWorldState(World world) {
        if (!(world instanceof WorldServer)) return null;
        return worldStates.get(world.provider.getDimension());
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().world.isRemote) return;
        if (!(event.getEntity().world instanceof WorldServer)) return;

        if (!(event.getEntity() instanceof EntityTraveller)) return;

        EntityTraveller t = (EntityTraveller) event.getEntity();
        if (!isChunkloader(t)) return;

        // removes from tracking and unforces if it was the last one
        untrackChunkloader(t);
    }

    public void untrackChunkloader(EntityTraveller traveller) {
        if (traveller.world.isRemote || !(traveller.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) traveller.world;

        WorldState state = getOrCreateState(ws);

        if (state.merchants.remove(traveller.getUniqueID()) != null) {
            if (state.merchants.isEmpty()) {
                // Last chunkloading travellers in this world is gone
                clearForcedChunks(state);
            } else {
                recomputeForWorld(state);
            }
        }
    }


    // ----------------------------------------------------------------
    // State / debug accessors
    // ----------------------------------------------------------------

    private void clearForcedChunks(WorldState state) {
        if (state.ticket == null) {
            state.forced.clear();
            state.hopForced.clear();
            state.hopForcedUntil.clear();
            return;
        }

        Set<ChunkPos> all = new HashSet<>();
        all.addAll(state.forced);
        all.addAll(state.hopForced);

        for (ChunkPos cp : all) {
            ForgeChunkManager.unforceChunk(state.ticket, cp);
        }

        state.forced.clear();
        state.hopForced.clear();
        state.hopForcedUntil.clear();

        NBTTagCompound tag = state.ticket.getModData();
        tag.removeTag(NBT_FORCED_CHUNKS);

        ForgeChunkManager.releaseTicket(state.ticket);
        state.ticket = null;
    }

    private void recomputeForWorld(WorldState state) {
        Iterator<Map.Entry<UUID, MerchantData>> it = state.merchants.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MerchantData> e = it.next();
            Entity ent = state.world.getEntityFromUuid(e.getKey());

            if (ent == null) {
                long now = state.world.getTotalWorldTime();
                long ttl = 20L * 60L * 5L;
                if (now - e.getValue().lastSeenTick > ttl) {
                    it.remove();
                }
                continue;
            }

            if (!(ent instanceof EntityTraveller) || !ent.isEntityAlive()) {
                it.remove();
                continue;
            }

            EntityTraveller t = (EntityTraveller) ent;
            if (!isChunkloader(t)) {
                it.remove();
            }
        }

        if (state.merchants.isEmpty()) {
            long now = state.world.getTotalWorldTime();
            boolean inGrace = state.restoredForcedFromDisk && now < state.restoreGraceUntilTick;
            boolean stillLoading = !state.pendingRestoreLoad.isEmpty();

            if (inGrace || stillLoading) {
                saveForcedToTicket(state);
                return;
            }

            if (state.ticket != null) {
                for (ChunkPos cp : state.forced) {
                    ForgeChunkManager.unforceChunk(state.ticket, cp);
                }
            }

            state.forced.clear();
            saveForcedToTicket(state);
            return;
        }

        ensureTicket(state);
        if (state.ticket == null) {
            return;
        }

        // Only HIRED workers get durable chunk forcing.
        // Non-hired workers move via blind offscreen hops without forcing chunks,
        // preventing the chunk explosion that causes OOM with 100+ workers.
        List<MerchantData> hired = new ArrayList<>();

        for (MerchantData md : state.merchants.values()) {
            Entity ent = state.world.getEntityFromUuid(md.id);
            if (ent instanceof EntityTravellerWorker) {
                md.hiredFollower = ((EntityTravellerWorker) ent).isHiredFollower();
            } else {
                md.hiredFollower = false;
            }
            md.chunkloading = md.hiredFollower;
            if (md.hiredFollower) {
                hired.add(md);
            }
        }

        if (hired.isEmpty()) {
            // No hired workers: unforce all durable chunks (non-hired don't need them)
            long now2 = state.world.getTotalWorldTime();
            boolean inGrace2 = state.restoredForcedFromDisk && now2 < state.restoreGraceUntilTick;
            boolean stillLoading2 = !state.pendingRestoreLoad.isEmpty();

            if (!inGrace2 && !stillLoading2 && state.ticket != null && !state.forced.isEmpty()) {
                for (ChunkPos cp : state.forced) {
                    ForgeChunkManager.unforceChunk(state.ticket, cp);
                }
                state.forced.clear();
                saveForcedToTicket(state);
            }
            return;
        }

        int maxForced = Math.max(1, TravellersModConfig.merchantMaxForcedChunks);
        Set<ChunkPos> desired = new HashSet<>();

        for (MerchantData md : hired) {
            if (desired.size() >= maxForced) break;

            Entity ent = state.world.getEntityFromUuid(md.id);
            if (!(ent instanceof EntityTraveller) || !ent.isEntityAlive()) continue;

            EntityTraveller t = (EntityTraveller) ent;
            desired.add(new ChunkPos(t.chunkCoordX, t.chunkCoordZ));
        }

        long now = state.world.getTotalWorldTime();
        boolean inGrace = state.restoredForcedFromDisk && now < state.restoreGraceUntilTick;
        boolean restoring = inGrace || !state.pendingRestoreLoad.isEmpty();

        Set<ChunkPos> toUnforce = new HashSet<>(state.forced);
        toUnforce.removeAll(desired);

        Set<ChunkPos> toForce = new HashSet<>(desired);
        toForce.removeAll(state.forced);


        if (restoring) {
            desired.addAll(state.forced);
            toUnforce.clear();
        }

        for (ChunkPos cp : toUnforce) {
            ForgeChunkManager.unforceChunk(state.ticket, cp);

            if (!shouldAvoidQueueUnload(state, cp)) {
                boolean stillForcedSomewhere = false;
                try {
                    com.google.common.collect.ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> forced =
                            ForgeChunkManager.getPersistentChunksFor(state.world);
                    stillForcedSomewhere = forced.containsKey(cp);
                } catch (Throwable ignored) {
                    stillForcedSomewhere = true;
                }

                if (!stillForcedSomewhere) {
                    state.unloading.put(cp, state.world.getTotalWorldTime());
                    ChunkProviderServer cps = state.world.getChunkProvider();
                    Chunk ch = cps.getLoadedChunk(cp.x, cp.z);
                    if (ch != null) {
                        cps.queueUnload(ch);
                    }
                } else {
                    state.unloading.remove(cp);
                }
            } else {
                state.unloading.remove(cp);
            }
        }

        for (ChunkPos cp : toForce) {
            // If this chunk is already transiently forced for a hop,
            // just promote it to durable instead of forcing again.
            if (state.hopForced.remove(cp)) {
                state.hopForcedUntil.remove(cp);
            } else {
                ForgeChunkManager.forceChunk(state.ticket, cp);
            }
        }

        state.forced.clear();
        state.forced.addAll(desired);

        saveForcedToTicket(state);
        vacuumUnneededChunks(state);
    }

    // ----------------------------------------------------
    // Inner classes
    // ----------------------------------------------------

    private void ensureTicket(WorldState state) {
        if (state.ticket != null) return;

        state.ticket = ForgeChunkManager.requestTicket(
                Travellers.INSTANCE,
                state.world,
                ForgeChunkManager.Type.NORMAL
        );
        if (state.ticket == null) {
            System.out.println("[Travellers] FAILED to get chunkloading ticket in dim " +
                    state.world.provider.getDimension());
            return;
        }
        System.out.println("[Travellers] Created ticket for dim " + state.world.provider.getDimension());
        state.ticket.setChunkListDepth(TravellersModConfig.merchantMaxForcedChunks);
    }

    @Nullable
    private static BlockPos getChunkloadGoal(EntityTraveller t) {
        // Worker-only: when catchup is active, follow the dynamic goal (owner/player pos)
        if (t instanceof EntityTravellerWorker) {
            EntityTravellerWorker w = (EntityTravellerWorker) t;

            if (w.isCatchupTravel()) { // your flag
                BlockPos cg = w.getDynamicCatchupGoal(); // your cached/updated goal (eg every 200 ticks)
                if (cg != null) return cg;
            }
        }

        // Everyone else: existing behavior
        return t.getTargetVillage();
    }

    private boolean shouldAvoidQueueUnload(WorldState state, ChunkPos cp) {
        WorldServer ws = state.world;

        // 1) If any player could be seeing it, don’t try to unload it.
        if (isInAnyPlayerViewDistance(ws, cp)) return true;

        // 2) If another mod (or spawn chunks, etc.) is forcing it, don’t try to unload it.
        return isForcedByOtherTicket(ws, cp, state.ticket);
    }

    private void vacuumUnneededChunks(WorldState state) {
        final long now = state.world.getTotalWorldTime();
        final long ttl = 20L * 60L; // 60 seconds; tune

        ChunkProviderServer cps = state.world.getChunkProvider();

        Iterator<Map.Entry<ChunkPos, Long>> it = state.unloading.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Long> e = it.next();
            ChunkPos cp = e.getKey();

            // if it became desired again, stop vacuuming it
            if (state.forced.contains(cp)) {
                it.remove();
                continue;
            }

            // give up after TTL (prevents unbounded growth)
            if (now - e.getValue() > ttl) {
                it.remove();
                continue;
            }

            Chunk ch = cps.getLoadedChunk(cp.x, cp.z);
            if (ch != null) {
                if (!shouldAvoidQueueUnload(state, cp)) {
                    cps.queueUnload(ch);
                } else {
                    // Stop vacuuming it if it’s now near players or forced elsewhere
                    it.remove();
                }
            } else {
                it.remove();
            }

        }

        // optional: process immediately (careful with perf)
        // cps.unloadQueuedChunks();
    }

    private boolean isInAnyPlayerViewDistance(WorldServer ws, ChunkPos cp) {
        // Server render distance in chunks
        int view = ws.getMinecraftServer().getPlayerList().getViewDistance();

        // Compare to each player in this dimension
        for (EntityPlayerMP p : ws.getMinecraftServer().getPlayerList().getPlayers()) {
            if (p.world != ws) continue;
            int pcx = p.chunkCoordX;
            int pcz = p.chunkCoordZ;

            int dx = Math.abs(cp.x - pcx);
            int dz = Math.abs(cp.z - pcz);

            // Vanilla view distance is a square
            if (dx <= view && dz <= view) return true;
        }
        return false;
    }

    private boolean isForcedByOtherTicket(WorldServer ws, ChunkPos cp, @Nullable ForgeChunkManager.Ticket ourTicket) {
        try {
            // In many Forge builds, this returns a multimap of forced chunks -> tickets.
            // Signature varies a bit across versions, so keep it wrapped in try/catch.
            // If it exists, it’s the best way to detect "another mod is forcing this chunk".
            com.google.common.collect.ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> forced =
                    ForgeChunkManager.getPersistentChunksFor(ws);

            if (!forced.containsKey(cp)) return false;

            for (ForgeChunkManager.Ticket t : forced.get(cp)) {
                if (ourTicket != null && t == ourTicket) continue; // ignore ourselves
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            // If method/signature not available in your Forge build, we just can’t detect it here.
            return false;
        }
    }

    private void batchLoadRestoredChunks(WorldState state) {
        if (state.pendingRestoreLoad.isEmpty()) return;

        final int perTick = 2;
        ChunkProviderServer cps = state.world.getChunkProvider();

        for (int i = 0; i < perTick && !state.pendingRestoreLoad.isEmpty(); i++) {
            ChunkPos pos = state.pendingRestoreLoad.pollFirst();
            cps.provideChunk(pos.x, pos.z);
        }

        // ✅ restore loading done: allow recompute to shrink again
        if (state.pendingRestoreLoad.isEmpty() && state.world.getTotalWorldTime() >= state.restoreGraceUntilTick) {
            state.restoredForcedFromDisk = false;
        }
    }

    @SubscribeEvent
    public void onWorldSave(WorldEvent.Save event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        WorldServer ws = (WorldServer) event.getWorld();
        WorldState state = worldStates.get(ws.provider.getDimension());
        if (state != null) saveForcedToTicket(state);
    }

    public static class WorldState {
        public final Set<ChunkPos> forced = new HashSet<>();
        public final ArrayDeque<ChunkPos> pendingRestoreLoad = new ArrayDeque<>();
        final WorldServer world;
        final Map<UUID, MerchantData> merchants = new HashMap<>();
        final Map<ChunkPos, Long> unloading = new HashMap<>();
        public long restoreGraceUntilTick = 0;
        public boolean restoredForcedFromDisk = false;
        ForgeChunkManager.Ticket ticket;
        public final Map<ChunkPos, Long> hopForcedUntil = new HashMap<>();
        public final Set<ChunkPos> hopForced = new HashSet<>();
        public long lastRespawnProcessDay = -1L;

        public WorldState(WorldServer w) {
            this.world = w;
        }


    }

    public static class MerchantData {
        public final UUID id;
        public BlockPos pos;
        public boolean chunkloading;
        public int radius;
        public double lastDistToTargetSq;
        public long lastProgressTick;
        public long lastOffscreenHopTick = 0;
        public long lastSeenTick;
        public boolean hiredFollower;
        public int lastChunkX, lastChunkZ;
        public boolean movedChunk;

        @Nullable
        private BlockPos lastTargetVillage;

        public MerchantData(EntityTraveller t, int radius) {
            this.id = t.getUniqueID();
            this.pos = t.getPosition();
            int cx = this.pos.getX() >> 4;
            int cz = this.pos.getZ() >> 4;
            this.lastChunkX = cx;
            this.lastChunkZ = cz;
            this.movedChunk = false;

            this.radius = radius;

            // ✅ track hired follower status
            if (t instanceof EntityTravellerWorker) {
                this.hiredFollower = ((EntityTravellerWorker) t).isHiredFollower();
            } else {
                this.hiredFollower = false;
            }

            BlockPos goal = TravellerChunkManager.getChunkloadGoal(t);
            this.lastTargetVillage = (goal == null ? null : goal.toImmutable());
            this.lastDistToTargetSq = computeDistSqToGoal(t);
            this.lastProgressTick = t.world.getTotalWorldTime();
            this.lastSeenTick = t.world.getTotalWorldTime(); // (you already added lastSeenTick elsewhere)
        }

        private double computeDistSqToGoal(EntityTraveller t) {
            BlockPos goal = TravellerChunkManager.getChunkloadGoal(t);
            if (goal == null) return Double.MAX_VALUE;
            return t.getDistanceSqToCenter(goal);
        }

        public void updateFromEntity(EntityTraveller t) {
            this.lastSeenTick = t.world.getTotalWorldTime();
            this.pos = t.getPosition();

            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;

            if (cx != lastChunkX || cz != lastChunkZ) {
                movedChunk = true;
                lastChunkX = cx;
                lastChunkZ = cz;
            }

            BlockPos tv = TravellerChunkManager.getChunkloadGoal(t);
            long nowTick = t.world.getTotalWorldTime();

            // ✅ keep hired status fresh
            if (t instanceof EntityTravellerWorker) {
                this.hiredFollower = ((EntityTravellerWorker) t).isHiredFollower();
            } else {
                this.hiredFollower = false;
            }

            if (tv == null) {
                // No target -> don't consider them stuck, just keep resetting
                lastTargetVillage = null;
                lastDistToTargetSq = Double.MAX_VALUE;
                lastProgressTick = nowTick;
                return;
            }

            double nowD2 = computeDistSqToTarget(t);

            if (!Objects.equals(tv, lastTargetVillage)) {
                lastTargetVillage = tv.toImmutable();
                lastDistToTargetSq = nowD2;
                lastProgressTick = nowTick;
                return;
            }

            if (nowD2 < lastDistToTargetSq - 1.0D) {
                lastDistToTargetSq = nowD2;
                lastProgressTick = nowTick;
            }
        }

        private double computeDistSqToTarget(EntityTraveller t) {
            BlockPos tv = t.getTargetVillage();
            if (tv == null) return Double.MAX_VALUE;
            return t.getDistanceSqToCenter(tv);
        }

        public int chunkloadingPriority(WorldServer world) {
            Entity e = world.getEntityFromUuid(this.id);

            // ✅ hired followers must always be chunkloaded
            if (e instanceof EntityTravellerWorker) {
                EntityTravellerWorker w = (EntityTravellerWorker) e;
                if (w.isHiredFollower()) return 1000;
            }

            // ✅ near-player chunkloaders should win (optional)
            if (e instanceof EntityTraveller) {
                EntityTraveller t = (EntityTraveller) e;
                if (t.isInPlayerViewDistance()) return 50;
            }

            int idleTicks = ticksSinceLastProgress(world);
            if (idleTicks < 200) return 2;
            if (idleTicks < 1200) return 1;
            return 0;
        }

        public int ticksSinceLastProgress(WorldServer world) {
            return (int) (world.getTotalWorldTime() - lastProgressTick);
        }

    }
    private ChunkPos chunkPosFromBlock(BlockPos p) {
        return new ChunkPos(p.getX() >> 4, p.getZ() >> 4);
    }

    private ChunkPos computeHopLandingChunk(EntityTraveller t, BlockPos goal, int stepBlocks) {
        BlockPos from = t.getPosition();
        int fx = from.getX();
        int fz = from.getZ();

        int gx = goal.getX();
        int gz = goal.getZ();

        int dx = gx - fx;
        int dz = gz - fz;

        // If we're already basically there, just keep current chunk.
        if ((dx * dx + dz * dz) < 4) {
            return new ChunkPos(fx >> 4, fz >> 4);
        }

        double dist = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
        if (dist < 1e-6) {
            return new ChunkPos(fx >> 4, fz >> 4);
        }

        double nx = dx / dist;
        double nz = dz / dist;

        // hop in blocks (no need to clamp to 16; chunk forcing will follow the landing)
        int hop = Math.max(1, stepBlocks);

        int lx = fx + (int) Math.round(nx * hop);
        int lz = fz + (int) Math.round(nz * hop);

        return new ChunkPos(lx >> 4, lz >> 4);
    }

    private void forceChunkIfPossible(WorldState state, ChunkPos cp) {
        forceTransientHopChunk(state, cp);
    }

    private void forceTransientHopChunk(WorldState state, ChunkPos cp) {
        if (state.ticket == null) return;

        long now = state.world.getTotalWorldTime();
        long expireAt = now + OFFSCREEN_HOP_FORCE_TTL_TICKS;

        // Already durable: no need to track as transient.
        if (state.forced.contains(cp)) {
            return;
        }

        // Already transient: just extend TTL.
        if (state.hopForced.contains(cp)) {
            state.hopForcedUntil.put(cp, expireAt);
            return;
        }

        // Respect ticket depth. Count durable + transient.
        int totalForcedByUs = state.forced.size() + state.hopForced.size();
        if (totalForcedByUs >= TravellersModConfig.merchantMaxForcedChunks) {
            return;
        }

        ForgeChunkManager.forceChunk(state.ticket, cp);
        state.hopForced.add(cp);
        state.hopForcedUntil.put(cp, expireAt);

        // IMPORTANT:
        // Do NOT save transient hop chunks into ticket NBT.
    }
    private void tickTransientHopForcedChunks(WorldState state) {
        if (state.ticket == null) return;

        long now = state.world.getTotalWorldTime();

        Iterator<Map.Entry<ChunkPos, Long>> it = state.hopForcedUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkPos, Long> e = it.next();
            ChunkPos cp = e.getKey();
            long expireAt = e.getValue();

            if (expireAt > now) {
                continue;
            }

            it.remove();
            state.hopForced.remove(cp);

            // Do not unforce durable chunks here.
            if (state.forced.contains(cp)) {
                continue;
            }

            ForgeChunkManager.unforceChunk(state.ticket, cp);

            if (!shouldAvoidQueueUnload(state, cp)) {
                ChunkProviderServer cps = state.world.getChunkProvider();
                Chunk ch = cps.getLoadedChunk(cp.x, cp.z);
                if (ch != null) {
                    cps.queueUnload(ch);
                }
            }
        }
    }
}

