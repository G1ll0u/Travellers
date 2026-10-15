package com.jubitus.travellers.debug.autotest.scenario;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** A hired group whose leader is teleported far away walks to it and regroups, without any follower teleporting. */
public final class CatchupScenario extends AutotestScenario {

    private static final int LEADER_JUMP_BLOCKS = 80;
    private static final int FOLLOWERS = 3;
    /** The group settles into formation before the leader jumps. */
    private static final long FORMATION_TICKS = 100L;
    /** Regrouped: every follower this close to the leader (slots sit around it, not on it). */
    private static final double REGROUPED_BLOCKS = 14.0D;
    /** A loaded follower covering this much in one tick was teleported. */
    private static final double TELEPORT_BLOCKS = 6.0D;

    private UUID leaderId;
    private final List<UUID> followerIds = new ArrayList<>();
    private final List<double[]> lastPos = new ArrayList<>();
    private BlockPos jumpTo;
    private boolean jumped;
    private String teleportedFollower;

    @Override
    public String name() {
        return "catchup";
    }

    @Override
    public int budgetSeconds() {
        return 180;
    }

    @Nullable
    @Override
    public Outcome begin(AutotestArena arena, int attempt) {
        BlockPos[] run = arena.findRun(LEADER_JUMP_BLOCKS, attempt);
        if (run == null) return Outcome.skip("no open " + LEADER_JUMP_BLOCKS + "-block run near " + arena.centerX + " " + arena.centerZ);
        jumpTo = run[1];
        jumped = false;
        teleportedFollower = null;
        followerIds.clear();
        lastPos.clear();

        EntityTravellerWorker leader = spawnHiredWorker(arena.world, run[0]);
        if (leader == null) return Outcome.fail("leader spawn refused");
        leaderId = leader.getUniqueID();
        leader.clearEscortLeader();
        leader.setFollowOrderActive(false);
        for (int i = 0; i < FOLLOWERS; i++) {
            BlockPos p = arena.groundAt(run[0].getX() + 2 + i, run[0].getZ() + 2);
            EntityTravellerWorker f = p == null ? null : spawnHiredWorker(arena.world, p);
            if (f == null) return Outcome.fail("follower " + i + " spawn refused");
            f.setEscortLeaderUUID(leaderId);
            f.setFollowOrderActive(true);
            followerIds.add(f.getUniqueID());
            lastPos.add(null);
        }
        return null;
    }

    @Override
    public Outcome poll(WorldServer ws, long ageTicks) {
        if (!jumped) {
            if (ageTicks < FORMATION_TICKS) return Outcome.PENDING;
            EntityTraveller leader = loaded(ws, leaderId);
            if (leader == null) return Outcome.fail("the leader is not loaded when it should jump");
            leader.setPositionAndUpdate(jumpTo.getX() + 0.5D, jumpTo.getY(), jumpTo.getZ() + 0.5D);
            jumped = true;
            return Outcome.PENDING;
        }

        double[] leaderPos = position(ws, leaderId);
        if (leaderPos == null) return Outcome.fail("the leader no longer exists");
        boolean allClose = true;
        for (int i = 0; i < followerIds.size(); i++) {
            UUID id = followerIds.get(i);
            EntityTraveller body = loaded(ws, id);
            double[] pos = position(ws, id);
            if (pos == null) return Outcome.fail("follower " + i + " no longer exists");
            double[] before = lastPos.get(i);
            if (body != null && before != null && teleportedFollower == null
                    && Math.hypot(pos[0] - before[0], pos[2] - before[2]) > TELEPORT_BLOCKS) {
                teleportedFollower = "follower " + i;
            }
            lastPos.set(i, body != null ? pos : null);
            if (Math.hypot(pos[0] - leaderPos[0], pos[2] - leaderPos[2]) > REGROUPED_BLOCKS) allClose = false;
        }
        if (teleportedFollower != null) return Outcome.fail(teleportedFollower + " was teleported instead of walking");
        return allClose ? Outcome.PASS : Outcome.PENDING;
    }

    @Override
    public String describeState(WorldServer ws) {
        double[] leaderPos = position(ws, leaderId);
        if (leaderPos == null) return "leader gone";
        StringBuilder sb = new StringBuilder(jumped ? "leader jumped" : "leader had not jumped yet");
        for (int i = 0; i < followerIds.size(); i++) {
            double[] pos = position(ws, followerIds.get(i));
            sb.append(pos == null ? ", follower " + i + " gone" : String.format(Locale.ROOT, ", follower %d %s %.0f blocks away",
                    i, where(ws, followerIds.get(i)), Math.hypot(pos[0] - leaderPos[0], pos[2] - leaderPos[2])));
        }
        return sb.toString();
    }
}
