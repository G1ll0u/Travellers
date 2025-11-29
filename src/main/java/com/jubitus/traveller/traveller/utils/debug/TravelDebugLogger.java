package com.jubitus.traveller.traveller.utils.debug;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

public final class TravelDebugLogger {

    // flip this to enable / disable
    public static boolean ENABLED = true;

    private static PrintWriter out;
    private static boolean headerWritten = false;

    private TravelDebugLogger() {
    }

    /**
     * One log entry. `event` is a short code (TICK, WAYPOINT, DETOUR, WALLFOLLOW, ESCAPE, TP, etc.).
     * `note` is any extra text (e.g. which angle you picked, etc.).
     */
    public static void log(
            EntityTraveller mob,
            // AI state snapshot:
            BlockPos target,
            BlockPos currentWaypoint,
            int stallLevel,
            boolean wallFollow,
            int followSide,
            double bestDistSq,
            int ticksSinceBest,
            double projAccum,
            int ticksNoForward,
            int noPathTicks,
            int hardUnstuckCount,
            int hardUnstuckCooldown,
            int postRecoverGuardTicks,
            String event,
            String note
    ) {
        if (!ENABLED) return;
        if (mob.world.isRemote) return; // server only

        ensureOpen(mob.world);
        if (!ENABLED || out == null) return;
        writeHeader();

        World w = mob.world;
        long tick = mob.ticksExisted;
        long worldTime = w.getTotalWorldTime();
        int dim = w.provider.getDimension();
        int id = mob.getEntityId();

        Vec3d pos = mob.getPositionVector();

        double tx = target != null ? target.getX() + 0.5 : Double.NaN;
        double ty = target != null ? target.getY() + 0.0 : Double.NaN;
        double tz = target != null ? target.getZ() + 0.5 : Double.NaN;

        double wx = currentWaypoint != null ? currentWaypoint.getX() + 0.5 : Double.NaN;
        double wy = currentWaypoint != null ? currentWaypoint.getY() + 0.0 : Double.NaN;
        double wz = currentWaypoint != null ? currentWaypoint.getZ() + 0.5 : Double.NaN;

        double distSqToTarget = (target != null)
                ? mob.getDistanceSqToCenter(target)
                : Double.NaN;

        boolean inWater = mob.isInWater();
        boolean inCombat = mob.isInCombat();
        boolean hasPath = !mob.getNavigator().noPath();

        // CSV-ish but with ';' to avoid locale comma clashes
        out.printf(Locale.ROOT,
                "%d;%d;%d;%d;" +  // tick, worldTime, dim, entityId
                        "%.3f;%.3f;%.3f;" + // posX,Y,Z
                        "%.3f;%.3f;%.3f;" + // targetX,Y,Z
                        "%.3f;%.3f;%.3f;" + // waypointX,Y,Z
                        "%.3f;%d;%b;%d;" +  // distSqToTarget, stallLevel, wallFollow, followSide
                        "%.3f;%d;%.3f;%d;" +// bestDistSq, ticksSinceBest, projAccum, ticksNoForward
                        "%d;%d;%d;" +       // noPathTicks, hardUnstuckCount, hardUnstuckCooldown
                        "%d;" +             // postRecoverGuardTicks
                        "%b;%b;%b;" +       // inWater, inCombat, hasPath
                        "%s;%s%n",          // event, note
                tick, worldTime, dim, id,
                pos.x, pos.y, pos.z,
                tx, ty, tz,
                wx, wy, wz,
                distSqToTarget, stallLevel, wallFollow, followSide,
                bestDistSq, ticksSinceBest, projAccum, ticksNoForward,
                noPathTicks, hardUnstuckCount, hardUnstuckCooldown,
                postRecoverGuardTicks,
                inWater, inCombat, hasPath,
                event, note.replace(';', ':')
        );
        out.flush();
    }

    private static void ensureOpen(World w) {
        if (!ENABLED) return;
        if (out != null) return;

        try {
            // Put it in the main run folder as "traveller_debug.csv"
            File file = new File("traveller_debug.csv");
            out = new PrintWriter(new FileWriter(file, true));
        } catch (IOException e) {
            e.printStackTrace();
            ENABLED = false;
        }
    }

    private static void writeHeader() {
        if (!ENABLED || out == null || headerWritten) return;
        headerWritten = true;
        out.println(
                "tick;worldTime;dim;entityId;"
                        + "posX;posY;posZ;"
                        + "targetX;targetY;targetZ;"
                        + "waypointX;waypointY;waypointZ;"
                        + "distSqToTarget;stallLevel;wallFollow;followSide;"
                        + "bestDistSq;ticksSinceBest;projAccum;ticksNoForward;"
                        + "noPathTicks;hardUnstuckCount;hardUnstuckCooldown;"
                        + "postRecoverGuardTicks;"
                        + "inWater;inCombat;hasPath;"
                        + "event;note"
        );
        out.flush();
    }

    public static void close() {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
            headerWritten = false;
        }
    }
}

