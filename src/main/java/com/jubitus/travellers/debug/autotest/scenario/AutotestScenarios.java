package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.debug.autotest.AutotestReport;
import com.jubitus.travellers.debug.autotest.AutotestZones;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Runs the scenario list one at a time on the overworld. A failed scenario is run once more on a different
 * line of ground before it is reported, to tell a flaky spot from a broken behaviour. One run at a time;
 * the caller forces the chunks of the arena beforehand.
 */
public final class AutotestScenarios {

    private static final int MAX_ATTEMPTS = 2;

    private AutotestScenarios() {
    }

    private static List<AutotestScenario> all() {
        List<AutotestScenario> list = new ArrayList<>(everything());
        list.removeIf(s -> s instanceof GoOrderScenario && ((GoOrderScenario) s).isRestartHalf());
        return list;
    }

    /** Every scenario, including the halves that only make sense around a server restart. */
    private static List<AutotestScenario> everything() {
        return Arrays.asList(
                new GoOrderScenario(GoOrderScenario.Mode.PLAIN),
                new GoOrderScenario(GoOrderScenario.Mode.VIRTUALIZED),
                new CatchupScenario(),
                new RecruitSpawnScenario(),
                new RaiderFightScenario(),
                new EquipScenario(ZoneType.BARRACK, true),
                new EquipScenario(ZoneType.BARRACK, false),
                new EquipScenario(ZoneType.STABLE, false),
                new ZoneTripScenario(false),
                new ZoneTripScenario(true),
                new BoatLandingScenario(),
                new GroupSuccessionScenario(GroupSuccessionScenario.Mode.LOADED),
                new GroupSuccessionScenario(GroupSuccessionScenario.Mode.VIRTUAL),
                new GroupSuccessionScenario(GroupSuccessionScenario.Mode.VANISHED),
                new WorkerSuccessionScenario(false),
                new WorkerSuccessionScenario(true),
                new GroupAnchorScenario(),
                new GroupRoadFileScenario(GroupRoadFileScenario.Mode.TRAVELLERS),
                new GroupRoadFileScenario(GroupRoadFileScenario.Mode.WORKERS),
                new GroupRoadFileScenario(GroupRoadFileScenario.Mode.REAPPEAR),
                new GoOrderScenario(GoOrderScenario.Mode.RESTART_SAVE),
                new GoOrderScenario(GoOrderScenario.Mode.RESTART_RESUME));
    }

    private static final class Active {
        final List<AutotestScenario> queue;
        final AutotestArena arena;
        final List<String> results = new ArrayList<>();
        final int total;
        int pass, fail, skip;
        @Nullable AutotestScenario current;
        int attempt;
        long startTick;
        int zoneMark;
        String firstFailure = "";

        Active(List<AutotestScenario> queue, AutotestArena arena) {
            this.queue = queue;
            this.arena = arena;
            this.total = queue.size();
        }
    }

    @Nullable private static Active active;
    private static String lastResult = "no scenario run yet";

    public static String names() {
        StringBuilder sb = new StringBuilder();
        for (AutotestScenario s : everything()) sb.append(sb.length() == 0 ? "" : ", ").append(s.name());
        return sb.toString();
    }

    /** {@code selector} is "all" or one scenario name. */
    public static String start(WorldServer ws, String selector, int centerX, int centerZ) {
        if (active != null) return "scenarios: a run is already going";
        List<AutotestScenario> queue = new ArrayList<>();
        boolean wantAll = selector.equalsIgnoreCase("all");
        for (AutotestScenario s : wantAll ? all() : everything()) {
            if (wantAll || s.name().equalsIgnoreCase(selector)) queue.add(s);
        }
        if (queue.isEmpty()) return "scenarios: unknown '" + selector + "' (known: " + names() + ")";
        active = new Active(queue, new AutotestArena(ws, centerX, centerZ));
        String reply = "scenarios started: " + queue.size() + " around " + centerX + " " + centerZ;
        AutotestReport.append("scenarios start", java.util.Collections.singletonList(reply));
        return reply;
    }

