package com.jubitus.travellers.debug.trace;

import com.jubitus.travellers.debug.profile.MovementProfiler;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Diagnostic only: turns per-tick "horse wedged" readings into episodes, so a flickering wedge (pushed one tick,
 * idle the next) still reads as one blockage. Each episode of at least {@link #MIN_TICKS} is classified once
 * ({@link MountObstacle}), traced, and counted by the profiler. Runs only while a profile or a trace is active.
 */
public final class MountWedgeWatch {

    private MountWedgeWatch() {
    }

    /** Shorter contacts are ordinary brushes, not wedges. */
    private static final int MIN_TICKS = 10;
    /** An episode survives this many ticks without a wedged reading (the follow AI's idle ticks). */
    private static final int GRACE_TICKS = 10;
    /** Moving this far from where the episode started is an escape. */
    private static final double ESCAPE_BLOCKS = 1.0D;

    private static final class Episode {
        final long startTick;
        final double x, z;
        long lastWedgedTick;
        MountObstacle obstacle;

        Episode(long tick, Entity body) {
            this.startTick = tick;
            this.lastWedgedTick = tick;
            this.x = body.posX;
            this.z = body.posZ;
        }
    }

    private static final Map<UUID, Episode> EPISODES = new HashMap<>();

    public static void tick(EntityTraveller rider) {
        if (rider.world.isRemote) return;
        UUID id = rider.getUniqueID();
        if (!MovementProfiler.isActive() && !TravellerTracer.isTraced(id)) {
            EPISODES.remove(id);
            return;
        }
        long now = rider.world.getTotalWorldTime();
        Episode ep = EPISODES.get(id);
        Entity horse = rider.isMountedOnBondedMount() ? rider.getRidingEntity() : null;
        if (horse == null) {
            if (ep != null) close(rider, ep, "dismounted");
            return;
        }
        boolean wedged = TravellerMountSupport.isMountWedged(rider);
        if (ep == null) {
            if (wedged) EPISODES.put(id, new Episode(now, horse));
            return;
        }
        double dx = horse.posX - ep.x;
        double dz = horse.posZ - ep.z;
        if (dx * dx + dz * dz >= ESCAPE_BLOCKS * ESCAPE_BLOCKS) {
            close(rider, ep, "escaped");
            return;
        }
        if (!wedged) {
            if (now - ep.lastWedgedTick > GRACE_TICKS) close(rider, ep, "stopped pushing");
            return;
        }
        ep.lastWedgedTick = now;
        if (ep.obstacle == null && now - ep.startTick + 1 >= MIN_TICKS) {
            ep.obstacle = MountObstacle.probe(horse, horse.rotationYaw);
            TravellerTracer.event(rider, "mount", "wedged " + MIN_TICKS + " ticks: " + ep.obstacle.describe());
        }
    }

    private static void close(EntityTraveller rider, Episode ep, String how) {
        EPISODES.remove(rider.getUniqueID());
        if (ep.obstacle == null) return;
        double seconds = (ep.lastWedgedTick - ep.startTick + 1) / 20.0D;
        TravellerTracer.event(rider, "mount", String.format(Locale.ROOT, "wedge ended after %.1fs (%s), %s",
                seconds, how, ep.obstacle.kind));
        MovementProfiler.wedge(rider, ep.obstacle.kind, seconds);
    }

    /** The obstacle of the rider's open wedge episode, or a fresh probe while mounted; null on foot. */
    public static String describeFor(EntityTraveller rider) {
        Episode ep = EPISODES.get(rider.getUniqueID());
        if (ep != null && ep.obstacle != null) return ep.obstacle.describe();
        Entity horse = rider.isMountedOnBondedMount() ? rider.getRidingEntity() : null;
        return horse == null ? null : MountObstacle.probe(horse, horse.rotationYaw).describe();
    }

    /** Drops episodes of riders no longer in this world. */
    public static void pruneStale(net.minecraft.world.WorldServer world) {
        EPISODES.keySet().removeIf(id -> world.getEntityFromUuid(id) == null);
    }
}
