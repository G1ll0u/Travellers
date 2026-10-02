package com.jubitus.travellers.entity.entityai.travel;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Single place for door / fence-gate handling shared by every EntityTraveller subclass.
 *
 * Detection (this class) covers vanilla and most modded blocks - anything extending
 * {@link BlockDoor} (wood material, or all with {@code travellerOperateMetalDoors}) or
 * {@link BlockFenceGate}.
 *
 * Opening is done by {@link EntityAIPassDoor} the tick a traveller reaches a closed doorway it
 * is heading through. Closing is owned entirely by {@link #tick} (called once per world tick
 * from the chunk-manager hub): a doorway is closed again once nothing holds it, no traveller is
 * still walking into it, and nothing living is standing in it. That central owner is why the
 * close-behind works the same for plain pathing, travel AI, follow steering and the stable job -
 * none of them have to get the timing right themselves.
 *
 * Server thread only. The hold registry is keyed by {@link World} identity so two saves in one
 * client session cannot bleed state.
 */
public final class TravellerDoorways {

    private static final Map<World, Map<BlockPos, Hold>> HOLDS = new WeakHashMap<>();

    /** A traveller this close to a held doorway (or moving toward it) keeps it open. */
    static final double GUARD_RADIUS = 3.0D;
    /** Holders whose entity has strayed past this are dropped (task starved, teleported, ...). */
    private static final double HOLDER_FORGET_RADIUS = GUARD_RADIUS + 4.0D;
    /** A hold nobody has touched for this long is dropped outright (safety net). */
    private static final long STALE_TICKS = 2400L;

    private TravellerDoorways() {
    }

    // --- type detection ---------------------------------------------------

    public static boolean isDoor(IBlockState st) {
        if (!(st.getBlock() instanceof BlockDoor)) return false;
        return TravellersConfig.GENERAL.travellerOperateMetalDoors || st.getMaterial() == Material.WOOD;
    }

    public static boolean isGate(IBlockState st) {
        return st.getBlock() instanceof BlockFenceGate;
    }

    public static boolean isDoorway(IBlockState st) {
        return isDoor(st) || isGate(st);
    }

    public static boolean isOpen(World w, BlockPos p) {
        IBlockState st = w.getBlockState(p);
        Block b = st.getBlock();
        if (b instanceof BlockDoor) return BlockDoor.isOpen(w, p);
        if (b instanceof BlockFenceGate) return st.getValue(BlockFenceGate.OPEN);
        return false;
    }

    /** Normalises a door position to its lower half; a gate is returned unchanged; null if neither. */
    public static BlockPos normalize(World w, BlockPos p) {
        IBlockState st = w.getBlockState(p);
        if (st.getBlock() instanceof BlockDoor) {
            return st.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.UPPER ? p.down().toImmutable() : p.toImmutable();
        }
        if (st.getBlock() instanceof BlockFenceGate) return p.toImmutable();
        return null;
    }

    // --- open / close ---------------------------------------------------

    /** Opens or closes the doorway at {@code p} (either door half accepted). */
    public static void setOpen(World w, BlockPos p, boolean open) {
        IBlockState st = w.getBlockState(p);
        Block b = st.getBlock();

        if (b instanceof BlockDoor) {
            BlockPos lower = st.getValue(BlockDoor.HALF) == BlockDoor.EnumDoorHalf.UPPER ? p.down() : p;
            IBlockState lowerSt = w.getBlockState(lower);
            if (lowerSt.getBlock() instanceof BlockDoor && lowerSt.getValue(BlockDoor.OPEN) != open) {
                ((BlockDoor) lowerSt.getBlock()).toggleDoor(w, lower, open);
            }
            return;
        }

        if (b instanceof BlockFenceGate && st.getValue(BlockFenceGate.OPEN) != open) {
            w.setBlockState(p, st.withProperty(BlockFenceGate.OPEN, open), 2);
            w.playEvent(null, open ? 1008 : 1014, p, 0);
        }
    }

    // --- hold registry ---------------------------------------------------

    private static Map<BlockPos, Hold> mapFor(World w) {
        return HOLDS.computeIfAbsent(w, k -> new HashMap<>());
    }

    public static void acquire(World w, BlockPos pos, int entityId) {
        Hold h = mapFor(w).computeIfAbsent(pos.toImmutable(), k -> new Hold());
        h.holders.add(entityId);
        h.lastTouch = w.getTotalWorldTime();
    }

