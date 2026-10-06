package com.jubitus.travellers.debug.autotest;

import com.jubitus.travellers.debug.trace.MountObstacle;
import com.jubitus.travellers.debug.trace.TraceSession;
import com.jubitus.travellers.debug.trace.TravellerTracer;
import com.jubitus.travellers.ground.ColumnGround;
import com.jubitus.travellers.mount.MountMode;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Forest crossing scenario: mounted riders (solo riders and one mounted group) spawned at one point travel to
 * another through the real travel and follow AI; reports arrivals, time, wedges by obstacle, LEDs and recalls.
 * One run at a time; the caller forces the chunks of the route beforehand.
 */
public final class AutotestForest {

    private AutotestForest() {
    }

    /** Riders spawn this far apart. */
    private static final int SPACING = 3;
    /** Wedged readings this far apart belong to the same blockage (the follow AI idles every other tick). */
    private static final int WEDGE_GRACE_TICKS = 10;
    /** A blockage this long is classified and counted. */
    private static final int WEDGE_MIN_TICKS = 10;
    /** A horse moving this far in one tick was teleported (recalled). */
    private static final double RECALL_JUMP_BLOCKS = 4.0D;
    /** Followers stop at formation slots around the leader, not on the goal itself. */
    private static final int FOLLOWER_EXTRA_RADIUS = 8;

    private static final class Rider {
        final UUID id;
        final String role;
        final boolean mountedAtStart;
        final String traceFile;
        int arriveRadius;
        long arrivedTick = -1;
        int wedgedTicks;
        long spanStart = -1, lastWedged = -1;
        long longestSpan;
        boolean spanClassified;
        final Map<MountObstacle.Kind, Integer> obstacles = new EnumMap<>(MountObstacle.Kind.class);
        int leds;
        int recalls;
        MountMode lastMode;
        double horseX = Double.NaN, horseZ;
        boolean vanished;

        Rider(UUID id, String role, boolean mountedAtStart, String traceFile) {
            this.id = id;
            this.role = role;
            this.mountedAtStart = mountedAtStart;
            this.traceFile = traceFile;
        }
    }

    private static final class Run {
        final String label;
        final BlockPos goal;
        final long startTick;
        final long endTick;
        final List<Rider> riders = new ArrayList<>();

        Run(String label, BlockPos goal, long startTick, long endTick) {
            this.label = label;
            this.goal = goal;
            this.startTick = startTick;
            this.endTick = endTick;
        }
    }

    @Nullable private static Run run;
    private static String lastResult = "no forest run yet";

