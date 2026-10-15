package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.debug.autotest.AutotestZones;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.equipment.EquipTargetKind;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.zone.stable.StableHorseEntry;
import com.jubitus.travellers.zone.stable.StableZoneData;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * A worker sent to fetch equipment at one of the world's Barracks or Stables reaches it and the order ends the way
 * it was asked: staying there, or going back to following. At a Stable it also comes away with a horse.
 */
public final class EquipScenario extends AutotestScenario {

    private static final int START_DISTANCE = 44;
    private static final int ZONE_RADIUS_CHUNKS = 3;
    private static final int ARRIVAL_MARGIN = 3;

    private final ZoneType zoneType;
    private final boolean stay;
    private UUID workerId;
    private BlockPos target;

    public EquipScenario(ZoneType zoneType, boolean stay) {
        this.zoneType = zoneType;
        this.stay = stay;
    }

    @Override
    public String name() {
        return "equip-" + zoneType.name().toLowerCase(Locale.ROOT) + (stay ? "-stay" : "-return");
    }

    @Override
    public int budgetSeconds() {
        return 150;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        PlayerZoneRecord zone = pickZone(ws);
        if (zone == null) return Outcome.skip("no " + zoneType + " with a ready horse in this world");
        target = zone.center;
        if (AutotestZones.add(ws, target.getX(), target.getZ(), ZONE_RADIUS_CHUNKS) < 0) return Outcome.fail("zone ticket refused");

        BlockPos from = arena.groundAround(target, START_DISTANCE, attempt);
        if (from == null) return Outcome.skip("no dry ground " + START_DISTANCE + " blocks from the " + zoneType + " at " + target.getX() + " " + target.getZ());

        EntityTravellerWorker w = spawnHiredWorker(ws, from, zone.ownerUUID);
        if (w == null) return Outcome.fail("spawn refused at " + from.getX() + " " + from.getZ());
        workerId = w.getUniqueID();
        w.trips.startGetEquip(target, stay, zoneType == ZoneType.STABLE ? EquipTargetKind.STABLE : EquipTargetKind.LONE_BARRACK);
        return null;
    }

    /** First zone of the type; a Stable must also hold a horse the order can hand out. */
    @Nullable
    private PlayerZoneRecord pickZone(WorldServer ws) {
        for (PlayerZoneRecord z : PlayerZoneManager.get(ws).getAll()) {
            if (z.type != zoneType) continue;
            if (zoneType != ZoneType.STABLE || hasReadyHorse(z)) return z;
        }
        return null;
    }

    private static boolean hasReadyHorse(PlayerZoneRecord z) {
        if (!(z.data instanceof StableZoneData)) return false;
        StableZoneData data = (StableZoneData) z.data;
        if (data.pool.size() <= TravellersConfig.STABLES.minPoolReserve) return false;
        for (StableHorseEntry e : data.pool) if (e.tamed && e.saddled) return true;
        return false;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        EntityTravellerWorker body = loaded(ws, workerId) instanceof EntityTravellerWorker
                ? (EntityTravellerWorker) loaded(ws, workerId) : null;
        VirtualTravellerRecord rec = record(ws, workerId);
        if (body != null && rec != null) return Outcome.fail("loaded AND virtual at once");
        if (body == null && rec == null) return Outcome.fail("the worker no longer exists");

        boolean orderDone = body != null ? !body.trips.isGoOrderActive() && !body.isJobActive()
                : !rec.goOrderActive && !"GET_EQUIPMENT".equals(rec.workerMode);
        if (!orderDone) return Outcome.PENDING;
        if (horizontalDistance(position(ws, workerId), target) > TravellersConfig.WORKER.workerEquipArrivalRadius + ARRIVAL_MARGIN) {
            return Outcome.fail("the order ended away from the " + zoneType);
        }
        if (body != null) {
            if (stay && body.getIdleAnchorPos() == null) return Outcome.fail("asked to stay, but no anchor was set");
            if (!stay && !body.isFollowOrderActive()) return Outcome.fail("asked to come back, but it is not following");
            if (zoneType == ZoneType.STABLE && body.getMountUUID() == null) return Outcome.fail("no horse was handed out");
        }
        return Outcome.PASS;
    }

    @Override
    public String describeState(WorldServer ws) {
        double[] pos = position(ws, workerId);
        if (pos == null) return "worker gone";
        return String.format(Locale.ROOT, "%s, %.0f blocks from the %s at %d %d", where(ws, workerId),
                horizontalDistance(pos, target), zoneType, target.getX(), target.getZ());
    }
}
