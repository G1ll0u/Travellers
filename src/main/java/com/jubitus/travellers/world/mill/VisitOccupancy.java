package com.jubitus.travellers.world.mill;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Which spot each village visitor is currently heading to or standing at, so the next one to pick a
 * target can prefer a quieter one and keep a little room from the others. One claim per visitor;
 * claims expire on their own, so a visitor that vanished without releasing never blocks a spot for long.
 */
public final class VisitOccupancy {

    private static final Map<World, Map<UUID, Claim>> CLAIMS = new WeakHashMap<>();

    private static final class Claim {
        final BlockPos pos;
        final long expiresTick;

        Claim(BlockPos pos, long expiresTick) {
            this.pos = pos;
            this.expiresTick = expiresTick;
        }
    }

    private VisitOccupancy() {
    }

    public static void claim(World world, UUID who, BlockPos pos, int ttlTicks) {
        CLAIMS.computeIfAbsent(world, w -> new HashMap<>())
                .put(who, new Claim(pos, world.getTotalWorldTime() + Math.max(20, ttlTicks)));
    }

    public static void release(World world, UUID who) {
        Map<UUID, Claim> m = CLAIMS.get(world);
        if (m != null) m.remove(who);
    }

    /** Visitors (other than {@code except}) holding a spot within {@code radius} blocks of {@code pos} (XZ). */
    public static int countNear(World world, BlockPos pos, double radius, @Nullable UUID except) {
        Map<UUID, Claim> m = CLAIMS.get(world);
        if (m == null || m.isEmpty()) return 0;
        long now = world.getTotalWorldTime();
        double r2 = radius * radius;
        int n = 0;
        Iterator<Map.Entry<UUID, Claim>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Claim> e = it.next();
            Claim c = e.getValue();
            if (c.expiresTick < now) {
                it.remove();
                continue;
            }
            if (e.getKey().equals(except)) continue;
            double dx = c.pos.getX() - pos.getX();
            double dz = c.pos.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= r2 && Math.abs(c.pos.getY() - pos.getY()) <= 3) n++;
        }
        return n;
    }

    /** Weight multiplier for a candidate spot: 1 when free, lower the more visitors are already there. */
    public static double crowdFactor(World world, BlockPos pos, double radius, @Nullable UUID except, double penalty) {
        if (penalty <= 0.0D) return 1.0D;
        return 1.0D / (1.0D + penalty * countNear(world, pos, radius, except));
    }
}