    public static String start(WorldServer ws, int x1, int z1, int x2, int z2, int solo, int followers, int seconds) {
        if (run != null) return "forest: a run is already going (" + run.label + ")";
        int gy = ColumnGround.terrain(ws, x2, z2, false);
        if (gy == ColumnGround.NONE) return "forest: goal column not loaded or no ground at " + x2 + " " + z2;
        long now = ws.getTotalWorldTime();
        String label = String.format(Locale.ROOT, "%d,%d->%d,%d", x1, z1, x2, z2);
        Run r = new Run(label, new BlockPos(x2, gy, z2), now, now + seconds * 20L);

        double dx = x2 - x1, dz = z2 - z1;
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        // Riders line up across the route, centred on the start point.
        double px = -dz / len, pz = dx / len;
        int total = solo + (followers > 0 ? followers + 1 : 0);
        List<String> failures = new ArrayList<>();
        EntityTraveller leader = null;
        for (int i = 0; i < total; i++) {
            double off = (i - (total - 1) / 2.0D) * SPACING;
            int x = (int) Math.floor(x1 + px * off);
            int z = (int) Math.floor(z1 + pz * off);
            int y = ColumnGround.NONE;
            // Slide along the route until dry ground is found.
            for (int step = 0; step <= 4 && y == ColumnGround.NONE; step++) {
                int sx = (int) Math.floor(x + dx / len * step), sz = (int) Math.floor(z + dz / len * step);
                y = ColumnGround.terrain(ws, sx, sz, false);
                if (y != ColumnGround.NONE) {
                    x = sx;
                    z = sz;
                }
            }
            if (y == ColumnGround.NONE) {
                failures.add("no ground at " + x + " " + z);
                continue;
            }
            boolean follower = i >= solo + 1 && leader != null;
            EntityTraveller unit = follower ? new EntityTraveller(ws) : new EntityTravellerWorker(ws);
            unit.setDebugPathTest(true);
            unit.setLocationAndAngles(x + 0.5D, y, z + 0.5D, 0.0F, 0.0F);
            unit.onInitialSpawn(ws.getDifficultyForLocation(new BlockPos(x, y, z)), null);
            if (follower) unit.setGroupLeaderUUID(leader.getUniqueID());
            if (!ws.spawnEntity(unit)) {
                failures.add("spawn refused at " + x + " " + z);
                continue;
            }
            if (!follower) {
                unit.getTravelCommon().routeTo(r.goal);
            }
            boolean mounted = TravellerMountSupport.bondNewMount(unit);
            TraceSession trace = TravellerTracer.start(unit, TravellerTracer.getDefaultChannels());
            String role = follower ? "follower" : i == solo ? (followers > 0 ? "leader" : "solo") : "solo";
            if (i == solo && followers > 0) leader = unit;
            Rider rider = new Rider(unit.getUniqueID(), role, mounted,
                    trace == null ? "-" : trace.getFile().getName());
            rider.arriveRadius = Math.max(3, unit.arrivalRadiusBlocks(r.goal)) + (follower ? FOLLOWER_EXTRA_RADIUS : 0);
            r.riders.add(rider);
        }
        if (r.riders.isEmpty()) return "forest: nothing spawned (" + String.join("; ", failures) + ")";
        run = r;
        String reply = "forest started " + label + ": " + r.riders.size() + " riders, budget " + seconds + " s"
                + (failures.isEmpty() ? "" : ", skipped: " + String.join("; ", failures));
        AutotestReport.append("forest start", Collections.singletonList(reply));
        return reply;
    }

    public static String status(WorldServer ws) {
        if (run == null) return "forest: done | " + lastResult;
        int arrived = 0;
        for (Rider r : run.riders) if (r.arrivedTick >= 0) arrived++;
        return String.format(Locale.ROOT, "forest: running %s, %d/%d arrived, %d s left", run.label, arrived,
                run.riders.size(), (run.endTick - ws.getTotalWorldTime()) / 20L);
    }

    public static String stop(WorldServer ws) {
        if (run == null) return "forest: nothing running";
        finish(ws, "stopped");
        return "forest: stopped | " + lastResult;
    }

    /** World tick (overworld, END phase). */
    public static void tick(WorldServer ws) {
        Run r = run;
        if (r == null) return;
        long now = ws.getTotalWorldTime();
        boolean allDone = true;
        for (Rider rider : r.riders) {
            if (rider.vanished) continue;
            Entity e = ws.getEntityFromUuid(rider.id);
            if (!(e instanceof EntityTraveller) || e.isDead) {
                rider.vanished = true;
                continue;
            }
            EntityTraveller unit = (EntityTraveller) e;
            if (rider.arrivedTick < 0) {
                double dx = unit.posX - (r.goal.getX() + 0.5D);
                double dz = unit.posZ - (r.goal.getZ() + 0.5D);
                if (dx * dx + dz * dz <= rider.arriveRadius * rider.arriveRadius) rider.arrivedTick = now;
            }
            if (rider.arrivedTick < 0) allDone = false;
            observe(unit, rider, now);
        }
        if (allDone || now >= r.endTick) finish(ws, allDone ? "every rider arrived or vanished" : "time budget reached");
    }

