package com.jubitus.travellers.combat.ai;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

/** Pure arrow maths for the bow AI: lead, launch angle, lob arc, target velocity smoothing. */
final class BowBallistics {

    static final float ARROW_VEL = 3.2F;
    // Ballistic "clear the blocker" solutions run from a flat arc up to near-vertical mortar fire as
    // the blocker gets closer - past this launch angle it stops reading as an arc and starts reading
    // as a rain of arrows, so it's treated the same as "no arc can clear this" (falls back to sidestep).
    static final double LOB_MAX_LAUNCH_ANGLE_DEG = 55.0D;
    private static final double LOB_CLEARANCE_MARGIN = 0.6D; // extra headroom above the blocker's AABB
    private static final double LEAD_GAIN_LATERAL = 1.18; // 1.10–1.25: sideways runners
    private static final double LEAD_GAIN_RADIAL = 1.06; // 1.00–1.10: toward/away bias
    private static final double RELEASE_LATENCY_TICKS = 2.0; // add ~2 ticks to time-of-flight
    private static final int LEAD_ITERS = 3; // 2→3 helps convergence for long shots

    private BowBallistics() {
    }

    /** Where to point the arrow: the shot vector (dx, dy, dz) and the horizontal range to the aim point. */
    static final class Shot {
        final double dx, dy, dz, horizontalRange;

