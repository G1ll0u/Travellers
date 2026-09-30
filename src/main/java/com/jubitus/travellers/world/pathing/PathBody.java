package com.jubitus.travellers.world.pathing;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.Entity;

/**
 * The single answer to "which body actually has to fit through the world".
 *
 * <p>Vanilla sizes every part of navigation from whichever entity asked for the path: the cells a
 * step may occupy ({@code NodeProcessor.init} uses {@code floor(width + 1)}), the point aimed at
 * inside a node ({@code Path.getVectorFromIndex} uses {@code (int)(width + 1) * 0.5}), the arrival
 * radius, the line-of-sight shortcut. For a mounted traveller that entity is the rider - 0.6 wide,
 * one cell, aimed straight down the line of block centres - while the body that actually collides
 * is the horse, 1.4 wide and needing a two-cell footprint. Every "wedged against a trunk it was
 * walking past" report follows from that single mismatch.
 *
 * <p>Rather than a mount-aware special case at each of those sites, they all ask here. On foot this
 * returns the entity itself, so nothing changes; mounted, it returns the horse and the whole chain
 * becomes consistent by construction.
 */
public final class PathBody {

    private PathBody() {
    }

    /** The horse while its rider is in the saddle, otherwise the entity itself. Never null. */
    public static Entity of(Entity e) {
        if (e instanceof EntityTraveller && ((EntityTraveller) e).isMountedOnBondedMount()) {
            Entity ride = e.getRidingEntity();
            if (ride != null) return ride;
        }
        return e;
    }

    /** True when the moving body is something other than the entity being asked - i.e. mounted. */
    public static boolean isMounted(Entity e) {
        return of(e) != e;
    }

    public static float width(Entity e) {
        return of(e).width;
    }

    public static float height(Entity e) {
        return of(e).height;
    }

    /**
     * Total vertical space that must be free above the ground for this body to pass - the horse's own
     * body <em>and</em> the rider sitting on it, which is taller than either alone.
     *
     * <p>The rider's seat height is taken from where {@code AbstractHorse.updatePassenger} would
     * actually place it, so this is the real silhouette rather than a guessed sum. Sizing clearance
     * from the horse alone is what let a mounted traveller be routed under a 2-block overhang and
     * bang its rider's head on it.
     */
    public static double clearanceHeight(Entity e) {
        Entity body = of(e);
        if (body == e) return e.height;
        double riderTop = body.getMountedYOffset() + e.getYOffset() + e.height;
        return Math.max(body.height, riderTop);
    }

    public static float stepHeight(Entity e) {
        return of(e).stepHeight;
    }

    /** Real feet height of the moving body. A rider's own bounding box sits ~0.78 above the ground. */
    public static double feetY(Entity e) {
        return of(e).getEntityBoundingBox().minY;
    }

    public static boolean onGround(Entity e) {
        return of(e).onGround;
    }

    public static boolean isInWater(Entity e) {
        return of(e).isInWater();
    }

    /**
     * Half-width offset from a path node's min corner to the centre of the body's footprint,
     * matching what {@code NodeProcessor} scans and what {@code Path.getVectorFromIndex} would
     * compute for this body. One cell (0.5) on foot, two cells (1.0) on a horse.
     */
    public static double nodeCentreOffset(Entity e) {
        return (int) (width(e) + 1.0F) * 0.5D;
    }

    /** Footprint side in whole cells - the same value {@code NodeProcessor.init} derives. */
    public static int footprintCells(Entity e) {
        return (int) (width(e) + 1.0F);
    }
}
