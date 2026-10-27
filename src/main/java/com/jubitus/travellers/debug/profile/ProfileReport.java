package com.jubitus.travellers.debug.profile;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns a {@link ProfileSession} into a short, human-readable text file. */
final class ProfileReport {

    private ProfileReport() {
    }

    @Nullable
    static File write(MinecraftServer server, ProfileSession s, long now, boolean fin) {
        File dir = new File(server.getDataDirectory(), "logs/travellers-profile");
        if (!dir.exists() && !dir.mkdirs()) {
            FMLLog.log(Level.WARN, "[Travellers/Profile] could not create %s", dir.getAbsolutePath());
            return null;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File file = new File(dir, "profile_" + stamp + (fin ? "" : "_partial") + ".txt");
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            w.write(build(s, now, fin));
        } catch (IOException ex) {
            FMLLog.log(Level.WARN, "[Travellers/Profile] could not write %s: %s", file.getName(), ex.getMessage());
            return null;
        }
        File metrics = new File(dir, file.getName().replace(".txt", ".metrics"));
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(metrics.toPath()), StandardCharsets.UTF_8)) {
            w.write(metrics(s, now));
        } catch (IOException ex) {
            FMLLog.log(Level.WARN, "[Travellers/Profile] could not write %s: %s", metrics.getName(), ex.getMessage());
        }
        return file;
    }

    private static String build(ProfileSession s, long now, boolean fin) {
        StringBuilder sb = new StringBuilder(8192);
        long ticks = Math.max(1L, now - s.startTick);
        double seconds = ticks / 20.0D;
        long realSeconds = (System.currentTimeMillis() - s.startMillis) / 1000L;

        sb.append("=== Travellers movement profile (").append(fin ? "final" : "intermediate").append(") ===\n");
        sb.append("Started ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(s.startMillis)))
                .append(" | game time ").append(duration((long) seconds))
                .append(" | real time ").append(duration(realSeconds)).append('\n');
        sb.append("Stall = trying to move but under 0.3 blocks/s for at least ")
                .append(f1(TravellersConfig.TRACE.profileStallSeconds)).append(" s. Follower lagging = more than ")
                .append(f1(TravellersConfig.TRACE.profileFollowerLagBlocks)).append(" blocks from its formation slot.\n");

        population(sb, s, seconds);
        efficiency(sb, s);
        stalls(sb, s, seconds);
        anomalies(sb, s);
        led(sb, s);
        wedges(sb, s);
        scheduler(sb, s, ticks);
        paths(sb, s, ticks);
        raidScans(sb, s, ticks);
        combat(sb, s, ticks);
        villageVisits(sb, s, ticks);
        boundary(sb, s);
        groups(sb, s);
        hotspots(sb, s);
        worst(sb, s);
        return sb.toString();
    }

    /** Flat key=value counterpart of the report, read by the automated server tests to compare runs. */
    private static String metrics(ProfileSession s, long now) {
        StringBuilder sb = new StringBuilder(2048);
        long ticks = Math.max(1L, now - s.startTick);
        double loaded = 0.0D;
        for (double v : s.presence.values()) loaded += v;
        metric(sb, "game_seconds", ticks / 20.0D);
        metric(sb, "loaded_unit_seconds", loaded);
        metric(sb, "units_tracked", s.states.size());
        metric(sb, "materialized", s.materialized);
        metric(sb, "materialize_refused", s.materializeRefused);
        metric(sb, "stall_episodes", s.stallEpisodes.count);
        metric(sb, "stall_seconds", s.stalledSeconds);
        metric(sb, "stall_max_seconds", s.stallEpisodes.max);
        metric(sb, "stalls_60s_plus", s.stallHistogram[3]);
        metric(sb, "stalls_after_materialize", s.stallsAfterMaterialize);
        for (MovementAnomaly a : MovementAnomaly.values()) {
            Long n = s.anomalies.get(a);
            metric(sb, "anomaly." + a.name(), n == null ? 0L : n);
        }
        for (MovementAnomaly.LedReason r : MovementAnomaly.LedReason.values()) {
            Long n = s.ledStarts.get(r);
            metric(sb, "led." + r.name(), n == null ? 0L : n);
        }
        long wedgeCount = 0;
        double wedgeSeconds = 0.0D;
        for (Map.Entry<com.jubitus.travellers.debug.trace.MountObstacle.Kind, ProfileSession.Tally> e : s.wedges.entrySet()) {
            wedgeCount += e.getValue().count;
            wedgeSeconds += e.getValue().sum;
        }
        for (com.jubitus.travellers.debug.trace.MountObstacle.Kind k : com.jubitus.travellers.debug.trace.MountObstacle.Kind.values()) {
            ProfileSession.Tally t = s.wedges.get(k);
            metric(sb, "wedge." + k.name(), t == null ? 0L : t.count);
        }
        metric(sb, "wedge_episodes", wedgeCount);
        metric(sb, "wedge_seconds", wedgeSeconds);
        metric(sb, "wedge_over_2s", s.wedgesOver2s);
        long mountedSearches = 0, mountedFailed = 0;
        for (Map.Entry<String, ProfileSession.PathStat> e : s.paths.entrySet()) {
            if (!e.getKey().contains("/ mounted") || e.getKey().contains("probe")) continue;
            mountedSearches += e.getValue().count;
            mountedFailed += e.getValue().nulls + e.getValue().partials;
        }
        metric(sb, "path_mounted_searches", mountedSearches);
        metric(sb, "path_mounted_failed", mountedFailed);
        for (Map.Entry<String, ProfileSession.Motion> e : s.travelMotion.entrySet()) {
            ProfileSession.Motion m = e.getValue();
            if (m.walked > 0) metric(sb, "travel_efficiency." + e.getKey().replace(" / ", ".").replace(' ', '_'), m.progress / m.walked);
        }
        metric(sb, "path_ms_per_tick", s.pathTotalNanos / 1e6 / ticks);
        metric(sb, "path_worst_tick_ms", s.pathMaxTickNanos / 1e6);
        metric(sb, "travel_budget_full_ticks", s.travelFullTicks);
        return sb.toString();
    }

    private static void metric(StringBuilder sb, String key, double value) {
        sb.append(key).append('=').append(String.format(Locale.ROOT, "%.3f", value)).append('\n');
    }

    private static void population(StringBuilder sb, ProfileSession s, double seconds) {
        header(sb, "Loaded population (average number loaded at once)");
        double total = 0.0D;
        for (double v : s.presence.values()) total += v;
        sb.append("  total ").append(f1(total / seconds)).append('\n');
        for (Map.Entry<String, Double> e : s.presence.entrySet()) {
            sb.append("  ").append(pad(e.getKey(), 34)).append(f1(e.getValue() / seconds)).append('\n');
        }
    }

    private static void efficiency(StringBuilder sb, ProfileSession s) {
        header(sb, "Movement efficiency");
        sb.append("  travel efficiency = blocks gained toward the goal / blocks walked (1.00 = straight line)\n");
        for (Map.Entry<String, ProfileSession.Motion> e : s.travelMotion.entrySet()) {
            ProfileSession.Motion m = e.getValue();
            sb.append("  ").append(pad(e.getKey(), 28))
                    .append("efficiency ").append(m.walked > 0 ? f2(m.progress / m.walked) : "-")
                    .append(" | progress ").append(m.seconds > 0 ? f2(m.progress / m.seconds) : "-").append(" b/s")
                    .append(" | speed ").append(m.seconds > 0 ? f2(m.walked / m.seconds) : "-").append(" b/s")
                    .append(" | sampled ").append(duration((long) m.seconds)).append('\n');
        }
        for (Map.Entry<String, ProfileSession.FollowMotion> e : s.followMotion.entrySet()) {
            ProfileSession.FollowMotion m = e.getValue();
            sb.append("  ").append(pad(e.getKey(), 28))
                    .append("avg slot distance ").append(m.slotSamples > 0 ? f1(m.slotSum / m.slotSamples) : "-")
                    .append(" b | lagging ").append(pct(m.lagging, m.slotSamples))
                    .append(" | speed ").append(m.seconds > 0 ? f2(m.walked / m.seconds) : "-").append(" b/s")
                    .append(" | sampled ").append(duration((long) m.seconds)).append('\n');
        }
    }

    private static void stalls(StringBuilder sb, ProfileSession s, double seconds) {
        header(sb, "Stalls");
        List<ProfileSession.Episode> open = s.openEpisodes();
        double loaded = 0.0D;
        for (double v : s.presence.values()) loaded += v;
        ProfileSession.Tally t = s.stallEpisodes;
        sb.append("  episodes ").append(t.count);
        if (!open.isEmpty()) sb.append(" (+").append(open.size()).append(" ongoing)");
        sb.append(" | avg ").append(f1(t.avg())).append(" s | max ").append(f1(t.max)).append(" s")
                .append(" | total ").append(duration((long) s.stalledSeconds))
                .append(" = ").append(loaded > 0 ? f1(100.0D * s.stalledSeconds / loaded) : "0").append(" % of loaded time\n");
        sb.append("  length: <5 s ").append(s.stallHistogram[0]).append(" | 5-15 s ").append(s.stallHistogram[1])
                .append(" | 15-60 s ").append(s.stallHistogram[2]).append(" | 60 s+ ").append(s.stallHistogram[3]).append('\n');
        sb.append("  started within 30 s of materializing: ").append(s.stallsAfterMaterialize).append('\n');
        if (t.count == 0) return;
        for (Map.Entry<String, Map<String, ProfileSession.Tally>> dim : s.stallBy.entrySet()) {
            sb.append("  by ").append(dim.getKey()).append(":\n");
            List<Map.Entry<String, ProfileSession.Tally>> rows = new ArrayList<>(dim.getValue().entrySet());
            rows.sort((a, b) -> Double.compare(b.getValue().sum, a.getValue().sum));
            int shown = 0;
            for (Map.Entry<String, ProfileSession.Tally> r : rows) {
                if (shown++ >= 8) {
                    sb.append("      ... ").append(rows.size() - 8).append(" more\n");
                    break;
                }
                ProfileSession.Tally v = r.getValue();
                sb.append("      ").append(pad(r.getKey(), 26))
                        .append(pad(v.count + " ep", 9))
                        .append(pad(pct(v.count, t.count), 8))
                        .append("total ").append(pad(duration((long) v.sum), 9))
                        .append("avg ").append(f1(v.avg())).append(" s\n");
            }
        }
    }

    private static void anomalies(StringBuilder sb, ProfileSession s) {
        header(sb, "Rescues and path trouble (counts, split by unit / mount state)");
        if (s.anomalies.isEmpty()) {
            sb.append("  none\n");
            return;
        }
        for (MovementAnomaly a : MovementAnomaly.values()) {
            Long n = s.anomalies.get(a);
            if (n == null) continue;
            sb.append("  ").append(pad(a.family, 8)).append(pad(a.label, 46)).append(pad(String.valueOf(n), 7));
            Map<String, Integer> split = s.anomalySplit.get(a);
            if (split != null) sb.append(joinCounts(split));
            sb.append('\n');
        }
    }

    private static void led(StringBuilder sb, ProfileSession s) {
        header(sb, "Dismount to lead the horse (LED)");
        if (s.ledStarts.isEmpty()) {
            sb.append("  none\n");
            return;
        }
        for (MovementAnomaly.LedReason r : MovementAnomaly.LedReason.values()) {
            Long n = s.ledStarts.get(r);
            if (n == null) continue;
            ProfileSession.Tally d = s.ledDurations.get(r);
            sb.append("  ").append(pad(r.label, 34)).append(pad(n + "x", 8));
            if (d != null) {
                sb.append("remounted ").append(d.count).append("x, led avg ").append(f1(d.avg()))
                        .append(" s, max ").append(f1(d.max)).append(" s");
            } else {
                sb.append("never remounted during the session");
            }
            sb.append('\n');
        }
    }

    private static void wedges(StringBuilder sb, ProfileSession s) {
        header(sb, "Mounted wedges (horse pressed against something, 0.5 s or more), by obstacle");
        if (s.wedges.isEmpty()) {
            sb.append("  none\n");
            return;
        }
        for (Map.Entry<com.jubitus.travellers.debug.trace.MountObstacle.Kind, ProfileSession.Tally> e : s.wedges.entrySet()) {
            ProfileSession.Tally t = e.getValue();
            sb.append("  ").append(pad(e.getKey().name(), 20)).append(pad(t.count + "x", 8))
                    .append("avg ").append(f1(t.avg())).append(" s, max ").append(f1(t.max)).append(" s\n");
        }
    }

    private static void scheduler(StringBuilder sb, ProfileSession s, long ticks) {
        header(sb, "Path scheduler");
        String[] prio = {"normal", "stuck-ish", "urgent"};
        long granted = 0;
        for (long[] row : s.travelSchedule) granted += row[MovementProfiler.TRAVEL_GRANTED];
        sb.append("  Travel (PathBudget travel row): ").append(granted).append(" granted, ")
                .append(f2((double) granted / ticks)).append(" per tick. Budget full on ")
                .append(s.travelFullTicks).append(" ticks (").append(pct(s.travelFullTicks, ticks)).append(" of ticks)\n");
        sb.append("    'not its turn' is the normal 1-in-N stagger, not a shortage.\n");
        for (int p = 0; p < 3; p++) {
            long[] r = s.travelSchedule[p];
            long total = r[0] + r[1] + r[2] + r[3];
            if (total == 0) continue;
            sb.append("    ").append(pad(prio[p], 11)).append(pad(total + " asks", 13))
                    .append("granted ").append(pad(pct(r[0], total), 8))
                    .append("not its turn ").append(pad(pct(r[1], total), 8))
                    .append("normal full ").append(pad(pct(r[2], total), 8))
                    .append("urgent full ").append(pct(r[3], total)).append('\n');
        }
        long followTotal = 0;
        for (long v : s.followSchedule.values()) followTotal += v;
        sb.append("  Follow (PathScheduler): ").append(followTotal).append(" asks. Global/mounted cap hit on ")
                .append(s.followCapTicks).append(" ticks (").append(pct(s.followCapTicks, ticks)).append(" of ticks)\n");
        for (Map.Entry<String, Long> e : s.followSchedule.entrySet()) {
            sb.append("    ").append(pad(e.getKey(), 44)).append(pad(String.valueOf(e.getValue()), 9))
                    .append(pct(e.getValue(), followTotal)).append('\n');
        }
    }

    private static void raidScans(StringBuilder sb, ProfileSession s, long ticks) {
        header(sb, "Raid target scans (RaiderAttackTargetAI)");
        long n = Math.max(1L, s.raidScans);
        sb.append("  scans ").append(s.raidScans).append(" (").append(f2((double) s.raidScans / ticks))
                .append(" per tick) | found a target ").append(pct(s.raidScanFound, n)).append('\n');
        sb.append("  per scan: ").append(f1((double) s.raidScanInBox / n)).append(" entities in box, ")
                .append(f1((double) s.raidScanPreferred / n)).append(" preferred candidates, ")
                .append(f1((double) s.raidScanRays / n)).append(" rays\n");
        sb.append("  cost: ").append(f2(s.raidScanNanos / 1e6 / ticks)).append(" ms per tick, ")
                .append(f2(s.raidScanNanos / 1e6 / n)).append(" ms per scan, worst ")
                .append(f2(s.raidScanMaxNanos / 1e6)).append(" ms\n");
    }

    private static void combat(StringBuilder sb, ProfileSession s, long ticks) {
        header(sb, "Combat targeting");
        long total = 0;
        for (long v : s.targetChanges.values()) total += v;
        sb.append("  target decisions ").append(total).append(" (adopted = had none, refused = lock held, others = switch reason)\n");
        for (Map.Entry<String, Long> e : s.targetChanges.entrySet()) {
            sb.append("    ").append(pad(e.getKey(), 24)).append(pad(String.valueOf(e.getValue()), 9))
                    .append(pct(e.getValue(), Math.max(1L, total))).append('\n');
        }
        long drops = 0;
        for (long v : s.targetDrops.values()) drops += v;
        sb.append("  live targets dropped ").append(drops).append(" (target still alive, unit left with no target)\n");
        for (Map.Entry<String, Long> e : s.targetDrops.entrySet()) {
            sb.append("    ").append(pad(e.getKey(), 40)).append(pad(String.valueOf(e.getValue()), 9))
                    .append(pct(e.getValue(), Math.max(1L, drops))).append('\n');
        }
        if (!s.tooFarDrops.isEmpty()) {
            sb.append("  too far, by who gave the target\n");
            List<Map.Entry<String, Long>> far = new ArrayList<>(s.tooFarDrops.entrySet());
            far.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> e : far) {
                sb.append("    ").append(pad(e.getKey(), 90)).append(e.getValue()).append('\n');
            }
        }
        if (!s.foreignTargetTasks.isEmpty()) {
            sb.append("  target tasks injected by other mods, removed\n");
            for (Map.Entry<String, Long> e : s.foreignTargetTasks.entrySet()) {
                sb.append("    ").append(pad(e.getKey(), 60)).append(e.getValue()).append('\n');
            }
        }
        sb.append("  help calls (allies pulled in per call)\n");
        for (Map.Entry<String, ProfileSession.Tally> e : s.helpCalls.entrySet()) {
            ProfileSession.Tally t = e.getValue();
            sb.append("    ").append(pad(e.getKey(), 24)).append(pad(String.valueOf(t.count), 9))
                    .append("avg ").append(f1(t.avg())).append(", max ").append((long) t.max).append('\n');
        }
        sb.append("  threat scans (world queries)\n");
        for (Map.Entry<String, ProfileSession.Tally> e : s.threatScanNanos.entrySet()) {
            ProfileSession.Tally n = e.getValue();
            ProfileSession.Tally f = s.threatScanFound.get(e.getKey());
            sb.append("    ").append(pad(e.getKey(), 24)).append(pad(String.valueOf(n.count), 9))
                    .append(f2((double) n.count / ticks)).append(" per tick, ")
                    .append(f1(f.avg())).append(" entities, ")
                    .append(f2(n.sum / 1e6 / ticks)).append(" ms per tick, worst ")
                    .append(f2(n.max / 1e6)).append(" ms\n");
        }
    }

    private static void villageVisits(StringBuilder sb, ProfileSession s, long ticks) {
        header(sb, "Village visits (stays started; recruitable = travellers and free workers)");
        double hours = ticks / 72000.0D;
        long n = s.visits;
        sb.append("  stays ").append(n).append(" (").append(f1(n / hours)).append(" per hour) | ")
                .append(joinLongs(s.visitsByKind)).append('\n');
        sb.append("  offscreen ").append(pct(s.visitsVirtual, n)).append(" | as group follower ")
                .append(pct(s.visitsFollower, n)).append(" | mounted ").append(pct(s.visitsMounted, n))
                .append(" | same unit, same village again ").append(pct(s.visitsRepeated, n)).append('\n');
        for (Map.Entry<String, ProfileSession.Tally> e : s.visitRepeatGapSeconds.entrySet()) {
            sb.append("    repeated by ").append(e.getKey()).append(": ").append(e.getValue().count)
                    .append(", ").append(f1(e.getValue().avg())).append(" s after its previous start on average\n");
        }
        sb.append("  recruitable by culture (villages only): ").append(joinLongs(s.recruitableByCulture)).append('\n');
        if (!s.recruitAsks.isEmpty()) sb.append("  recruiters asked groups: ").append(joinLongs(s.recruitAsks)).append('\n');

        List<ProfileSession.VillageTraffic> villages = new ArrayList<>();
        for (ProfileSession.VillageTraffic t : s.villageTraffic.values()) if (!t.lone) villages.add(t);
        if (villages.isEmpty()) return;
        villages.sort((a, b) -> Long.compare(b.recruitable, a.recruitable));
        long median = villages.get(villages.size() / 2).recruitable;
        sb.append("  ").append(villages.size()).append(" villages visited | recruitable per village per hour: median ")
                .append(f1(median / hours)).append(", busiest ").append(f1(villages.get(0).recruitable / hours))
                .append(", quietest ").append(f1(villages.get(villages.size() - 1).recruitable / hours)).append('\n');
        for (int i = 0; i < Math.min(5, villages.size()); i++) {
            ProfileSession.VillageTraffic t = villages.get(i);
            sb.append("    ").append(pad(t.name, 32)).append(f1(t.recruitable / hours)).append(" recruitable/h, ")
                    .append(f1(t.raiders / hours)).append(" raiders/h\n");
        }
    }

    private static String joinLongs(Map<String, Long> counts) {
        List<Map.Entry<String, Long>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> r : rows) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(r.getKey()).append(' ').append(r.getValue());
        }
        return sb.toString();
    }

    private static void paths(StringBuilder sb, ProfileSession s, long ticks) {
        header(sb, "A* searches (traveller navigator)");
        sb.append("  average ").append(f2(s.pathTotalNanos / 1e6 / ticks)).append(" ms per tick")
                .append(" | worst single tick ").append(f1(s.pathMaxTickNanos / 1e6)).append(" ms")
                .append(" | ticks with a search ").append(pct(s.pathTicksWithSearch, ticks)).append('\n');
        sb.append("  ").append(pad("caller < its caller / mount", 96)).append(pad("count", 9)).append(pad("avg ms", 8))
                .append(pad("max ms", 8)).append(pad(">5ms", 7)).append(pad(">20ms", 7))
                .append(pad("no path", 9)).append("partial\n");
        List<Map.Entry<String, ProfileSession.PathStat>> rows = new ArrayList<>(s.paths.entrySet());
        rows.sort((a, b) -> Long.compare(b.getValue().nanos, a.getValue().nanos));
        for (Map.Entry<String, ProfileSession.PathStat> e : rows) {
            ProfileSession.PathStat p = e.getValue();
            sb.append("  ").append(pad(e.getKey(), 96)).append(pad(String.valueOf(p.count), 9))
                    .append(pad(f2(p.nanos / 1e6 / Math.max(1, p.count)), 8))
                    .append(pad(f1(p.maxNanos / 1e6), 8))
                    .append(pad(String.valueOf(p.over5ms), 7)).append(pad(String.valueOf(p.over20ms), 7))
                    .append(pad(pct(p.nulls, p.count), 9)).append(pct(p.partials, p.count)).append('\n');
        }
        if (!s.searchesAvoided.isEmpty()) {
            sb.append("  searches avoided by going direct\n");
            List<Map.Entry<String, Long>> avoided = new ArrayList<>(s.searchesAvoided.entrySet());
            avoided.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> e : avoided) {
                sb.append("  ").append(pad(e.getKey(), 96)).append(e.getValue()).append('\n');
            }
        }
    }

    private static void boundary(StringBuilder sb, ProfileSession s) {
        header(sb, "Virtual <-> loaded boundary");
        sb.append("  materialized ").append(s.materialized).append(" | spawn refused ").append(s.materializeRefused)
                .append(" | stalled within 30 s after materializing ").append(s.stallsAfterMaterialize).append('\n');
        if (!s.virtualizeReasons.isEmpty()) {
            sb.append("  virtualized: ");
            boolean first = true;
            for (Map.Entry<String, Long> e : s.virtualizeReasons.entrySet()) {
                if (!first) sb.append(" | ");
                first = false;
                sb.append(e.getKey()).append(' ').append(e.getValue());
            }
            sb.append('\n');
        }
    }

    private static void groups(StringBuilder sb, ProfileSession s) {
        header(sb, "Groups (followers of the same leader, worst 10 by lag)");
        if (s.groups.isEmpty()) {
            sb.append("  none\n");
            return;
        }
        List<ProfileSession.GroupStats> rows = new ArrayList<>(s.groups.values());
        rows.sort((a, b) -> Double.compare(lagShare(b), lagShare(a)));
        sb.append("  ").append(pad("leader", 32)).append(pad("seen", 6)).append(pad("mounted", 9))
                .append(pad("slot avg", 10)).append(pad("lagging", 9)).append(pad("stalls", 8))
                .append(pad("stall time", 12)).append(pad("rescues", 9)).append(pad("other", 7))
                .append("sched. denials\n");
        int shown = 0;
        for (ProfileSession.GroupStats g : rows) {
            if (shown++ >= 10) break;
            sb.append("  ").append(pad(g.label, 32)).append(pad(String.valueOf(g.members.size()), 6))
                    .append(pad(pct(g.mountedSamples, g.samples), 9))
                    .append(pad(g.slotSamples > 0 ? f1(g.slotSum / g.slotSamples) : "-", 10))
                    .append(pad(pct(g.lagging, g.slotSamples), 9))
                    .append(pad(String.valueOf(g.stallEpisodes), 8))
                    .append(pad(duration((long) g.stallSeconds), 12))
                    .append(pad(String.valueOf(g.rescues), 9))
                    .append(pad(String.valueOf(g.anomalies - g.rescues), 7))
                    .append(g.schedulerDenials).append('\n');
        }
    }

    private static double lagShare(ProfileSession.GroupStats g) {
        return g.slotSamples == 0 ? 0.0D : (double) g.lagging / g.slotSamples;
    }

    private static void hotspots(StringBuilder sb, ProfileSession s) {
        header(sb, "Hotspots (64x64 areas with the most trouble)");
        if (s.hotspots.isEmpty()) {
            sb.append("  none\n");
            return;
        }
        List<ProfileSession.Hotspot> rows = new ArrayList<>(s.hotspots.values());
        rows.sort((a, b) -> Double.compare(b.score(), a.score()));
        int shown = 0;
        for (ProfileSession.Hotspot h : rows) {
            if (shown++ >= 10) break;
            int x = h.cellX * 64 + 32;
            int z = h.cellZ * 64 + 32;
            sb.append("  ").append(pad("x " + x + " z " + z, 20)).append(pad(h.biome, 22))
                    .append("stall time ").append(pad(duration((long) h.stallSeconds), 9))
                    .append(joinCounts(h.kinds)).append('\n');
        }
    }

    private static void worst(StringBuilder sb, ProfileSession s) {
        header(sb, "Worst stalls");
        List<ProfileSession.Episode> all = new ArrayList<>(s.worst);
        for (ProfileSession.Episode ep : s.openEpisodes()) {
            if (!all.contains(ep)) all.add(ep);
        }
        all.sort((a, b) -> Double.compare(b.seconds, a.seconds));
        if (all.isEmpty()) {
            sb.append("  none\n");
            return;
        }
        int limit = TravellersConfig.TRACE.profileWorstCases;
        for (int i = 0; i < all.size() && i < limit; i++) {
            ProfileSession.Episode ep = all.get(i);
            sb.append(String.format(Locale.ROOT, "  %2d. %6.0f s  %s (%s, %s, %s)%s  ai=%s  %s, %s%s%s  at %d %d %d%s%s%n",
                    i + 1, ep.seconds, ep.name, ep.unit, ep.mount, ep.role,
                    ep.groupLabel == null ? "" : " of " + ep.groupLabel + " [" + ep.groupSize + "]",
                    ep.ai, ep.biome, ep.place,
                    ep.water ? ", in water" : "", ep.combat ? ", in combat" : "",
                    ep.x, ep.y, ep.z,
                    ep.afterMaterialize ? "  [just materialized]" : "",
                    ep.ongoing ? "  [ongoing]" : ""));
        }
    }

    // ---- formatting ----

    private static void header(StringBuilder sb, String title) {
        sb.append("\n-- ").append(title).append(" --\n");
    }

    private static String joinCounts(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> rows = new ArrayList<>(counts.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Integer> r : rows) {
            if (shown++ >= 5) break;
            if (sb.length() > 0) sb.append(" | ");
            sb.append(r.getKey()).append(' ').append(r.getValue());
        }
        return sb.toString();
    }

    static String duration(long seconds) {
        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        long sec = seconds % 60L;
        if (h > 0) return String.format(Locale.ROOT, "%dh%02dm%02ds", h, m, sec);
        if (m > 0) return String.format(Locale.ROOT, "%dm%02ds", m, sec);
        return sec + "s";
    }

    private static String pct(long part, long total) {
        return total <= 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0D * part / total);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f2(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s + ' ';
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }
}