    public static void touch(World w, BlockPos pos, int entityId) {
        Hold h = mapFor(w).get(pos);
        if (h == null) return;
        h.holders.add(entityId);
        h.lastTouch = w.getTotalWorldTime();
    }

    public static void release(World w, BlockPos pos, int entityId) {
        Hold h = mapFor(w).get(pos);
        if (h == null) return;
        h.holders.remove(entityId);
        h.lastTouch = w.getTotalWorldTime();
    }

    public static boolean isHeld(World w, BlockPos pos) {
        Map<BlockPos, Hold> m = HOLDS.get(w);
        return m != null && m.containsKey(pos);
    }

    public static void releaseAll(World w, int entityId) {
        Map<BlockPos, Hold> m = HOLDS.get(w);
        if (m == null) return;
        for (Hold h : m.values()) h.holders.remove(entityId);
    }

    // --- central close owner ---------------------------------------------

    public static void tick(WorldServer ws, long now) {
        if (now % 4L != 0L) return;
        Map<BlockPos, Hold> m = HOLDS.get(ws);
        if (m == null || m.isEmpty()) return;

        boolean closeBehind = TravellersConfig.GENERAL.travellerCloseDoorsBehind;

        Iterator<Map.Entry<BlockPos, Hold>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Hold> e = it.next();
            BlockPos pos = e.getKey();
            Hold h = e.getValue();

            if (!ws.isBlockLoaded(pos, false)) {
                // never load a chunk just to close a gate; forget it, a later pass re-registers
                it.remove();
                continue;
            }
            if (!isDoorway(ws.getBlockState(pos))) {
                it.remove();
                continue;
            }

            // drop holders whose entity is gone / dead / walked off without releasing
            double cx = pos.getX() + 0.5D, cz = pos.getZ() + 0.5D;
            h.holders.removeIf(id -> {
                Entity ent = ws.getEntityByID(id);
                if (ent == null || ent.isDead) return true;
                double dx = cx - ent.posX, dz = cz - ent.posZ;
                return dx * dx + dz * dz > HOLDER_FORGET_RADIUS * HOLDER_FORGET_RADIUS;
            });

            if (!h.holders.isEmpty()) {
                if (now - h.lastTouch > STALE_TICKS) it.remove();
                continue;
            }

            if (!isOpen(ws, pos)) {          // someone/something already closed it
                it.remove();
                continue;
            }
            if (livingInDoorway(ws, pos)) {  // don't shut it on anyone
                h.lastTouch = now;
                continue;
            }
            if (travellerStillHeadingIn(ws, pos)) {
                // a trailing group member is walking into it - hold the door for a grace window
                if (now - h.lastTouch < 100L) continue;
            }

            if (closeBehind) setOpen(ws, pos, false);
            it.remove();
        }
    }

    private static boolean livingInDoorway(WorldServer ws, BlockPos pos) {
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 2, pos.getZ() + 1).grow(0.12D, 0.0D, 0.12D);
        for (EntityLivingBase le : ws.getEntitiesWithinAABB(EntityLivingBase.class, box)) {
            if (le.isEntityAlive()) return true;
        }
        return false;
    }

    private static boolean travellerStillHeadingIn(WorldServer ws, BlockPos pos) {
        double cx = pos.getX() + 0.5D, cz = pos.getZ() + 0.5D;
        AxisAlignedBB box = new AxisAlignedBB(pos).grow(GUARD_RADIUS, 2.0D, GUARD_RADIUS);
        List<EntityTraveller> near = ws.getEntitiesWithinAABB(EntityTraveller.class, box);
        for (EntityTraveller t : near) {
            if (!t.isEntityAlive()) continue;
            double dx = cx - t.posX, dz = cz - t.posZ;
            double d2 = dx * dx + dz * dz;
            if (d2 > GUARD_RADIUS * GUARD_RADIUS) continue;
            if (d2 < 1.6D * 1.6D) return true;                 // in / right at the doorway
            if (t.motionX * dx + t.motionZ * dz > 0.001D) return true; // moving toward it
        }
        return false;
    }

    private static final class Hold {
        final Set<Integer> holders = new HashSet<>();
        long lastTouch;
    }
}
