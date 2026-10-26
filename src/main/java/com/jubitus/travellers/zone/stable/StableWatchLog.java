package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.init.Items;
import net.minecraft.world.WorldServer;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-Stable running ledger of everything that changes its pool - taming, saddling, births,
 * foals growing up, horses entering / leaving, plus a periodic head-count. Every line is tagged
 * {@code [L]} (a loaded palfrenier did it) or {@code [V]} (the offscreen timer did it), so a
 * long-run test can be checked for the two staying in step just by reading the log.
 *
 * <p>Always kept in a small in-memory ring per zone (readable with {@code /travellersdebug stable
 * log}); also appended to {@code logs/travellers-stable/<name>_<id8>.log} when
 * {@code STABLES.debugLog} is on. Costs nothing when nothing happens.
 */
public final class StableWatchLog {

    /**
     * TAME/SADDLE/BORN/GROWN/INTAKE/LEAVE/SNAPSHOT are pool changes (the original set). HAND is a
     * palfrenier-lifecycle event (arrived, work/break, bed, combat, gave up a target, snap-hopped);
     * ZONE is a stable-level transition (loaded/unloaded, which scheduler drives the pool, a strayed
     * horse contained); HELD notes a chore a cap deliberately blocked.
     */
    public enum Kind {TAME, SADDLE, HEAL, BORN, GROWN, INTAKE, LEAVE, SNAPSHOT, HAND, ZONE, HELD}

    private static final int RING_SIZE = 400;
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private static final Map<UUID, Deque<String>> RINGS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_SNAPSHOT = new ConcurrentHashMap<>();
    /** One buffered writer per stable, opened lazily while {@code STABLES.debugLog} is on and closed
     * on zone deletion, server stop, or the flag being turned off - much cheaper than reopening the
     * file for every line a busy stable produces. */
    private static final Map<UUID, Writer> WRITERS = new ConcurrentHashMap<>();

    private StableWatchLog() {
    }

    public static void record(WorldServer world, PlayerZoneRecord zone, Kind kind, String detail, boolean live) {
        String line = String.format(Locale.ROOT, "%9d %s [%s] %-8s %s",
                world.getTotalWorldTime(), CLOCK.format(new Date()), live ? "L" : "V", kind,
                detail == null ? "" : detail);

        Deque<String> ring = RINGS.computeIfAbsent(zone.id, i -> new ArrayDeque<>());
        synchronized (ring) {
            ring.addLast(line);
            while (ring.size() > RING_SIZE) ring.removeFirst();
        }
        if (TravellersConfig.STABLES.debugLog) appendFile(world, zone, line);
        else closeWriter(zone.id); // flag turned off - release any open handle
    }

    /** Convenience for a palfrenier-lifecycle ({@link Kind#HAND}) line. */
    public static void hand(WorldServer world, PlayerZoneRecord zone, String worker, String event) {
        record(world, zone, Kind.HAND, (worker == null ? "?" : worker) + ": " + event, true);
    }

    /** Convenience for a stable-level ({@link Kind#ZONE}) transition line. */
    public static void zone(WorldServer world, PlayerZoneRecord zone, String event) {
        record(world, zone, Kind.ZONE, event, true);
    }

    /** Writes a head-count line at most once every {@code STABLES.debugLogSnapshotIntervalTicks}. */
    public static void maybeSnapshot(WorldServer world, PlayerZoneRecord zone, StableZoneData data,
                                     boolean loaded, int handsLoaded, int handsVirtual) {
        long now = world.getTotalWorldTime();
        Long last = LAST_SNAPSHOT.get(zone.id);
        if (last != null && now - last < TravellersConfig.STABLES.debugLogSnapshotIntervalTicks) return;
        LAST_SNAPSHOT.put(zone.id, now);
        record(world, zone, Kind.SNAPSHOT, summary(data, loaded, handsLoaded, handsVirtual), loaded);
    }

    /** One-line state of a pool - shared by the snapshot line, {@code /travellersdebug stable list}
     * and the worldmap hover label so they can never disagree. */
    public static String summary(StableZoneData data, boolean loaded, int handsLoaded, int handsVirtual) {
        int tamed = 0, saddled = 0, foals = 0, hurt = 0, live = 0, dormant = 0;
        for (StableHorseEntry e : data.pool) {
            if (e.tamed) tamed++;
            if (StableWork.isHurt(e)) hurt++;
            if (e.saddled) saddled++;
            if (e.growingAge < 0) foals++;
            if (e.liveEntityUUID != null) live++;
            else dormant++;
        }
        StableChestMirror m = data.chestMirror;
        return String.format(Locale.ROOT,
                "%s pool=%d (tamed=%d saddled=%d foals=%d hurt=%d live=%d dormant=%d) hands=L%d/V%d chest[wheat=%d hay=%d sugar=%d apple=%d saddle=%d gcarrot=%d gapple=%d]",
                loaded ? "LOADED" : "virtual", data.pool.size(), tamed, saddled, foals, hurt, live, dormant,
                handsLoaded, handsVirtual, m.available(Items.WHEAT), m.available(StableWork.HAY_BALE),
                m.available(Items.SUGAR), m.available(Items.APPLE), m.available(Items.SADDLE),
                m.available(Items.GOLDEN_CARROT), m.available(Items.GOLDEN_APPLE));
    }

    public static List<String> tail(UUID zoneId, int n) {
        Deque<String> ring = RINGS.get(zoneId);
        if (ring == null) return new ArrayList<>();
        synchronized (ring) {
            List<String> all = new ArrayList<>(ring);
            int from = Math.max(0, all.size() - n);
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    public static void forget(UUID zoneId) {
        RINGS.remove(zoneId);
        LAST_SNAPSHOT.remove(zoneId);
        closeWriter(zoneId);
    }

    /** Flush and close every open stable log file - call on server stop. */
    public static void closeAll() {
        for (UUID id : new ArrayList<>(WRITERS.keySet())) closeWriter(id);
    }

    public static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    private static void appendFile(WorldServer world, PlayerZoneRecord zone, String line) {
        try {
            Writer w = WRITERS.get(zone.id);
            if (w == null) {
                File dir = new File(world.getMinecraftServer().getDataDirectory(), "logs/travellers-stable");
                if (!dir.exists() && !dir.mkdirs()) return;
                String name = (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;
                name = name.replaceAll("[^A-Za-z0-9_-]", "_");
                w = new BufferedWriter(new FileWriter(new File(dir, name + "_" + shortId(zone.id) + ".log"), true));
                WRITERS.put(zone.id, w);
            }
            w.write(line);
            w.write('\n');
            w.flush();
        } catch (IOException | RuntimeException ignored) {
            // Best-effort - a debug log must never break the tick.
            closeWriter(zone.id);
        }
    }

    private static void closeWriter(UUID zoneId) {
        Writer w = WRITERS.remove(zoneId);
        if (w == null) return;
        try {
            w.close();
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