    private static void observe(EntityTraveller unit, Rider rider, long now) {
        MountMode mode = unit.mountState.getMountMode();
        if (rider.lastMode != null && rider.lastMode != MountMode.LED && mode == MountMode.LED) rider.leds++;
        rider.lastMode = mode;

        EntityHorse horse = TravellerMountSupport.getBondedHorse(unit);
        if (horse != null) {
            if (!Double.isNaN(rider.horseX)) {
                double hx = horse.posX - rider.horseX, hz = horse.posZ - rider.horseZ;
                if (hx * hx + hz * hz > RECALL_JUMP_BLOCKS * RECALL_JUMP_BLOCKS) rider.recalls++;
            }
            rider.horseX = horse.posX;
            rider.horseZ = horse.posZ;
        }

        if (rider.arrivedTick >= 0) return;
        if (TravellerMountSupport.isMountWedged(unit)) {
            rider.wedgedTicks++;
            if (rider.spanStart < 0 || now - rider.lastWedged > WEDGE_GRACE_TICKS) {
                rider.spanStart = now;
                rider.spanClassified = false;
            }
            rider.lastWedged = now;
            long span = now - rider.spanStart + 1;
            rider.longestSpan = Math.max(rider.longestSpan, span);
            if (!rider.spanClassified && span >= WEDGE_MIN_TICKS && unit.getRidingEntity() != null) {
                rider.spanClassified = true;
                Entity body = unit.getRidingEntity();
                rider.obstacles.merge(MountObstacle.probe(body, body.rotationYaw).kind, 1, Integer::sum);
            }
        }
    }

    private static void finish(WorldServer ws, String why) {
        Run r = run;
        run = null;
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        int arrived = 0, leds = 0, recalls = 0, notMounted = 0;
        long wedged = 0, longest = 0;
        Map<MountObstacle.Kind, Integer> obstacles = new EnumMap<>(MountObstacle.Kind.class);
        for (Rider rider : r.riders) {
            boolean ok = rider.arrivedTick >= 0;
            if (ok) {
                arrived++;
                times.add((rider.arrivedTick - r.startTick) / 20L);
            }
            if (!rider.mountedAtStart) notMounted++;
            leds += rider.leds;
            recalls += rider.recalls;
            wedged += rider.wedgedTicks;
            longest = Math.max(longest, rider.longestSpan);
            for (Map.Entry<MountObstacle.Kind, Integer> e : rider.obstacles.entrySet()) {
                obstacles.merge(e.getKey(), e.getValue(), Integer::sum);
            }
            lines.add(String.format(Locale.ROOT, "%s %s%s: %s, wedged %d ticks (longest %.1f s), obstacles %s, "
                            + "LED %d, recalls %d, trace %s",
                    ok ? "PASS" : "FAIL", rider.role, rider.mountedAtStart ? "" : " (no horse at start)",
                    ok ? "arrived in " + (rider.arrivedTick - r.startTick) / 20L + " s"
                            : rider.vanished ? "vanished" : "not arrived",
                    rider.wedgedTicks, rider.longestSpan / 20.0D, rider.obstacles, rider.leds, rider.recalls,
                    rider.traceFile));
            removeRider(ws, rider);
        }
        Collections.sort(times);
        String median = times.isEmpty() ? "-" : times.get(times.size() / 2) + " s";
        String max = times.isEmpty() ? "-" : times.get(times.size() - 1) + " s";
        lastResult = String.format(Locale.ROOT, "%s: %d/%d arrived (%s), median %s, slowest %s, wedged %d ticks, "
                        + "longest wedge %.1f s, obstacles %s, LED %d, recalls %d%s",
                r.label, arrived, r.riders.size(), why, median, max, wedged, longest / 20.0D, obstacles, leds,
                recalls, notMounted > 0 ? ", " + notMounted + " without a horse at start" : "");
        lines.add(0, "RESULT " + lastResult);
        AutotestReport.append("forest " + r.label, lines);
    }

    private static void removeRider(WorldServer ws, Rider rider) {
        TravellerTracer.stop(ws.getMinecraftServer(), rider.id, "forest scenario ended");
        Entity e = ws.getEntityFromUuid(rider.id);
        if (!(e instanceof EntityTraveller)) return;
        EntityHorse horse = TravellerMountSupport.getBondedHorse((EntityTraveller) e);
        e.dismountRidingEntity();
        e.setDead();
        if (horse != null) horse.setDead();
    }
}
