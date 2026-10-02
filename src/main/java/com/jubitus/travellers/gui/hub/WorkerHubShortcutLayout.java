package com.jubitus.travellers.gui.hub;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * Pure math for hub shortcuts: fraction &lt;-&gt; pixel conversion and the "don't land on
 * something else" placement rule.
 *
 * <p>A shortcut's stored position is a fraction of the half-screen relative to the portrait
 * center, not a raw pixel — so it rescales for free with GUI scale/resolution instead of drifting
 * or ending up off-screen.
 *
 * <p><b>Placement is free-form and best-effort, not a reserved grid.</b> A shortcut can go
 * anywhere on screen, including directly above or below the portrait — the only thing avoided is
 * actually overlapping a concrete obstacle (the portrait, the identity text, a currently visible
 * menu button, another shortcut), checked against the tree's *current* shape, not a worst-case
 * reservation. That means a shortcut can end up nudged aside for a session if a branch happens to
 * be open over it, but it is never permanently walled out of most of the screen the way a
 * conservative reserved-zone rule would be — see the caller ({@code GuiWorkerHub}) for how the
 * nudge is applied without touching the persisted anchor.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubShortcutLayout {

    private static final int SEARCH_STEP = 8;
    private static final int SCREEN_MARGIN = 2;

    private WorkerHubShortcutLayout() {}

    /** An axis-aligned obstacle rectangle in screen pixels. */
    public static final class Rect {
        public final int x, y, w, h;

        public Rect(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean intersects(int ox, int oy, int ow, int oh, int margin) {
            return x - margin < ox + ow && x + w + margin > ox
                    && y - margin < oy + oh && y + h + margin > oy;
        }
    }

    public static int pixelCenterX(float fracX, int centerX, int screenW) {
        return centerX + Math.round(fracX * (screenW / 2f));
    }

    public static int pixelCenterY(float fracY, int centerY, int screenH) {
        return centerY + Math.round(fracY * (screenH / 2f));
    }

    public static float fracXOf(int pixelCenterX, int centerX, int screenW) {
        return (pixelCenterX - centerX) / (screenW / 2f);
    }

    public static float fracYOf(int pixelCenterY, int centerY, int screenH) {
        return (pixelCenterY - centerY) / (screenH / 2f);
    }

    public static boolean overlapsAny(int x, int y, int w, int h, List<Rect> obstacles) {
        for (Rect r : obstacles) {
            if (r.intersects(x, y, w, h, 1)) return true;
        }
        return false;
    }

    /**
     * Nearest free top-left pixel to the desired drop point, searching outward ring by ring. Best
     * effort only: if nothing opens up within the search radius (should not happen in practice with
     * this few obstacles), the clamped drop point is returned anyway rather than refusing the
     * player's placement — freedom of placement matters more here than a hard guarantee.
     */
    public static int[] findNearbyFree(int desiredCenterX, int desiredCenterY, int w, int h,
                                       List<Rect> obstacles, int screenW, int screenH) {
        int cx = clamp(desiredCenterX - w / 2, SCREEN_MARGIN, screenW - w - SCREEN_MARGIN);
        int cy = clamp(desiredCenterY - h / 2, SCREEN_MARGIN, screenH - h - SCREEN_MARGIN);
        if (!overlapsAny(cx, cy, w, h, obstacles)) return new int[]{cx, cy};

        int maxRadius = Math.min(800, Math.max(screenW, screenH));
        for (int radius = SEARCH_STEP; radius <= maxRadius; radius += SEARCH_STEP) {
            for (int dx = -radius; dx <= radius; dx += SEARCH_STEP) {
                for (int dy = -radius; dy <= radius; dy += SEARCH_STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue;
                    int x = clamp(cx + dx, SCREEN_MARGIN, screenW - w - SCREEN_MARGIN);
                    int y = clamp(cy + dy, SCREEN_MARGIN, screenH - h - SCREEN_MARGIN);
                    if (!overlapsAny(x, y, w, h, obstacles)) return new int[]{x, y};
                }
            }
        }
        return new int[]{cx, cy};
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
