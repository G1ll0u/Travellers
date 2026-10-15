package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.debug.autotest.AutotestZones;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * A worker assigned to a Barrack goes home, or one assigned to a Stable goes to its duty: the trip ends within the
 * zone's arrival radius. The assignment is set directly on the worker; the bed bookkeeping is left alone.
 */
public final class ZoneTripScenario extends AutotestScenario {

    private static final int START_DISTANCE = 44;
    private static final int ZONE_RADIUS_CHUNKS = 3;
    private static final int ARRIVAL_MARGIN = 2;

    private final boolean duty;
    private UUID workerId;
    private BlockPos target;
    private int arrivalRadius;

    public ZoneTripScenario(boolean duty) {
        this.duty = duty;
    }

    @Override
    public String name() {
        return duty ? "go-duty" : "go-home";
    }

    @Override
    public int budgetSeconds() {
        return 150;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        ZoneType type = duty ? ZoneType.STABLE : ZoneType.BARRACK;
        PlayerZoneRecord zone = null;
        for (PlayerZoneRecord z : PlayerZoneManager.get(ws).getAll()) {
            if (z.type == type) {
                zone = z;
                break;
            }
        }
        if (zone == null) return Outcome.skip("no " + type + " in this world");
        target = zone.center;
        if (AutotestZones.add(ws, target.getX(), target.getZ(), ZONE_RADIUS_CHUNKS) < 0) return Outcome.fail("zone ticket refused");
        BlockPos from = arena.groundAround(target, START_DISTANCE, attempt);
        if (from == null) return Outcome.skip("no dry ground " + START_DISTANCE + " blocks from the " + type + " at " + target.getX() + " " + target.getZ());

        EntityTravellerWorker w = spawnHiredWorker(ws, from, zone.ownerUUID);
        if (w == null) return Outcome.fail("spawn refused at " + from.getX() + " " + from.getZ());
        workerId = w.getUniqueID();
        arrivalRadius = w.getTravelCommon().arrivalRadiusBlocks(target);
        w.assignZone(zone, ws.provider.getDimension());
        if (duty) w.trips.startDutyTravel();
        else w.trips.startGoHome();
        return null;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        EntityTravellerWorker body = loaded(ws, workerId) instanceof EntityTravellerWorker
                ? (EntityTravellerWorker) loaded(ws, workerId) : null;
        VirtualTravellerRecord rec = record(ws, workerId);
        if (body != null && rec != null) return Outcome.fail("loaded AND virtual at once");
        if (body == null && rec == null) return Outcome.fail("the worker no longer exists");

        boolean tripDone = body != null ? !(duty ? body.trips.isGoingToDuty() : body.trips.isGoingHome())
                : !(duty ? rec.goingToDuty : rec.goingHome);
        if (!tripDone) return Outcome.PENDING;
        if (horizontalDistance(position(ws, workerId), target) > arrivalRadius + ARRIVAL_MARGIN) {
            return Outcome.fail("the trip ended away from the zone");
        }
        return Outcome.PASS;
    }

    @Override
    public String describeState(WorldServer ws) {
        double[] pos = position(ws, workerId);
        if (pos == null) return "worker gone";
        return String.format(Locale.ROOT, "%s, %.0f blocks from the zone at %d %d (arrival radius %d)", where(ws, workerId),
                horizontalDistance(pos, target), target.getX(), target.getZ(), arrivalRadius);
    }
}
