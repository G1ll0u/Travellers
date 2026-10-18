package com.jubitus.travellers.worldmap.gui;

/**
 * The one clock map markers and strike animations are drawn by, in server time (ms = tick * 50).
 * It plays {@link #BUFFER_MS} behind the newest unit update and follows the server's pace: when the
 * server runs slow the map runs slow, like the units themselves. It never jumps; it speeds up or slows
 * down gently to keep its distance. Pure logic; the wall clock is passed in.
 */
public final class MapPlaybackClock {

    /** The clock of the client's map screens. */
    public static final MapPlaybackClock CLIENT = new MapPlaybackClock();

    /** How far behind the newest update markers are drawn, in server time: a bit over one update. */
    public static final long BUFFER_MS = 1800L;
    /** The server's time is guessed forward from the last update for at most one update interval. */
    private static final long MAX_GUESS_MS = 1000L;
    /** Weight of each update in the measured server pace (server ms per wall ms). */
    private static final double PACE_SMOOTHING = 0.2;
    /** How hard the clock pulls toward its target: an error of this many ms adds or removes a whole wall pace. */
    private static final double PULL_MS = 3000.0;
    /** Closer than this to the newest update the clock slows down, so a stalled server makes markers ease to a stop. */
    private static final double EASE_MS = 300.0;
    /** Further than this from its target (new world, map reopened after a while) the clock resets. */
    private static final double RESYNC_MS = 5000.0;

    private boolean running;
    private long newestServerMs;
    private long newestWallMs;
    private long lastWallMs;
    private double playMs;
    private double serverPace = 1.0;

    /** A unit update measured at {@code serverTick} arrived at {@code wallMs}; returns its server time in ms. */
    public long onUpdate(long serverTick, long wallMs) {
        long serverMs = serverTick * 50L;
        if (!running || serverMs < newestServerMs || serverMs - newestServerMs > RESYNC_MS) {
            running = true;
            newestServerMs = serverMs;
            newestWallMs = wallMs;
            lastWallMs = wallMs;
            playMs = serverMs - BUFFER_MS;
            serverPace = 1.0;
        } else if (serverMs > newestServerMs) {
            double measured = (serverMs - newestServerMs) / (double) Math.max(1L, wallMs - newestWallMs);
            serverPace += PACE_SMOOTHING * (Math.max(0.05, Math.min(2.0, measured)) - serverPace);
            newestServerMs = serverMs;
            newestWallMs = wallMs;
        }
        return serverMs;
    }

    public boolean isRunning() {
        return running;
    }

    /** Server time (ms) to draw at {@code wallMs}. Call with a non-decreasing wall clock. */
    public long now(long wallMs) {
        if (!running) return 0L;
        long dt = Math.max(0L, wallMs - lastWallMs);
        lastWallMs = Math.max(lastWallMs, wallMs);
        double guessedServerMs = newestServerMs + Math.min(Math.max(0L, wallMs - newestWallMs) * serverPace, MAX_GUESS_MS);
        double error = guessedServerMs - BUFFER_MS - playMs;
        if (Math.abs(error) > RESYNC_MS) {
            playMs = guessedServerMs - BUFFER_MS;
        } else {
            double pace = Math.max(0.0, Math.min(2.0, serverPace + error / PULL_MS));
            pace = Math.min(pace, Math.max(0.0, (newestServerMs - playMs) / EASE_MS));
            playMs += dt * pace;
        }
        playMs = Math.min(playMs, newestServerMs);
        return (long) playMs;
    }

    /** Same as {@link #now(long)} with the real wall clock. */
    public long now() {
        return now(System.currentTimeMillis());
    }
}
