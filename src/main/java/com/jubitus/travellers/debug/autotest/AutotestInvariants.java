package com.jubitus.travellers.debug.autotest;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-cutting invariants checked in one pass over loaded units and virtual records. Placements and
 * worker deaths are watched between passes, only on a server started by the autotest runner.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class AutotestInvariants {

    private static final boolean WATCHING = Boolean.getBoolean("travellers.autotest");
    private static final int MAX_LISTED = 40;
    // Feet this far above a building's lowest block, under open sky, are on its roof (a ground-level courtyard is not).
    private static final int ROOF_MIN_HEIGHT = 2;
    private static final int ROOF_HOLLOW_DEPTH = 6;

    private static final List<String> badPlacements = new ArrayList<>();
    private static final Set<UUID> diedWorkers = new HashSet<>();
    private static Set<UUID> lastHiredWorkers;
    private static int placementsChecked;
    private static int diskLoads;

    private AutotestInvariants() {
    }

    // INVARIANT: a body read from a chunk file is already in its chunk when it joins; a fresh placement is not.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onJoin(EntityJoinWorldEvent event) {
        if (!WATCHING || event.getWorld().isRemote || !(event.getEntity() instanceof EntityTraveller)) return;
        Entity e = event.getEntity();
        if (e.addedToChunk) {
            diskLoads++;
            return;
        }
        placementsChecked++;
        World world = event.getWorld();
        if (world.collidesWithAnyBlock(e.getEntityBoundingBox())) {
            badPlacements.add("placed inside a wall (" + collidingBlocks(world, e) + "): " + describe(e) + " by " + placer());
            return;
        }
        MillFootprints.Footprint f = MillFootprints.at(world, e.posX, e.posY, e.posZ, 0);
        if (f != null && e.posY > f.minY + ROOF_MIN_HEIGHT && world.canSeeSky(new BlockPos(e)) && hollowBelow(world, e, f)) {
            badPlacements.add("placed on a Millenaire roof " + bounds(f) + ": " + describe(e) + " by " + placer());
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (WATCHING && event.getEntity() instanceof EntityTravellerWorker && !event.getEntity().world.isRemote) {
            diedWorkers.add(event.getEntity().getUniqueID());
        }
    }

    /** A virtual battle death removes the record without a LivingDeathEvent. */
    public static void onVirtualDeath(UUID workerUUID) {
        if (WATCHING) diedWorkers.add(workerUUID);
    }

    private static String collidingBlocks(World world, Entity e) {
        AxisAlignedBB box = e.getEntityBoundingBox();
        Set<String> names = new java.util.TreeSet<>();
        for (BlockPos p : BlockPos.getAllInBox(new BlockPos(box.minX, box.minY, box.minZ), new BlockPos(box.maxX, box.maxY, box.maxZ))) {
            IBlockState state = world.getBlockState(p);
            AxisAlignedBB bb = state.getCollisionBoundingBox(world, p);
            if (bb != null && bb != Block.NULL_AABB && bb.offset(p).intersects(box)) {
                names.add(state.getBlock().getRegistryName() + "@" + (p.getY() - MathHelper.floor(e.posY)));
            }
        }
        return String.join(", ", names);
    }

    /** A roof has a room under it; a fountain, courtyard or filled terrace stands on solid ground. */
    private static boolean hollowBelow(World world, Entity e, MillFootprints.Footprint f) {
        BlockPos support = new BlockPos(e).down();
        for (int dy = 1; dy <= ROOF_HOLLOW_DEPTH && support.getY() - dy >= f.minY; dy++) {
            if (world.isAirBlock(support.down(dy))) return true;
        }
        return false;
    }

    private static String bounds(MillFootprints.Footprint f) {
        return f == null ? "?" : String.format("x %d..%d y %d..%d z %d..%d", f.minX, f.maxX, f.minY, f.maxY, f.minZ, f.maxZ);
    }

    /** First mod frame above the spawn call, so a bad placement names its code path. */
    private static String placer() {
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            String c = f.getClassName();
            if (c.startsWith("com.jubitus.travellers") && !c.startsWith("com.jubitus.travellers.debug.autotest")) {
                return c.substring(c.lastIndexOf('.') + 1) + "." + f.getMethodName() + ":" + f.getLineNumber();
            }
        }
        return "?";
    }

    /** Runs every check, appends the details to the report and returns the one-line verdict. */
    public static String check(MinecraftServer server) {
        List<String> fails = new ArrayList<>();
        Map<UUID, Entity> loaded = new HashMap<>();
        Map<UUID, VirtualTravellerRecord> records = new HashMap<>();
        Set<UUID> hired = new HashSet<>();
        int[] loadedByClass = new int[3];
        int[] virtualByClass = new int[3];

        for (WorldServer world : server.worlds) {
            for (Entity e : world.loadedEntityList) {
                if (!(e instanceof EntityTraveller) || e.isDead) continue;
                loadedByClass[classIndex(e)]++;
                Entity other = loaded.put(e.getUniqueID(), e);
                if (other != null) fails.add("two loaded bodies share an identity: " + describe(e) + " / " + describe(other));
                if (!isSanePosition(e.posX, e.posY, e.posZ)) fails.add("bad position: " + describe(e));
                if (e instanceof EntityTravellerWorker && ((EntityTravellerWorker) e).isHiredFollower()) hired.add(e.getUniqueID());
            }
            for (VirtualTravellerRecord r : VirtualTravellerManager.get(world).getAllRecords()) {
                virtualByClass[recordClassIndex(r)]++;
                VirtualTravellerRecord other = records.put(r.uuid, r);
                if (other != null) fails.add("two records share an identity: " + describe(r));
                if (!isSanePosition(r.posX, r.posY, r.posZ)) fails.add("bad record position: " + describe(r));
                if ("WORKER".equals(r.entityClass) && r.isHiredFollower) {
                    if (r.followOwnerUUID == null) fails.add("hired worker record without an owner: " + describe(r));
                    hired.add(r.uuid);
                }
            }
        }
        for (Entity e : loaded.values()) {
            VirtualTravellerRecord r = records.get(e.getUniqueID());
            if (r != null) fails.add("loaded AND virtual: " + describe(e) + " / " + describe(r));
        }
        if (lastHiredWorkers != null) {
            for (UUID id : lastHiredWorkers) {
                if (!hired.contains(id) && !diedWorkers.contains(id)) fails.add("hired worker vanished without dying: " + id);
            }
        }
        lastHiredWorkers = hired;
        diedWorkers.clear();
        fails.addAll(badPlacements);
        badPlacements.clear();

        String counts = String.format("loaded T/R/W %d/%d/%d, virtual T/R/W %d/%d/%d, hired %d, placements %d, disk loads %d, zone chunks %d",
                loadedByClass[0], loadedByClass[1], loadedByClass[2], virtualByClass[0], virtualByClass[1],
                virtualByClass[2], hired.size(), placementsChecked, diskLoads, AutotestZones.forcedChunkCount());
        List<String> lines = new ArrayList<>();
        lines.add(counts);
        for (int i = 0; i < fails.size() && i < MAX_LISTED; i++) lines.add("FAIL " + fails.get(i));
        if (fails.size() > MAX_LISTED) lines.add("... " + (fails.size() - MAX_LISTED) + " more");
        if (fails.isEmpty()) lines.add("PASS");
        AutotestReport.append("invariants", lines);
        return (fails.isEmpty() ? "PASS" : "FAIL " + fails.size()) + " | " + counts;
    }

    private static boolean isSanePosition(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && y >= 0 && y < 512;
    }

    private static int classIndex(Entity e) {
        if (e instanceof EntityTravellerWorker) return 2;
        if (e instanceof EntityTravellerRaider) return 1;
        return 0;
    }

    private static int recordClassIndex(VirtualTravellerRecord r) {
        if ("WORKER".equals(r.entityClass)) return 2;
        if ("RAIDER".equals(r.entityClass)) return 1;
        return 0;
    }

    private static String describe(Entity e) {
        return String.format("%s %s dim %d (%.1f, %.1f, %.1f)", e.getName(), e.getUniqueID(), e.dimension, e.posX, e.posY, e.posZ);
    }

    private static String describe(VirtualTravellerRecord r) {
        return String.format("record %s %s %s dim %d (%.1f, %.1f, %.1f)", r.entityClass, r.name, r.uuid, r.dimension, r.posX, r.posY, r.posZ);
    }
}