        Shot(double dx, double dy, double dz, double horizontalRange) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.horizontalRange = horizontalRange;
        }
    }

    /** A high arc that clears a blocker: launch angle, arc height over the blocker, and the arc midpoint. */
    static final class LobArc {
        final double launchAngleDeg, arcY, midX, midZ;

        LobArc(double launchAngleDeg, double arcY, double midX, double midZ) {
            this.launchAngleDeg = launchAngleDeg;
            this.arcY = arcY;
            this.midX = midX;
            this.midZ = midZ;
        }
    }

    static Shot solveShot(double sx, double sy, double sz, double bx, double by, double bz, Vec3d vT,
                          boolean lofted) {
        // Iterate lead using actual flight time from ballistic solve
        final double g = 0.05D;            // vanilla arrow gravity (~1.12)
        final double v = ARROW_VEL;
        double aimX = bx, aimY = by, aimZ = bz;

        for (int i = 0; i < LEAD_ITERS; i++) {
            // Vector from shooter to current aim
            double rx = aimX - sx, ry = aimY - sy, rz = aimZ - sz;
            double xh = Math.sqrt(rx * rx + rz * rz); // horizontal range

            // Unit forward & lateral on XZ
            double ux = (xh > 1e-6) ? rx / xh : 0.0;
            double uz = (xh > 1e-6) ? rz / xh : 0.0;
            double lx = -uz, lz = ux;

            // Decompose target horizontal velocity
            double vr = vT.x * ux + vT.z * uz; // toward/away
            double vl = vT.x * lx + vT.z * lz; // sideways

            // Gentle gains → slight over-lead predictable motion
            vr *= LEAD_GAIN_RADIAL;
            vl *= LEAD_GAIN_LATERAL;

            // Recompose gained velocity + damp vertical (jumping is noisy)
            double gvx = vr * ux + vl * lx;
            double gvz = vr * uz + vl * lz;
            double gvy = vT.y * 0.5;

            // Lower-arc ballistic solve: tanθ = (v^2 - √(v^4 - g(gx^2 + 2y v^2)))/(g x)
            double v2 = v * v;
            double disc = (v2 * v2) - g * (g * xh * xh + 2.0D * ry * v2);

            double tanTheta, cosTheta;
            if (disc > 0.0D && xh > 1.0e-6D) {
                double root = Math.sqrt(disc);
                tanTheta = (v2 - root) / (g * xh);
                cosTheta = 1.0 / Math.sqrt(1.0 + tanTheta * tanTheta);
            } else {
                // shallow fallback when too close/steep
                tanTheta = 0.06;
                cosTheta = 1.0 / Math.sqrt(1.0 + tanTheta * tanTheta);
            }

            // Actual flight time + small release latency
            double t = xh / (v * cosTheta);
            t += RELEASE_LATENCY_TICKS;
            t = Math.max(0.0, Math.min(2.0, t));

            // Predict target at that time with the gained velocity
            aimX = bx + gvx * t;
            aimY = by + gvy * t;
            aimZ = bz + gvz * t;
        }

        // Final deltas
        double fx = aimX - sx;
        double fy = aimY - sy;
        double fz = aimZ - sz;
        double xh = Math.sqrt(fx * fx + fz * fz);

        // Final elevation: the low arc by default, or the high ("en cloche") arc when lofting over a
        // blocking ally - same quadratic, just the other root.
        double v2 = v * v;
        double disc = (v2 * v2) - g * (g * xh * xh + 2.0D * fy * v2);
        if (disc > 0.0D && xh > 1.0e-6D) {
            double root = Math.sqrt(disc);
            double tanTheta = lofted ? (v2 + root) / (g * xh) : (v2 - root) / (g * xh);
            if (lofted) {
                // Guard against the lead-adjusted aim point pushing past what was actually validated
                // as "an arc, not a mortar" in solveLob (which checked the raw target point).
                double tanCap = Math.tan(Math.toRadians(LOB_MAX_LAUNCH_ANGLE_DEG));
                if (tanTheta > tanCap) tanTheta = tanCap;
            }
            aimY = tanTheta * xh;
        } else {
            aimY = fy + xh * 0.06D;
        }
        return new Shot(fx, aimY, fz, xh);
    }

    /** The high arc from the shooter to the target point that clears the blocker, or null if none does. */
    @Nullable
    static LobArc solveLob(double sx, double sy, double sz, double bx, double by, double bz,
                           double blockerX, double blockerZ, double blockerMaxY) {
        double fx = bx - sx, fy = by - sy, fz = bz - sz;
        double xh = Math.sqrt(fx * fx + fz * fz);
        if (xh < 1.0E-6D) return null;

        double g = 0.05D, v = ARROW_VEL, v2 = v * v;
        double disc = v2 * v2 - g * (g * xh * xh + 2.0D * fy * v2);
        if (disc <= 0.0D) return null; // target out of ballistic range for this speed
        double tanHigh = (v2 + Math.sqrt(disc)) / (g * xh);

        double angleDeg = Math.toDegrees(Math.atan(tanHigh));
        if (angleDeg > LOB_MAX_LAUNCH_ANGLE_DEG) return null; // would read as a mortar, not an arc

        // Blocker's position projected onto the shot line.
        double ux = fx / xh, uz = fz / xh;
        double distAlong = (blockerX - sx) * ux + (blockerZ - sz) * uz;
        if (distAlong <= 0.2D || distAlong >= xh - 0.2D) return null; // not really between shooter and target

        double clearance = tanHigh * distAlong
                - g * distAlong * distAlong * (1.0D + tanHigh * tanHigh) / (2.0D * v2);
        double arcY = sy + clearance;

        double requiredY = blockerMaxY + LOB_CLEARANCE_MARGIN;
        if (arcY < requiredY) return null;

        double midX = sx + fx * (distAlong / xh) * 0.5D;
        double midZ = sz + fz * (distAlong / xh) * 0.5D;
        return new LobArc(angleDeg, arcY, midX, midZ);
    }

    /** Per-archer smoothing of the target's velocity over consecutive ticks. */
    static final class VelocityTracker {
        @Nullable
        private java.util.UUID lastTargetId = null;
        private double lastVX = 0, lastVY = 0, lastVZ = 0;
        private int lastVelTick = -1000;

        Vec3d sample(EntityLivingBase t, int nowTick) {
            double vx = (com.jubitus.travellers.unit.EntityMotion.velX(t));
            double vy = (com.jubitus.travellers.unit.EntityMotion.velY(t));
            double vz = (com.jubitus.travellers.unit.EntityMotion.velZ(t));

            java.util.UUID id = t.getUniqueID();
            if (lastTargetId != null && lastTargetId.equals(id) && nowTick == lastVelTick + 1) {
                double a = 0.60; // 60% previous sample, smooths jitter
                vx = a * lastVX + (1.0 - a) * vx;
                vy = a * lastVY + (1.0 - a) * vy;
                vz = a * lastVZ + (1.0 - a) * vz;
            }
            lastTargetId = id;
            lastVX = vx;
            lastVY = vy;
            lastVZ = vz;
            lastVelTick = nowTick;
            return new Vec3d(vx, vy, vz);
        }
    }
}
