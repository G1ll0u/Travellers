package com.jubitus.travellers.combat;

import com.jubitus.travellers.pathing.PathBudget;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import org.millenaire.common.entity.MillVillager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The world query behind the proactive threat scan, shared by a whole group for a few ticks: a
 * hundred followers around one owner run one entity search, not a hundred. Returns a superset of
 * what any member could target (mobs, players, units, villagers, listed entities); each member
 * still applies its own exact hostility rules to it.
 */
public final class SharedThreatScan {

    private static final int FRESH_TICKS = 8;
    /** Formation spread around the anchor that a member's own box may reach and still be served. */
    private static final double FORMATION_MARGIN = 16.0D;
    private static final int EVICT_AGE_TICKS = 200;

    private static final class Entry {
        final int dimension;
        final long tick;
        final double reach;
        final AxisAlignedBB box;
        final List<EntityLivingBase> candidates;

        Entry(int dimension, long tick, double reach, AxisAlignedBB box, List<EntityLivingBase> candidates) {
            this.dimension = dimension;
            this.tick = tick;
            this.reach = reach;
            this.box = box;
            this.candidates = candidates;
        }
    }

    private static final Map<UUID, Entry> CACHE = new HashMap<>();

    private SharedThreatScan() {
    }

    /**
     * Possible threats inside {@code box}. {@code scope} names the group (owner or group leader) and
     * {@code anchor} is that group's centre; both null scans {@code box} alone. {@code reach} is the
     * member's own detection range. Null when the scan budget refused this tick.
     */
    @Nullable
    public static List<EntityLivingBase> scan(EntityTraveller scanner, @Nullable UUID scope, @Nullable Entity anchor,
                                              AxisAlignedBB box, double reach, int priority) {
        World world = scanner.world;
        UUID scopeOwner = scanner instanceof EntityTravellerWorker ? ((EntityTravellerWorker) scanner).getFollowOwnerId() : null;
        if (scope == null || anchor == null || anchor.world != world) {
            return direct(scanner, box, scopeOwner, priority);
        }

        long now = world.getTotalWorldTime();
        int dim = world.provider.getDimension();
        Entry entry = CACHE.get(scope);
        boolean sameDim = entry != null && entry.dimension == dim;
        if (!sameDim || now - entry.tick >= FRESH_TICKS || !contains(entry.box, box)) {
            double sharedReach = Math.max(reach, sameDim && now - entry.tick < EVICT_AGE_TICKS ? entry.reach : 0.0D);
            AxisAlignedBB shared = anchor.getEntityBoundingBox().grow(sharedReach + FORMATION_MARGIN);
            // A member far from its group (an idle worker across the map) scans its own box only.
            if (!contains(shared, box)) return direct(scanner, box, scopeOwner, priority);
            if (!PathBudget.tryAcquire(world, PathBudget.Client.COMBAT_SCAN, scanner.getEntityId(), priority)) return null;
            entry = new Entry(dim, now, sharedReach, shared, query(scanner, shared, scopeOwner));
            evictStale(now);
            CACHE.put(scope, entry);
        }

        List<EntityLivingBase> out = new ArrayList<>();
        for (EntityLivingBase e : entry.candidates) {
            if (e.isEntityAlive() && e.getEntityBoundingBox().intersects(box)) out.add(e);
        }
        return out;
    }

    @Nullable
    private static List<EntityLivingBase> direct(EntityTraveller scanner, AxisAlignedBB box, @Nullable UUID scopeOwner, int priority) {
        if (!PathBudget.tryAcquire(scanner.world, PathBudget.Client.COMBAT_SCAN, scanner.getEntityId(), priority)) return null;
        return query(scanner, box, scopeOwner);
    }

    private static List<EntityLivingBase> query(EntityTraveller scanner, AxisAlignedBB box, @Nullable UUID scopeOwner) {
        return scanner.world.getEntitiesWithinAABB(EntityLivingBase.class, box,
                e -> e != null && e.isEntityAlive() && mayBeThreat(scanner, e, scopeOwner));
    }

    // INVARIANT: member-specific hostility (players, workers, villagers, other units) is kept whole here; only group-uniform rules filter.
    private static boolean mayBeThreat(EntityTraveller scanner, EntityLivingBase e, @Nullable UUID scopeOwner) {
        if (e instanceof EntityTravellerWorker && scopeOwner != null) {
            EntityTravellerWorker w = (EntityTravellerWorker) e;
            return !(w.isHiredFollower() && scopeOwner.equals(w.getFollowOwnerId()));
        }
        return e instanceof EntityPlayer
                || e instanceof EntityTraveller
                || e instanceof MillVillager
                || e instanceof IMob
                || TravellerHostilityHelper.isLavaCowAggressive(e)
                || scanner.shouldAttackExtraEntity(e);
    }

    private static boolean contains(AxisAlignedBB outer, AxisAlignedBB inner) {
        return inner.minX >= outer.minX && inner.maxX <= outer.maxX
                && inner.minY >= outer.minY && inner.maxY <= outer.maxY
                && inner.minZ >= outer.minZ && inner.maxZ <= outer.maxZ;
    }

    private static void evictStale(long now) {
        Iterator<Entry> it = CACHE.values().iterator();
        while (it.hasNext()) {
            if (now - it.next().tick >= EVICT_AGE_TICKS) it.remove();
        }
    }
}
