package com.jubitus.travellers.pathing.terrain;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Framed binary file per region: {@code <dim>/r.<rx>.<rz>.tpg}. All reads and writes run on one
 * dedicated daemon thread (performance contract §2.4) - never the server thread.
 *
 * <p>Lazily (re)created like {@code worldmap/pyramid/PyramidIOExecutor}: an integrated server
 * quitting to title then loading another world runs a fresh {@code IntegratedServer} in the same
 * JVM, and {@link #shutdownAndAwait()} permanently terminates the executor at server stop.
 */
public final class TerrainProfileIO {

    private static final int MAGIC = 0x54504731; // "TPG1"
    private static final int VERSION = 1;
    private static final int FLAG_DEFLATED = 1;
    private static final int N = TerrainProfile.CELLS_PER_REGION;
    private static final int RAW_BYTES = N * 7; // 2 + 1 + 1 + 1 + 2 per cell

    private static ExecutorService exec;
    private static final AtomicBoolean UNKNOWN_VERSION_LOGGED = new AtomicBoolean(false);

    private TerrainProfileIO() {
    }

    private static synchronized ExecutorService ensureRunning() {
        if (exec == null || exec.isShutdown()) {
            exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Travellers-TerrainIO");
                t.setDaemon(true);
                return t;
            });
        }
        return exec;
    }

    /** Asynchronously reads a region file (or synthesises an EMPTY region if absent/corrupt) and hands it to {@code onLoaded} on the IO thread. */
    public static void loadAsync(File file, int regionX, int regionZ, Consumer<TerrainProfileRegion> onLoaded) {
        ensureRunning().submit(() -> {
            TerrainProfileRegion region;
            try {
                region = readSync(file, regionX, regionZ);
            } catch (Exception ex) {
                System.err.println("[Travellers/Route] terrain region read failed for " + file + ": " + ex);
                region = new TerrainProfileRegion(regionX, regionZ);
            }
            onLoaded.accept(region);
        });
    }

    /** Asynchronously writes a snapshot taken on the server thread. */
    public static void writeAsync(File file, TerrainProfileRegion.Snapshot snapshot) {
        ensureRunning().submit(() -> {
            try {
                writeSync(file, snapshot);
            } catch (Exception ex) {
                System.err.println("[Travellers/Route] terrain region write failed for " + file + ": " + ex);
            }
        });
    }

    /** Waits for in-flight terrain IO to finish - call once, at server shutdown. */
    public static synchronized void shutdownAndAwait() {
        if (exec == null) return;
        exec.shutdown();
        try {
            exec.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------

    static TerrainProfileRegion readSync(File file, int regionX, int regionZ) throws IOException {
        if (!file.isFile()) return new TerrainProfileRegion(regionX, regionZ);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return emptyLogged(regionX, regionZ, "bad magic in " + file);
            int version = in.readInt();
            int cellSize = in.readInt();
            int regionCells = in.readInt();
            int structBytes = in.readInt();
            if (version != VERSION || cellSize != TerrainProfile.CELL_SIZE
                    || regionCells != TerrainProfile.REGION_CELLS || structBytes != 7) {
                if (UNKNOWN_VERSION_LOGGED.compareAndSet(false, true)) {
                    System.err.println("[Travellers/Route] terrain region file " + file
                            + " has an unknown format (v" + version + ", cell " + cellSize + ", region "
                            + regionCells + ") - treating as empty. Further such files will not be logged.");
                }
                return new TerrainProfileRegion(regionX, regionZ);
            }
            int flags = in.readInt();
            int payloadLen = in.readInt();
            byte[] payload = new byte[payloadLen];
            in.readFully(payload);

            byte[] raw;
            if ((flags & FLAG_DEFLATED) != 0) {
                raw = inflate(payload);
            } else {
                raw = payload;
            }
            if (raw.length != RAW_BYTES) return emptyLogged(regionX, regionZ, "wrong payload size in " + file);

            ByteBuffer buf = ByteBuffer.wrap(raw);
            short[] heightMean = new short[N];
            byte[] amplitude = new byte[N];
            byte[] water = new byte[N];
            byte[] source = new byte[N];
            short[] biomeIx = new short[N];
            for (int i = 0; i < N; i++) heightMean[i] = buf.getShort();
            buf.get(amplitude);
            buf.get(water);
            buf.get(source);
            for (int i = 0; i < N; i++) biomeIx[i] = buf.getShort();
            return new TerrainProfileRegion(regionX, regionZ, heightMean, amplitude, water, source, biomeIx);
        }
    }

    static void writeSync(File file, TerrainProfileRegion.Snapshot s) throws IOException {
        byte[] raw = new byte[RAW_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(raw);
        for (int i = 0; i < N; i++) buf.putShort(s.heightMean[i]);
        buf.put(s.amplitude);
        buf.put(s.water);
        buf.put(s.source);
        for (int i = 0; i < N; i++) buf.putShort(s.biomeIx[i]);

        byte[] deflated = deflate(raw);

        File parent = file.getParentFile();
        if (parent != null) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(file.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(TerrainProfile.CELL_SIZE);
            out.writeInt(TerrainProfile.REGION_CELLS);
            out.writeInt(7);
            out.writeInt(FLAG_DEFLATED);
            out.writeInt(deflated.length);
            out.write(deflated);
        }
        if (file.exists() && !file.delete()) {
            // fall through - rename may still succeed on some filesystems
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("could not rename " + tmp + " -> " + file);
        }
    }

    private static TerrainProfileRegion emptyLogged(int rx, int rz, String why) {
        System.err.println("[Travellers/Route] " + why + " - treating region as empty");
        return new TerrainProfileRegion(rx, rz);
    }

    private static byte[] deflate(byte[] raw) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(raw.length / 4);
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, def)) {
            dos.write(raw);
        } finally {
            def.end();
        }
        return bos.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        byte[] out = new byte[RAW_BYTES];
        try (InflaterInputStream iis = new InflaterInputStream(new java.io.ByteArrayInputStream(compressed))) {
            int off = 0;
            int read;
            while (off < out.length && (read = iis.read(out, off, out.length - off)) != -1) {
                off += read;
            }
            if (off != out.length) throw new IOException("short inflate: " + off + " / " + out.length);
        }
        return out;
    }
}
