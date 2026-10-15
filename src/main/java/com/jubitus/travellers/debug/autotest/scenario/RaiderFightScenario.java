package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Raiders meet hired workers in open country: they spawn with the configured health, fight, and one side is beaten. */
public final class RaiderFightScenario extends AutotestScenario {

    private static final int RAIDERS = 2;
    private static final int WORKERS = 4;
    private static final int DISTANCE = 14;
    private static final float HEALTH_TOLERANCE = 0.01F;

    private final List<UUID> raiders = new ArrayList<>();
    private final List<UUID> workers = new ArrayList<>();
    private boolean fightSeen;

    @Override
    public String name() {
        return "raider-fight";
    }

    @Override
    public int budgetSeconds() {
        return 180;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        if (TravellersDerivedConfig.isPeacefulBlocked(ws)) return Outcome.skip("peaceful difficulty blocks combat");
        BlockPos[] run = arena.findRun(DISTANCE, attempt);
        if (run == null) return Outcome.skip("no open " + DISTANCE + "-block run near " + arena.centerX + " " + arena.centerZ);
        raiders.clear();
        workers.clear();
        fightSeen = false;

        for (int i = 0; i < WORKERS; i++) {
            BlockPos p = arena.groundAt(run[0].getX() + i, run[0].getZ());
            EntityTravellerWorker w = p == null ? null : spawnHiredWorker(ws, p);
            if (w == null) return Outcome.fail("worker " + i + " spawn refused");
            workers.add(w.getUniqueID());
        }
        for (int i = 0; i < RAIDERS; i++) {
            BlockPos p = arena.groundAt(run[1].getX() + i, run[1].getZ());
            if (p == null) return Outcome.fail("no ground for raider " + i);
            EntityTravellerRaider r = new EntityTravellerRaider(ws);
            r.setLocationAndAngles(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 0.0F, 0.0F);
            r.onInitialSpawn(ws.getDifficultyForLocation(p), null);
            if (!ws.spawnEntity(r)) return Outcome.fail("raider " + i + " spawn refused");
            track(r);
            raiders.add(r.getUniqueID());
            if (Math.abs(r.getHealth() - TravellersConfig.ATTRIBUTES.raiderMaxHealth) > HEALTH_TOLERANCE) {
                return Outcome.fail(String.format(Locale.ROOT, "raider spawned with %.1f health, config says %.1f",
                        r.getHealth(), TravellersConfig.ATTRIBUTES.raiderMaxHealth));
            }
        }
        return null;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        int raidersLeft = alive(ws, raiders), workersLeft = alive(ws, workers);
        if (raidersLeft < RAIDERS || workersLeft < WORKERS || anyHurt(ws)) fightSeen = true;
        if (raidersLeft == 0 || workersLeft == 0) return fightSeen ? Outcome.PASS : Outcome.fail("a side vanished without a fight");
        return Outcome.PENDING;
    }

    private int alive(WorldServer ws, List<UUID> ids) {
        int n = 0;
        for (UUID id : ids) if (!"gone".equals(where(ws, id))) n++;
        return n;
    }

    private boolean anyHurt(WorldServer ws) {
        for (List<UUID> side : java.util.Arrays.asList(raiders, workers)) {
            for (UUID id : side) {
                EntityTraveller e = loaded(ws, id);
                if (e != null && e.getHealth() < e.getMaxHealth() - HEALTH_TOLERANCE) return true;
            }
        }
        return false;
    }

    @Override
    public String describeState(WorldServer ws) {
        return String.format(Locale.ROOT, "%d/%d raiders and %d/%d workers left, %s", alive(ws, raiders), RAIDERS,
                alive(ws, workers), WORKERS, fightSeen ? "a fight started but did not finish" : "nobody was ever hurt");
    }
}
