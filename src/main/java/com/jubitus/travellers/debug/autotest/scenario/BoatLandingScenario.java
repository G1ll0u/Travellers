package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.MillFootprints;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/** A worker ordered across open water arrives on the far shore on foot: out of the boat, on dry ground, outside buildings. */
public final class BoatLandingScenario extends AutotestScenario {

    private static final int ARRIVAL_MARGIN = 2;

    private UUID workerId;
    private BlockPos start;
    private BlockPos landing;

    @Override
    public String name() {
        return "boat-landing";
    }

    @Override
    public int budgetSeconds() {
        return 240;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        BlockPos[] crossing = arena.findWaterCrossing(attempt);
        if (crossing == null) return Outcome.skip("no water crossing near " + arena.centerX + " " + arena.centerZ);
        start = crossing[0];
        landing = crossing[1];
        EntityTravellerWorker w = spawnHiredWorker(arena.world, start);
        if (w == null) return Outcome.fail("spawn refused at " + start.getX() + " " + start.getZ());
        workerId = w.getUniqueID();
        w.trips.startGoTo(landing, w.dimension);
        return null;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        EntityTraveller body = loaded(ws, workerId);
        VirtualTravellerRecord rec = record(ws, workerId);
        if (body != null && rec != null) return Outcome.fail("loaded AND virtual at once");
        if (body == null && rec == null) return Outcome.fail("the worker no longer exists");

        double[] pos = position(ws, workerId);
        boolean orderDone = body instanceof EntityTravellerWorker ? !((EntityTravellerWorker) body).trips.isGoOrderActive() : !rec.goOrderActive;
        if (!orderDone || horizontalDistance(pos, landing) > TravellersConfig.WORKER.workerGoArrivalRadius + ARRIVAL_MARGIN) {
            return Outcome.PENDING;
        }
        if (body != null) {
            if (body.isRiding()) return Outcome.fail("still in the boat at the landing");
            if (body.isInWater()) return Outcome.fail("landed in the water");
        }
        if (MillFootprints.isForbiddenLanding(ws, pos[0], pos[1], pos[2])) return Outcome.fail("landed inside a Millenaire building");
        return Outcome.PASS;
    }

    @Override
    public String describeState(WorldServer ws) {
        double[] pos = position(ws, workerId);
        if (pos == null) return "worker gone";
        EntityTraveller body = loaded(ws, workerId);
        return String.format(Locale.ROOT, "%s, %.0f blocks from the landing at %d %d (started %d %d)%s", where(ws, workerId),
                horizontalDistance(pos, landing), landing.getX(), landing.getZ(), start.getX(), start.getZ(),
                body == null ? "" : (body.isRiding() ? ", in a boat" : body.isInWater() ? ", in the water" : ", on land"));
    }
}
