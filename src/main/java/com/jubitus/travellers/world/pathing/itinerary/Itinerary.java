package com.jubitus.travellers.world.pathing.itinerary;

import com.jubitus.travellers.world.pathing.route.TravelPlan;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The route polyline of one travel leg, shared by the loaded and virtual sides. Pure data: XZ
 * vertices (Y is only a hint), one kind per segment, cumulative XZ length per vertex. Immutable, so
 * one instance can be handed across a virtualize/materialize or shared by several units.
 */
public final class Itinerary {

    public static final byte WALK = 0;
    public static final byte ROAD = 1;
    public static final byte BOAT = 2;
    // Threading a village wall gate: inner -> gate -> outer (or the reverse).
    public static final byte GATE = 3;

    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final byte[] kinds;
    private final double[] cum;
    private final BlockPos dest;
    public final byte quality;
    public final String source;

    public Itinerary(int[] xs, int[] ys, int[] zs, byte[] kinds, byte quality, String source) {
        if (xs.length < 2 || ys.length != xs.length || zs.length != xs.length || kinds.length != xs.length - 1) {
            throw new IllegalArgumentException("bad itinerary arrays");
        }
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.kinds = kinds;
        this.quality = quality;
        this.source = source == null ? "" : source;
        this.cum = new double[xs.length];
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1];
            double dz = zs[i] - zs[i - 1];
            cum[i] = cum[i - 1] + Math.sqrt(dx * dx + dz * dz);
        }
        int n = xs.length - 1;
        this.dest = new BlockPos(xs[n], ys[n], zs[n]);
    }

    public static Itinerary straight(BlockPos from, BlockPos to, String source) {
        return new Itinerary(
                new int[]{from.getX(), to.getX()},
                new int[]{from.getY(), to.getY()},
                new int[]{from.getZ(), to.getZ()},
                new byte[]{WALK}, TravelPlan.QUALITY_STRAIGHT, source);
    }

    /** Builds from a vertex list with one kind for every segment. */
    public static Itinerary of(List<BlockPos> pts, byte kind, byte quality, String source) {
        int n = pts.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            BlockPos p = pts.get(i);
            xs[i] = p.getX();
            ys[i] = p.getY();
            zs[i] = p.getZ();
        }
        byte[] kinds = new byte[Math.max(0, n - 1)];
        java.util.Arrays.fill(kinds, kind);
        return new Itinerary(xs, ys, zs, kinds, quality, source);
    }

    public int size() {
        return xs.length;
    }

    public int x(int i) {
        return xs[i];
    }

    public int y(int i) {
        return ys[i];
    }

    public int z(int i) {
        return zs[i];
    }

    public byte kind(int segment) {
        return kinds[segment];
    }

    /** Cumulative XZ length from the first vertex to vertex {@code i}. */
    public double cum(int i) {
        return cum[i];
    }

    public double totalLength() {
        return cum[cum.length - 1];
    }

    public BlockPos dest() {
        return dest;
    }

    public BlockPos vertex(int i) {
        return new BlockPos(xs[i], ys[i], zs[i]);
    }

    /** Index of the first vertex at or after {@code from} with this XZ, or -1. */
    public int indexOfXZ(int x, int z, int from) {
        for (int i = Math.max(0, from); i < xs.length; i++) {
            if (xs[i] == x && zs[i] == z) return i;
        }
        return -1;
    }

    /**
     * Replaces the {@code start..end} run (matched by XZ) with the interior of {@code poly}, as WALK
     * segments. The poly may come from a cache shared per 128-block bucket, so its ends can be another
     * unit's: it is trimmed to the vertices nearest {@code start} and {@code end} first. Returns null
     * when either anchor is gone (the itinerary was rebuilt meanwhile).
     */
    @Nullable
    public Itinerary withSplice(BlockPos start, BlockPos end, List<BlockPos> poly, byte polyQuality) {
        return withSplice(start, end, poly, null, polyQuality);
    }

    /** Same, with one kind per poly segment ({@code null} = all WALK). */
    @Nullable
    public Itinerary withSplice(BlockPos start, BlockPos end, List<BlockPos> poly, @Nullable byte[] polyKinds,
                                byte polyQuality) {
        if (poly == null || poly.size() < 2) return null;
        int si = indexOfXZ(start.getX(), start.getZ(), 0);
        if (si < 0) return null;
        int ei = indexOfXZ(end.getX(), end.getZ(), si + 1);
        if (ei < 0) return null;

        int i0 = nearestIndex(poly, start.getX(), start.getZ(), 0, poly.size());
        int i1 = nearestIndex(poly, end.getX(), end.getZ(), i0, poly.size());
        int interior = Math.max(0, i1 - i0 - 1);

        int n = si + 1 + interior + (xs.length - ei);
        int[] nx = new int[n], ny = new int[n], nz = new int[n];
        byte[] nk = new byte[n - 1];
        int w = 0;
        for (int i = 0; i <= si; i++, w++) {
            nx[w] = xs[i];
            ny[w] = ys[i];
            nz[w] = zs[i];
            if (i < si) nk[w] = kinds[i];
        }
        for (int k = i0 + 1; k < i1; k++, w++) {
            BlockPos p = poly.get(k);
            nx[w] = p.getX();
            ny[w] = p.getY();
            nz[w] = p.getZ();
            nk[w - 1] = polyKind(polyKinds, k - 1);
        }
        byte joinKind = polyKind(polyKinds, i1 - 1);
        for (int i = ei; i < xs.length; i++, w++) {
            nx[w] = xs[i];
            ny[w] = ys[i];
            nz[w] = zs[i];
            if (w > 0 && i == ei) nk[w - 1] = joinKind;
            else if (i > ei) nk[w - 1] = kinds[i - 1];
        }
        byte q = (byte) Math.max(quality, polyQuality);
        return new Itinerary(nx, ny, nz, nk, q, source);
    }

    private static byte polyKind(@Nullable byte[] kinds, int segment) {
        return kinds != null && segment >= 0 && segment < kinds.length ? kinds[segment] : WALK;
    }

    private static int nearestIndex(List<BlockPos> pts, int x, int z, int from, int to) {
        int best = from;
        long bestD = Long.MAX_VALUE;
        for (int i = from; i < to; i++) {
            long dx = pts.get(i).getX() - x;
            long dz = pts.get(i).getZ() - z;
            long d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Short run summary for traces, e.g. {@code ROAD:3 WALK:2 BOAT:1}. */
    public String kindsSummary() {
        int roadRuns = 0, walkRuns = 0, boatRuns = 0, gateRuns = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (i > 0 && kinds[i] == kinds[i - 1]) continue;
            if (kinds[i] == ROAD) roadRuns++;
            else if (kinds[i] == BOAT) boatRuns++;
            else if (kinds[i] == GATE) gateRuns++;
            else walkRuns++;
        }
        return "ROAD:" + roadRuns + " WALK:" + walkRuns + (boatRuns > 0 ? " BOAT:" + boatRuns : "")
                + (gateRuns > 0 ? " GATE:" + gateRuns : "");
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        int[] v = new int[xs.length * 3];
        for (int i = 0; i < xs.length; i++) {
            v[i * 3] = xs[i];
            v[i * 3 + 1] = ys[i];
            v[i * 3 + 2] = zs[i];
        }
        tag.setIntArray("V", v);
        tag.setByteArray("K", kinds);
        tag.setByte("Q", quality);
        tag.setString("S", source);
        return tag;
    }

    @Nullable
    public static Itinerary fromNbt(NBTTagCompound tag) {
        int[] v = tag.getIntArray("V");
        byte[] k = tag.getByteArray("K");
        int n = v.length / 3;
        if (n < 2 || v.length != n * 3 || k.length != n - 1) return null;
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = v[i * 3];
            ys[i] = v[i * 3 + 1];
            zs[i] = v[i * 3 + 2];
        }
        return new Itinerary(xs, ys, zs, k.clone(), tag.getByte("Q"), tag.getString("S"));
    }
}
