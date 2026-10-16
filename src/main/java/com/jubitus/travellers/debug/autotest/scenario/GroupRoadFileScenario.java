package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.route.TravelPlan;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * A group whose leader walks a road lines up in file on the road behind it, and the leader still gets to the end
 * (it slows for the group, never stops). The road is a forced straight ROAD itinerary, so any open ground will do.
 * Variants: hired escorts behind a worker leader on a Go order; a group that goes offscreen once lined up and
 * must reappear lined up on the road.
 */
public final class GroupRoadFileScenario extends AutotestScenario {

    public enum Mode {TRAVELLERS, WORKERS, REAPPEAR}

    private static final int RUN_LENGTH = 40;
    /** Long enough that the offscreen leader is still on the road when the group comes back. */
    private static final int REAPPEAR_RUN_LENGTH = 72;
    private static final int FOLLOWERS = 3;
    /** Two-abreast lanes sit 0.75 off the centre line; allow a step of slack. */
    private static final double ON_ROAD = 1.6D;
    private static final double ARRIVED = 6.0D;

    private UUID leaderId;
    private UUID[] followers;
    private BlockPos start;
    private BlockPos goal;
    private boolean fileSeen;
    private boolean virtualized;
    private final Mode mode;
    private final boolean workers;

    public GroupRoadFileScenario(Mode mode) {
        this.mode = mode;
        this.workers = mode == Mode.WORKERS;
    }

    @Override
    public String name() {
        return mode == Mode.WORKERS ? "worker-road-file" : mode == Mode.REAPPEAR ? "group-road-file-reappear" : "group-road-file";
    }

    @Override
    public int budgetSeconds() {
        return 150;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        int length = mode == Mode.REAPPEAR ? REAPPEAR_RUN_LENGTH : RUN_LENGTH;
        BlockPos[] run = arena.findRun(length, attempt);
        if (run == null) return Outcome.skip("no open " + length + "-block run near " + arena.centerX + " " + arena.centerZ);
        start = run[0];
        goal = run[1];
        fileSeen = false;
        virtualized = false;

        EntityTraveller leader = spawn(ws, start);
        if (leader == null) return Outcome.fail("leader spawn refused");
        leaderId = leader.getUniqueID();
        followers = new UUID[FOLLOWERS];
        for (int i = 0; i < FOLLOWERS; i++) {
            EntityTraveller f = spawn(ws, arena.groundAround(start, 3, i * 3));
            if (f == null) return Outcome.fail("follower spawn refused");
            if (workers) {
                ((EntityTravellerWorker) f).setEscortLeaderUUID(leaderId);
                ((EntityTravellerWorker) f).setFollowOrderActive(true);
            } else {
                f.setGroupLeaderUUID(leaderId);
            }
            followers[i] = f.getUniqueID();
        }
        if (workers) {
            EntityTravellerWorker w = (EntityTravellerWorker) leader;
            w.setFollowOrderActive(false);
            w.trips.startGoTo(goal, w.dimension);
        } else {
            leader.getTravelCommon().routeTo(goal);
        }
        leader.getItineraryState().setForced(Itinerary.of(Arrays.asList(start, goal), Itinerary.ROAD,
                TravelPlan.QUALITY_REAL, "autotest-road"), leader.posX, leader.posZ);
        return null;
    }

    @Nullable
    private EntityTraveller spawn(WorldServer ws, @Nullable BlockPos p) {
        if (p == null) return null;
        if (workers) return spawnHiredWorker(ws, p);
        EntityTraveller t = new EntityTraveller(ws);
        t.setLocationAndAngles(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 0.0F, 0.0F);
        t.onInitialSpawn(ws.getDifficultyForLocation(p), null);
        if (!ws.spawnEntity(t)) return null;
        track(t);
        return t;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        if (virtualized) return pollReappear(ws);
        EntityTraveller leader = loaded(ws, leaderId);
        if (leader == null) return Outcome.fail("the leader is no longer loaded (" + where(ws, leaderId) + ")");
        for (UUID f : followers) if (loaded(ws, f) == null) return Outcome.fail("a follower is no longer loaded");

        if (!fileSeen && alongLeader(ws, leader) >= 12.0D && allInFile(ws, leader)) fileSeen = true;
        if (fileSeen && mode == Mode.REAPPEAR) {
            VirtualTravellerManager vtm = VirtualTravellerManager.get(ws);
            long now = ws.getTotalWorldTime();
            for (UUID id : followers) {
                if (!vtm.virtualizeTraveller(ws, loaded(ws, id), now)) return Outcome.fail("virtualizing a follower was refused");
            }
            if (!vtm.virtualizeTraveller(ws, leader, now)) return Outcome.fail("virtualizing the leader was refused");
            virtualized = true;
            return Outcome.PENDING;
        }
        if (horizontalDistance(new double[]{leader.posX, leader.posY, leader.posZ}, goal) <= ARRIVED) {
            return fileSeen ? Outcome.PASS : Outcome.fail("the leader arrived but the group never lined up on the road");
        }
        return Outcome.PENDING;
    }

    /** Once every unit is back in the world, they must stand in file on the road behind the leader. */
    private Outcome pollReappear(WorldServer ws) {
        EntityTraveller leader = loaded(ws, leaderId);
        if (leader == null) return position(ws, leaderId) == null ? Outcome.fail("the leader vanished") : Outcome.PENDING;
        for (UUID id : followers) {
            if (loaded(ws, id) == null) return position(ws, id) == null ? Outcome.fail("a follower vanished") : Outcome.PENDING;
        }
        if (horizontalDistance(new double[]{leader.posX, leader.posY, leader.posZ}, goal) <= ARRIVED) {
            return Outcome.skip("the group came back only after reaching the end of the road");
        }
        return allInFile(ws, leader) ? Outcome.PASS : Outcome.fail("the group reappeared off the road or ahead of its leader");
    }

    private double alongLeader(WorldServer ws, EntityTraveller leader) {
        return along(leader.posX, leader.posZ);
    }

    /** Every follower close to the road line and behind the leader. */
    private boolean allInFile(WorldServer ws, EntityTraveller leader) {
        double leaderAlong = along(leader.posX, leader.posZ);
        for (UUID id : followers) {
            EntityTraveller f = loaded(ws, id);
            if (f == null || offLine(f.posX, f.posZ) > ON_ROAD || along(f.posX, f.posZ) >= leaderAlong) return false;
        }
        return true;
    }

    private double along(double x, double z) {
        double dx = goal.getX() - start.getX(), dz = goal.getZ() - start.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        return ((x - start.getX() - 0.5D) * dx + (z - start.getZ() - 0.5D) * dz) / len;
    }

    private double offLine(double x, double z) {
        double dx = goal.getX() - start.getX(), dz = goal.getZ() - start.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        return Math.abs((x - start.getX() - 0.5D) * dz - (z - start.getZ() - 0.5D) * dx) / len;
    }

    @Override
    public String describeState(WorldServer ws) {
        EntityTraveller leader = loaded(ws, leaderId);
        if (leader == null) return "leader gone";
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "leader %.1f along, fileSeen=%s",
                along(leader.posX, leader.posZ), fileSeen));
        for (UUID id : followers) {
            EntityTraveller f = loaded(ws, id);
            if (f != null) sb.append(String.format(Locale.ROOT, "; follower %.1f along, %.1f off the road",
                    along(f.posX, f.posZ), offLine(f.posX, f.posZ)));
        }
        return sb.toString();
    }
}
