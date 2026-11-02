package com.jubitus.travellers.debug.path;

import com.jubitus.travellers.debug.trace.TerrainSnapshot;
import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the {@code /travellersdebug path export} file: the real route a mounted (or on-foot) test
 * entity actually walked, next to the route the player marked by hand, each point annotated with a
 * {@link TerrainSnapshot} - built so a reader (human or LLM, in another session, without access to
 * the live world) can judge whether the pathfinding made a reasonable choice.
 */
public final class PathExporter {

    private PathExporter() {
    }

    private static final Pattern MOVE_POS = Pattern.compile("move:pos=(-?[0-9.]+),(-?[0-9.]+),(-?[0-9.]+)");
    /** Points closer together than this along the real route are collapsed - a marker/line every few
     * ticks would be unreadable, a few blocks apart is enough to see the shape of the route. */
    private static final double MIN_STEP_BLOCKS = 3.0D;

    public static File traceFolder(MinecraftServer server) {
        return com.jubitus.travellers.debug.DebugFiles.dir(com.jubitus.travellers.debug.DebugFiles.TRACE);
    }

    private static File pathsFolder(MinecraftServer server) {
        File dir = com.jubitus.travellers.debug.DebugFiles.dir(com.jubitus.travellers.debug.DebugFiles.PATHS);
        return dir;
    }

    /** Resolves a trace file argument: a bare filename is looked up under logs/travellers/trace,
     * anything else is tried as a path as-given. */
    public static File resolveTraceFile(MinecraftServer server, String fileArg) throws CommandException {
        File direct = new File(fileArg);
        if (direct.isFile()) return direct;
        File byName = new File(traceFolder(server), fileArg);
        if (byName.isFile()) return byName;
        throw new CommandException("Trace file not found: " + fileArg
                + " (looked in " + traceFolder(server).getAbsolutePath() + ")");
    }

    /** Every {@code move:pos=x,y,z} position in the file, subsampled to roughly one point every
     * {@link #MIN_STEP_BLOCKS} blocks of real displacement. */
    public static List<Vec3d> readTracePositions(MinecraftServer server, String fileArg) throws CommandException {
        File file = resolveTraceFile(server, fileArg);
        List<Vec3d> all = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = MOVE_POS.matcher(line);
                if (!m.find()) continue;
                all.add(new Vec3d(Double.parseDouble(m.group(1)),
                        Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))));
            }
        } catch (IOException e) {
            throw new CommandException("Could not read trace file: " + e.getMessage());
        }
        return subsample(all);
    }

    private static List<Vec3d> subsample(List<Vec3d> all) {
        List<Vec3d> out = new ArrayList<>();
        Vec3d last = null;
        for (Vec3d p : all) {
            if (last == null || p.distanceTo(last) >= MIN_STEP_BLOCKS) {
                out.add(p);
                last = p;
            }
        }
        return out;
    }

    /**
     * Writes the comparison file. {@code traceFileArg} overrides which trace file backs the "real
     * path" section; null falls back to the last {@code path test} run by this player. Returns the
     * absolute path of the written file.
     */
    public static String export(EntityPlayerMP player, String name, String traceFileArg) throws CommandException {
        PathTestManager.State st = PathTestManager.state(player);
        MinecraftServer server = player.getServer();
        World world = player.world;

        String traceFileName = traceFileArg;
        if (traceFileName == null) {
            if (st.lastTraceFile == null) {
                throw new CommandException("No trace file yet - run 'path test' first, or pass a trace file name.");
            }
            traceFileName = st.lastTraceFile.getName();
        }
        List<Vec3d> realPath = readTracePositions(server, traceFileName);

        String safeName = name.replaceAll("[^A-Za-z0-9_-]", "_");
        File outFile = new File(pathsFolder(server), safeName + ".txt");

        try (PrintWriter out = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {

            out.println("# travellers path comparison v1");
            out.println("# generated=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                    + " player=" + player.getName());
            out.println("# pos1=" + posOrNone(st.pos1) + " pos2=" + posOrNone(st.pos2)
                    + " mounted=" + st.lastMounted);
            out.println("# real path source: " + traceFileName + " (" + realPath.size() + " points after subsampling)");
            out.println("# ideal path source: " + st.marks.size() + " hand-marked point(s)");
            out.println("#");
            out.println("# Each point below is followed by a local terrain snapshot: the first grid is the");
            out.println("# floor height of each column relative to that point's feet (+2 = wall, -3 = drop,");
            out.println("# .. = no floor found), the second is free blocks above that floor. Rows run");
            out.println("# north..south, columns west..east, centred on the point.");
            out.println();

            out.println("=== REAL PATH (" + (st.lastMounted ? "mounted" : "on foot") + ") ===");
            writePointList(out, world, realPathAsPoints(realPath));

            out.println();
            out.println("=== IDEAL PATH (hand-marked) ===");
            writePointList(out, world, st.marks);

        } catch (IOException e) {
            throw new CommandException("Failed to write export file: " + e.getMessage());
        }

        return outFile.getAbsolutePath();
    }

    private static List<BlockPos> realPathAsPoints(List<Vec3d> real) {
        List<BlockPos> out = new ArrayList<>(real.size());
        for (Vec3d v : real) out.add(new BlockPos(v.x, v.y, v.z));
        return out;
    }

    private static void writePointList(PrintWriter out, World world, List<BlockPos> points) {
        if (points.isEmpty()) {
            out.println("(no points)");
            return;
        }
        int i = 0;
        for (BlockPos p : points) {
            i++;
            out.println(String.format(Locale.ROOT, "-- point %d: %d,%d,%d", i, p.getX(), p.getY(), p.getZ()));
            int feetY = MathHelper.floor(p.getY());
            for (String line : TerrainSnapshot.lines(world, p.getX(), feetY, p.getZ(), 0.0F)) {
                out.println("   " + line);
            }
        }
    }

    private static String posOrNone(BlockPos p) {
        return p == null ? "none" : p.getX() + "," + p.getY() + "," + p.getZ();
    }
}