    public static String status(WorldServer ws) {
        Active a = active;
        if (a == null) return "scenarios: done | " + lastResult;
        return String.format(Locale.ROOT, "scenarios: running %s (%d/%d)",
                a.current == null ? "-" : a.current.name(), a.total - a.queue.size() + (a.current == null ? 0 : 1), a.total);
    }

    public static String stop(WorldServer ws) {
        if (active == null) return "scenarios: nothing running";
        finish(ws);
        return "scenarios: stopped | " + lastResult;
    }

    /** World tick (overworld, END phase). */
    public static void tick(WorldServer ws) {
        Active a = active;
        if (a == null) return;
        long now = ws.getTotalWorldTime();
        if (a.current == null && !beginNext(ws, a, now)) return;

        AutotestScenario s = a.current;
        Outcome o = s.poll(ws, now - a.startTick);
        if (!o.isFinal() && now - a.startTick >= s.budgetSeconds() * 20L) {
            o = Outcome.fail("time budget of " + s.budgetSeconds() + " s reached");
        }
        if (!o.isFinal()) return;
        conclude(ws, a, s, o);
    }

    /** Pops the next scenario and sets it up; false when the run is over or the scenario ended at once. */
    private static boolean beginNext(WorldServer ws, Active a, long now) {
        if (a.queue.isEmpty()) {
            finish(ws);
            return false;
        }
        a.current = a.queue.remove(0);
        a.attempt = 0;
        return setUp(ws, a, now);
    }

    private static boolean setUp(WorldServer ws, Active a, long now) {
        a.startTick = now;
        a.zoneMark = AutotestZones.mark();
        Outcome o = a.current.begin(a.arena, a.attempt);
        if (o == null) return true;
        conclude(ws, a, a.current, o);
        return false;
    }

    private static void conclude(WorldServer ws, Active a, AutotestScenario s, Outcome o) {
        String state = o.kind == Outcome.Kind.FAIL ? s.describeState(ws) : "";
        if (!(o.kind == Outcome.Kind.PASS && s.keepsUnitsOnPass())) s.cleanup(ws);
        AutotestZones.releaseSince(a.zoneMark);
        if (o.kind == Outcome.Kind.FAIL && s.retriesOnFailure() && a.attempt + 1 < MAX_ATTEMPTS) {
            a.attempt++;
            a.firstFailure = o.reason + (state.isEmpty() ? "" : " (" + state + ")");
            setUp(ws, a, ws.getTotalWorldTime());
            return;
        }
        String line;
        switch (o.kind) {
            case PASS:
                a.pass++;
                line = "PASS " + s.name() + (a.attempt > 0 ? " (on retry; first try: " + a.firstFailure + ")" : "");
                break;
            case SKIP:
                a.skip++;
                line = "SKIP " + s.name() + ": " + o.reason;
                break;
            default:
                a.fail++;
                line = "FAIL " + s.name() + ": " + o.reason + (state.isEmpty() ? "" : " (" + state + ")")
                        + (s.traceFiles().isEmpty() ? "" : " trace " + String.join(", ", s.traceFiles()));
                break;
        }
        a.results.add(line);
        a.current = null;
    }

    private static void finish(WorldServer ws) {
        Active a = active;
        if (a == null) return;
        if (a.current != null) {
            a.current.cleanup(ws);
            AutotestZones.releaseSince(a.zoneMark);
        }
        List<String> lines = new ArrayList<>(a.results);
        AutotestReport.append("scenarios", lines);
        StringBuilder bad = new StringBuilder();
        for (String l : a.results) if (!l.startsWith("PASS")) bad.append(" | ").append(l);
        lastResult = String.format(Locale.ROOT, "PASS %d, FAIL %d, SKIP %d%s", a.pass, a.fail, a.skip, bad);
        active = null;
    }
}
