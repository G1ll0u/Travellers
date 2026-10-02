package com.jubitus.travellers.travel;

import java.util.Objects;

/**
 * A unit's evidence of being trapped, owned by the entity so no AI task restart, goal refresh or
 * itinerary rebuild can wipe it. Progress means entering a cell not among the last {@link #CELL_MEMORY}
 * visited: pacing inside any small area keeps revisiting the same cells, real travel does not.
 * Pure state, no world access.
 */
public final class StuckRecord {

    static final int CELL_MEMORY = 128;

    /** The episode of a catchup chase, whatever its moving goal. */
    public static final Object CATCHUP = new Object();

    public final TravelStuckLadder ladder = new TravelStuckLadder();

    /** Ticks the follow AI has wanted to move without entering a new cell. */
    private int followStall;

    private final long[] cells = new long[CELL_MEMORY];
    private int cellCount;
    private int cellHead;
    private long lastCell = Long.MIN_VALUE;

    private Object episodeKey;

    /** Records a position; true when it lies in a cell not visited recently (real progress). */
    public boolean visit(double x, double y, double z) {
        long cell = cellKey((int) Math.floor(x), (int) Math.floor(y) >> 1, (int) Math.floor(z));
        if (cell == lastCell) return false;
        lastCell = cell;
        for (int i = 0; i < cellCount; i++) {
            if (cells[i] == cell) return false;
        }
        cells[cellHead] = cell;
        cellHead = (cellHead + 1) % CELL_MEMORY;
        if (cellCount < CELL_MEMORY) cellCount++;
        followStall = 0;
        return true;
    }

    /** One follow AI tick: records the position and counts the time spent wanting to move without progress. */
    public int tickFollow(double x, double y, double z, boolean wantsToMove) {
        if (!visit(x, y, z)) {
            followStall = wantsToMove ? followStall + 1 : 0;
        }
        return followStall;
    }

    /** The ladder has started escalating: a catchup chase must not end on distance alone until it resolves. */
    public boolean isEscalating() {
        return ladder.rung() > 0;
    }

    public int followStall() {
        return followStall;
    }

    /**
     * Hands a follower stuck for {@code followStall} ticks to catchup travel: the catchup episode starts
     * with that evidence, so the travel ladder runs its rungs straight away instead of starting over.
     */
    public void handOverToCatchup() {
        beginEpisode(CATCHUP);
        ladder.preload(followStall);
    }

    /** A different goal starts a new episode; the same one (even a moving catchup goal) keeps the evidence. */
    public void beginEpisode(Object key) {
        if (Objects.equals(key, episodeKey)) return;
        episodeKey = key;
        reset();
    }

    /** Real progress or a successful hop: the trap, if any, is behind. */
    public void reset() {
        ladder.reset();
        followStall = 0;
    }

    static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
