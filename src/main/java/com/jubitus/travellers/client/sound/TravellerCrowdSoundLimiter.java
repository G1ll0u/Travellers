package com.jubitus.travellers.client.sound;

import com.jubitus.travellers.config.TravellersConfig;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class TravellerCrowdSoundLimiter {

    private TravellerCrowdSoundLimiter() {
    }

    private static final Map<UUID, DensitySample> DENSITY_CACHE = new HashMap<>();
    private static final Map<UUID, DensitySample> COMBAT_DENSITY_CACHE = new HashMap<>();
    private static final Map<Long, Integer> ZONE_BUDGETS = new HashMap<>();

    private static long lastPruneTick = Long.MIN_VALUE;

    private static final int TYPE_STEP = 1;
    private static final int TYPE_HURT = 2;

    private static final class DensitySample {
        long tick;
        int count;

        DensitySample(long tick, int count) {
            this.tick = tick;
            this.count = count;
        }
    }

    public static boolean allowStep(EntityTraveller t) {
        if (t == null || t.world == null || t.world.isRemote) return true;
        if (!t.isEntityAlive()) return false;

        double strength = Math.max(0.0D, TravellersConfig.SOUND.travellerStepLimiterStrength);
        if (strength <= 0.0D) {
            return true;
        }

        int crowd = getNearbyTravellerCount(t, 12.0D, 8);

        int stride = MathHelper.clamp((int) Math.round(((crowd + 5) / 6.0D) * strength), 1, 16);

        long now = t.world.getTotalWorldTime();
        if (Math.floorMod(t.getEntityId(), stride) != Math.floorMod((int) now, stride)) {
            return false;
        }

        int budgetPerWindow;
        if (crowd <= 6) {
            budgetPerWindow = 4;
        } else if (crowd <= 16) {
            budgetPerWindow = 3;
        } else if (crowd <= 32) {
            budgetPerWindow = 2;
        } else {
            budgetPerWindow = 1;
        }

        budgetPerWindow = Math.max(1, (int) Math.round(budgetPerWindow / strength));

        return tryConsumeZoneBudget(t.world, t.getPosition(), TYPE_STEP, 2, budgetPerWindow);
    }

    public static boolean allowHurt(EntityTraveller t) {
        if (t == null || t.world == null || t.world.isRemote) return true;
        if (!t.isEntityAlive()) return false;

        double strength = Math.max(0.0D, TravellersConfig.SOUND.travellerHurtLimiterStrength);
        if (strength <= 0.0D) {
            return true;
        }

        // Hors combat : aucun throttling
        if (!t.isInCombat()) {
            return true;
        }

        int fighters = getNearbyCombatTravellerCount(t, 20.0D, 4);

        // Petit combat : pas de throttling
        if (fighters <= 8) {
            return true;
        }

        int budgetPerWindow;
        if (fighters <= 16) {
            budgetPerWindow = 6;
        } else if (fighters <= 32) {
            budgetPerWindow = 5;
        } else if (fighters <= 64) {
            budgetPerWindow = 4;
        } else {
            budgetPerWindow = 3;
        }

        budgetPerWindow = Math.max(1, (int) Math.round(budgetPerWindow / strength));

        return tryConsumeZoneBudget(t.world, t.getPosition(), TYPE_HURT, 3, budgetPerWindow);
    }

    private static int getNearbyCombatTravellerCount(EntityTraveller t, double radius, int cacheTicks) {
        UUID id = t.getUniqueID();
        long now = t.world.getTotalWorldTime();

        DensitySample cached = COMBAT_DENSITY_CACHE.get(id);
        if (cached != null && (now - cached.tick) < cacheTicks) {
            return cached.count;
        }

        AxisAlignedBB box = t.getEntityBoundingBox().grow(radius, 6.0D, radius);
        int count = t.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                box,
                e -> e != null && e.isEntityAlive() && e.isInCombat()
        ).size();

        COMBAT_DENSITY_CACHE.put(id, new DensitySample(now, count));
        return count;
    }

    private static int getNearbyTravellerCount(EntityTraveller t, double radius, int cacheTicks) {
        UUID id = t.getUniqueID();
        long now = t.world.getTotalWorldTime();

        DensitySample cached = DENSITY_CACHE.get(id);
        if (cached != null && (now - cached.tick) < cacheTicks) {
            return cached.count;
        }

        AxisAlignedBB box = t.getEntityBoundingBox().grow(radius, 4.0D, radius);
        int count = t.world.getEntitiesWithinAABB(
                EntityTraveller.class,
                box,
                e -> e != null && e.isEntityAlive()
        ).size();

        DENSITY_CACHE.put(id, new DensitySample(now, count));
        return count;
    }

    private static boolean tryConsumeZoneBudget(World world, BlockPos pos, int type, int windowTicks, int budget) {
        long now = world.getTotalWorldTime();
        pruneOldEntries(now);

        int dim = world.provider.getDimension();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        long window = now / Math.max(1, windowTicks);

        long key = packKey(dim, cx, cz, type, window);
        int used = ZONE_BUDGETS.getOrDefault(key, 0);

        if (used >= budget) {
            return false;
        }

        ZONE_BUDGETS.put(key, used + 1);
        return true;
    }

    private static void pruneOldEntries(long now) {
        if (lastPruneTick != Long.MIN_VALUE && now - lastPruneTick < 40) {
            return;
        }
        lastPruneTick = now;

        long minWindowToKeep = (now / 2L) - 40L;

        Iterator<Map.Entry<Long, Integer>> it = ZONE_BUDGETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Integer> en = it.next();
            long key = en.getKey();
            long window = unpackWindow(key);
            if (window < minWindowToKeep) {
                it.remove();
            }
        }

        Iterator<Map.Entry<UUID, DensitySample>> it2 = DENSITY_CACHE.entrySet().iterator();
        while (it2.hasNext()) {
            Map.Entry<UUID, DensitySample> en = it2.next();
            if (now - en.getValue().tick > 40) {
                it2.remove();
            }
        }

        Iterator<Map.Entry<UUID, DensitySample>> it3 = COMBAT_DENSITY_CACHE.entrySet().iterator();
        while (it3.hasNext()) {
            Map.Entry<UUID, DensitySample> en = it3.next();
            if (now - en.getValue().tick > 40) {
                it3.remove();
            }
        }
    }

    private static long packKey(int dim, int cx, int cz, int type, long window) {
        long d = ((long) dim & 0xFFFFL) << 48;
        long x = ((long) (cx & 0xFFF)) << 36;
        long z = ((long) (cz & 0xFFF)) << 24;
        long ty = ((long) (type & 0xF)) << 20;
        long w = (window & 0xFFFFFL);
        return d | x | z | ty | w;
    }

    private static long unpackWindow(long key) {
        return key & 0xFFFFFL;
    }
}