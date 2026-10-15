package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * A hired worker given a Go order across open terrain arrives. Variants: virtualized on the way, or saved and the
 * server restarted on the way (two halves, driven by the runner: the first leaves the worker walking and writes its
 * identity to a file, the second finds it again after the restart).
 */
public final class GoOrderScenario extends AutotestScenario {

    public enum Mode {PLAIN, VIRTUALIZED, RESTART_SAVE, RESTART_RESUME}

    private static final int RUN_LENGTH = 40;
    /** Within the arrival radius plus this margin counts as arrived (the unit stops at the radius edge). */
    private static final int ARRIVAL_MARGIN = 2;
    /** The worker is virtualized (or saved) once it has covered this much of the way. */
    private static final double MIDWAY_BLOCKS = 12.0D;
    private static final File RESTART_FILE = new File("logs/travellers-autotest/restart-go.properties");

    private final Mode mode;
    private UUID workerId;
    private BlockPos start;
    private BlockPos goal;
    private boolean virtualized;

    public GoOrderScenario(Mode mode) {
        this.mode = mode;
    }

    @Override
    public String name() {
        switch (mode) {
            case VIRTUALIZED:
                return "go-virtualized";
            case RESTART_SAVE:
                return "go-restart-save";
            case RESTART_RESUME:
                return "go-restart-resume";
            default:
                return "go";
        }
    }

    /** The two restart halves only make sense around a server restart, so "all" leaves them out. */
    public boolean isRestartHalf() {
        return mode == Mode.RESTART_SAVE || mode == Mode.RESTART_RESUME;
    }

    @Override
    public int budgetSeconds() {
        return 120;
    }

    @Override
    public boolean retriesOnFailure() {
        return mode != Mode.RESTART_RESUME;
    }

    @Override
    public boolean keepsUnitsOnPass() {
        return mode == Mode.RESTART_SAVE;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        virtualized = false;
        if (mode == Mode.RESTART_RESUME) return resume();
        BlockPos[] run = arena.findRun(RUN_LENGTH, attempt);
        if (run == null) return Outcome.skip("no open " + RUN_LENGTH + "-block run near " + arena.centerX + " " + arena.centerZ);
        start = run[0];
        goal = run[1];
        EntityTravellerWorker w = spawnHiredWorker(arena.world, start);
        if (w == null) return Outcome.fail("spawn refused at " + start.getX() + " " + start.getZ());
        workerId = w.getUniqueID();
        w.trips.startGoTo(goal, w.dimension);
        if (mode == Mode.RESTART_SAVE) writeRestartFile();
        return null;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        EntityTravellerWorker body = loaded(ws, workerId) instanceof EntityTravellerWorker
                ? (EntityTravellerWorker) loaded(ws, workerId) : null;
        VirtualTravellerRecord rec = record(ws, workerId);
        if (body != null && rec != null) return Outcome.fail("loaded AND virtual at once");
        if (body == null && rec == null) return Outcome.fail("the worker no longer exists");

        double[] pos = position(ws, workerId);
        if (mode == Mode.RESTART_SAVE) {
            return horizontalDistance(pos, start) >= MIDWAY_BLOCKS ? Outcome.PASS : Outcome.PENDING;
        }

        boolean orderDone = body != null ? !body.trips.isGoOrderActive() : !rec.goOrderActive;
        if (horizontalDistance(pos, goal) <= TravellersConfig.WORKER.workerGoArrivalRadius + ARRIVAL_MARGIN && orderDone) {
            if (mode == Mode.VIRTUALIZED && !virtualized) return Outcome.fail("arrived before it could be virtualized");
            return Outcome.PASS;
        }

        if (mode == Mode.VIRTUALIZED && !virtualized && body != null && horizontalDistance(pos, start) >= MIDWAY_BLOCKS) {
            virtualized = true;
            if (!VirtualTravellerManager.get(ws).virtualizeWorker(ws, body, ws.getTotalWorldTime())) {
                return Outcome.fail("virtualizing the worker was refused");
            }
        }
        return Outcome.PENDING;
    }

    private void writeRestartFile() {
        Properties p = new Properties();
        p.setProperty("worker", workerId.toString());
        p.setProperty("goal", goal.getX() + "," + goal.getY() + "," + goal.getZ());
        p.setProperty("start", start.getX() + "," + start.getY() + "," + start.getZ());
        RESTART_FILE.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(RESTART_FILE)) {
            p.store(out, "go-restart-save");
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + RESTART_FILE, e);
        }
    }

    @Nullable
    private Outcome resume() {
        if (!RESTART_FILE.exists()) return Outcome.skip("no go-restart-save came before (no " + RESTART_FILE.getName() + ")");
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(RESTART_FILE)) {
            p.load(in);
        } catch (IOException e) {
            return Outcome.fail("could not read " + RESTART_FILE.getName() + ": " + e.getMessage());
        }
        RESTART_FILE.delete();
        workerId = UUID.fromString(p.getProperty("worker"));
        goal = parse(p.getProperty("goal"));
        start = parse(p.getProperty("start"));
        adopt(workerId);
        return null;
    }

    private static BlockPos parse(String xyz) {
        String[] t = xyz.split(",");
        return new BlockPos(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]));
    }

    @Override
    public String describeState(WorldServer ws) {
        double[] pos = position(ws, workerId);
        if (pos == null) return "worker gone";
        return String.format(Locale.ROOT, "%s, %.0f blocks from the goal at %d %d (started %d %d)%s", where(ws, workerId),
                horizontalDistance(pos, goal), goal.getX(), goal.getZ(), start.getX(), start.getZ(),
                mode == Mode.VIRTUALIZED ? (virtualized ? ", was virtualized" : ", never reached the virtualize point") : "");
    }
}
