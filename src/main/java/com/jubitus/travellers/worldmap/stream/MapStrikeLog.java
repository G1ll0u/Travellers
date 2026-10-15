package com.jubitus.travellers.worldmap.stream;

import java.util.UUID;

/**
 * Real strikes (melee hits and arrows, loaded or virtual) waiting to be animated on the maps.
 * Server thread only; a fixed ring that overwrites its oldest entry when full, and records nothing
 * while no player has a map open.
 */
public final class MapStrikeLog {

    public static final byte MELEE = 0;
    public static final byte ARROW = 1;

    private static final int CAPACITY = 512;

    private static final byte[] kind = new byte[CAPACITY];
    private static final int[] dim = new int[CAPACITY];
    private static final long[] tick = new long[CAPACITY];
    private static final UUID[] attacker = new UUID[CAPACITY];
    private static final int[] ax = new int[CAPACITY];
    private static final int[] az = new int[CAPACITY];
    private static final int[] vx = new int[CAPACITY];
    private static final int[] vz = new int[CAPACITY];
    private static int head; // next slot to write
    private static int size;

    private MapStrikeLog() {
    }

    public static boolean isActive() {
        return com.jubitus.travellers.config.TravellersConfig.WORLDMAP.animateFightsOnMap
                && (PopulationMapStream.hasSubscribers() || WorkerMapStream.hasSubscribers());
    }

    /** Positions in block coordinates; {@code tick} is the world time of the strike. */
    public static void record(byte strikeKind, int dimension, long worldTick, UUID attackerId,
                              double attackerX, double attackerZ, double victimX, double victimZ) {
        if (!isActive() || attackerId == null) return;
        int i = head;
        kind[i] = strikeKind;
        dim[i] = dimension;
        tick[i] = worldTick;
        attacker[i] = attackerId;
        ax[i] = (int) Math.floor(attackerX);
        az[i] = (int) Math.floor(attackerZ);
        vx[i] = (int) Math.floor(victimX);
        vz[i] = (int) Math.floor(victimZ);
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) size++;
    }

    public static int size() {
        return size;
    }

    /** Oldest-first access to the {@code n}-th pending strike, 0 <= n < size(). */
    private static int slot(int n) {
        return (head - size + n + CAPACITY) % CAPACITY;
    }

    public static byte kindAt(int n) { return kind[slot(n)]; }
    public static int dimAt(int n) { return dim[slot(n)]; }
    public static long tickAt(int n) { return tick[slot(n)]; }
    public static UUID attackerAt(int n) { return attacker[slot(n)]; }
    public static int attackerXAt(int n) { return ax[slot(n)]; }
    public static int attackerZAt(int n) { return az[slot(n)]; }
    public static int victimXAt(int n) { return vx[slot(n)]; }
    public static int victimZAt(int n) { return vz[slot(n)]; }

    /** Forgets everything pending (called by the sender once it has read the strikes). */
    public static void clear() {
        for (int i = 0; i < CAPACITY; i++) attacker[i] = null;
        head = 0;
        size = 0;
    }
}
