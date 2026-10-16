package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.follow.GroupFormationTracker;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * A travelling group whose leader is suddenly pushed off its route waits at the route instead of chasing it,
 * then moves on and closes up once the leader travels on.
 */
public final class GroupAnchorScenario extends AutotestScenario {

    private static final int RUN_LENGTH = 40;
    /** The leader is pushed aside once it has walked this far. */
    private static final double PUSH_AFTER_BLOCKS = 8.0D;
    private static final int PUSH_SIDEWAYS = 8;
    private static final long WAIT_DEADLINE_TICKS = 40L;
    private static final double REGROUPED = 10.0D;

    private UUID leaderId;
    private UUID[] followers;
    private BlockPos start;
    private BlockPos goal;
    private boolean pushed;
    private long pushedAt;
    private boolean waitSeen;

    @Override
    public String name() {
        return "group-anchor";
    }

    @Override
    public int budgetSeconds() {
        return 150;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        WorldServer ws = arena.world;
        BlockPos[] run = arena.findRun(RUN_LENGTH, attempt);
        if (run == null) return Outcome.skip("no open " + RUN_LENGTH + "-block run near " + arena.centerX + " " + arena.centerZ);
        start = run[0];
        goal = run[1];
        pushed = false;
        waitSeen = false;

        EntityTraveller leader = spawn(ws, start);
        if (leader == null) return Outcome.fail("leader spawn refused");
        leaderId = leader.getUniqueID();
        followers = new UUID[2];
        for (int i = 0; i < followers.length; i++) {
            EntityTraveller f = spawn(ws, arena.groundAround(start, 2, i));
            if (f == null) return Outcome.fail("follower spawn refused");
            f.setGroupLeaderUUID(leaderId);
            followers[i] = f.getUniqueID();
        }
        leader.getTravelCommon().routeTo(goal);
        return null;
    }

    @Nullable
    private EntityTraveller spawn(WorldServer ws, @Nullable BlockPos p) {
        if (p == null) return null;
        EntityTraveller t = new EntityTraveller(ws);
        t.setLocationAndAngles(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 0.0F, 0.0F);
        t.onInitialSpawn(ws.getDifficultyForLocation(p), null);
        if (!ws.spawnEntity(t)) return null;
        track(t);
        return t;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        EntityTraveller leader = loaded(ws, leaderId);
        if (leader == null) return Outcome.fail("the leader is no longer loaded (" + where(ws, leaderId) + ")");
        for (UUID f : followers) if (loaded(ws, f) == null) return Outcome.fail("a follower is no longer loaded");
        boolean waiting = GroupFormationTracker.isWaitingForLeader(leaderId);

        if (!pushed) {
            double[] pos = {leader.posX, leader.posY, leader.posZ};
            if (horizontalDistance(pos, start) < PUSH_AFTER_BLOCKS) return Outcome.PENDING;
            BlockPos aside = sideways(ws, leader);
            if (aside == null) return Outcome.skip("no ground " + PUSH_SIDEWAYS + " blocks beside the route");
            leader.setPositionAndUpdate(aside.getX() + 0.5D, aside.getY(), aside.getZ() + 0.5D);
            pushed = true;
            pushedAt = ageTicks;
            return Outcome.PENDING;
        }
        if (waiting) waitSeen = true;
        if (!waitSeen) {
            return ageTicks - pushedAt > WAIT_DEADLINE_TICKS
                    ? Outcome.fail("the group never stopped to wait for its strayed leader") : Outcome.PENDING;
        }
        if (waiting) return Outcome.PENDING;
        for (UUID f : followers) {
            if (loaded(ws, f).getDistance(leader) > REGROUPED) return Outcome.PENDING;
        }
        return Outcome.PASS;
    }

    @Nullable
    private BlockPos sideways(WorldServer ws, EntityTraveller leader) {
        double dx = goal.getX() - start.getX(), dz = goal.getZ() - start.getZ();
        double len = Math.max(1.0D, Math.sqrt(dx * dx + dz * dz));
        int x = (int) Math.floor(leader.posX - dz / len * PUSH_SIDEWAYS);
        int z = (int) Math.floor(leader.posZ + dx / len * PUSH_SIDEWAYS);
        return new AutotestArena(ws, x, z).groundAt(x, z);
    }

    @Override
    public String describeState(WorldServer ws) {
        EntityTraveller leader = loaded(ws, leaderId);
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "pushed=%s waitSeen=%s waiting=%s",
                pushed, waitSeen, GroupFormationTracker.isWaitingForLeader(leaderId)));
        for (UUID f : followers) {
            EntityTraveller e = loaded(ws, f);
            if (e != null && leader != null) sb.append(String.format(Locale.ROOT, "; follower %.1f from leader", e.getDistance(leader)));
        }
        return sb.toString();
    }
}
