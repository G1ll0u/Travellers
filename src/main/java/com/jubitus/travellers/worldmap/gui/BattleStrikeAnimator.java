package com.jubitus.travellers.worldmap.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side animation of fight strikes on a map: an arrow flies from archer to target, a melee
 * fighter lunges toward its opponent. Pure logic; the clock is passed in. Strikes are real events
 * from the server (see MapStrikeLog), this class only times and positions them.
 */
public final class BattleStrikeAnimator {

    public static final long ARROW_FLIGHT_MS = 300L;
    public static final long LUNGE_MS = 250L;
    /** Share of the flight the trail covers behind the head. */
    private static final float TRAIL_SHARE = 0.3f;
    private static final int MAX_ARROWS = 64;
    private static final int MAX_LUNGES = 256;
    private static final float MIN_LUNGE_BLOCKS = 0.5f;

    private static final class Arrow {
        float sx, sz, tx, tz;
        int argb;
        long startMs;
    }

    private static final class Lunge {
        float dirX, dirZ;
        long startMs;
    }

    public interface ArrowVisitor {
        /** Block coordinates of the arrow head and of the end of its trail. */
        void arrow(int argb, float headX, float headZ, float tailX, float tailZ);
    }

    private final List<Arrow> arrows = new ArrayList<>();
    private final Map<UUID, Lunge> lunges = new HashMap<>();

    public void addArrow(float fromX, float fromZ, float toX, float toZ, int argb, long startMs) {
        if (arrows.size() >= MAX_ARROWS) return;
        Arrow a = new Arrow();
        a.sx = fromX;
        a.sz = fromZ;
        a.tx = toX;
        a.tz = toZ;
        a.argb = argb;
        a.startMs = startMs;
        arrows.add(a);
    }

    /** A melee strike by {@code attacker}, aimed at the given world point. */
    public void addLunge(UUID attacker, float fromX, float fromZ, float toX, float toZ, long startMs) {
        float dx = toX - fromX;
        float dz = toZ - fromZ;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (attacker == null || len < MIN_LUNGE_BLOCKS) return;
        if (lunges.size() >= MAX_LUNGES && !lunges.containsKey(attacker)) return;
        Lunge l = new Lunge();
        l.dirX = dx / len;
        l.dirZ = dz / len;
        l.startMs = startMs;
        lunges.put(attacker, l);
    }

    public void forEachArrow(long nowMs, ArrowVisitor visitor) {
        Iterator<Arrow> it = arrows.iterator();
        while (it.hasNext()) {
            Arrow a = it.next();
            float p = (nowMs - a.startMs) / (float) ARROW_FLIGHT_MS;
            if (p >= 1f) {
                it.remove();
                continue;
            }
            if (p < 0f) continue; // scheduled, not flying yet
            float tail = Math.max(0f, p - TRAIL_SHARE);
            visitor.arrow(a.argb, lerp(a.sx, a.tx, p), lerp(a.sz, a.tz, p), lerp(a.sx, a.tx, tail), lerp(a.sz, a.tz, tail));
        }
    }

    /**
     * Lunge of a unit right now: out = {dirX, dirZ, amount}, amount 0..1 (out and back).
     * Returns false when the unit is not lunging.
     */
    public boolean lunge(UUID id, long nowMs, float[] out) {
        Lunge l = id == null ? null : lunges.get(id);
        if (l == null) return false;
        float p = (nowMs - l.startMs) / (float) LUNGE_MS;
        if (p >= 1f) {
            lunges.remove(id);
            return false;
        }
        if (p < 0f) return false;
        out[0] = l.dirX;
        out[1] = l.dirZ;
        out[2] = (float) Math.sin(Math.PI * p);
        return true;
    }

    public boolean isIdle() {
        return arrows.isEmpty() && lunges.isEmpty();
    }

    public void clear() {
        arrows.clear();
        lunges.clear();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
