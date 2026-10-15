package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.net.TravellersNetwork;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import java.util.zip.Deflater;

/**
 * Cost counters of the map streams (server thread only): time per second-tick, packets and bytes
 * sent. Off until a session is started with {@code /travellersdebug map streams start}; while off,
 * every hook is a flag check and packets are sent untouched.
 */
public final class MapStreamStats {

    public enum Stream {
        POPULATION, FOREIGN, WORKER, BATTLE, STRIKES,
        /** Vision-source scan; its time is already included in FOREIGN and BATTLE. */
        VISION
    }

    private static final int N = Stream.values().length;
    private static final long[] ticks = new long[N];
    private static final long[] nanos = new long[N];
    private static final long[] maxNanos = new long[N];
    private static final long[] packets = new long[N];
    private static final long[] rawBytes = new long[N];
    private static final long[] deflatedBytes = new long[N];
    private static final long[] maxPacketBytes = new long[N];
    private static boolean enabled;
    private static long startedAtMs;
    private static long stoppedAtMs;

    private MapStreamStats() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void start() {
        reset();
        enabled = true;
        startedAtMs = System.currentTimeMillis();
    }

    public static void stop() {
        if (enabled) stoppedAtMs = System.currentTimeMillis();
        enabled = false;
    }

    private static void reset() {
        java.util.Arrays.fill(ticks, 0);
        java.util.Arrays.fill(nanos, 0);
        java.util.Arrays.fill(maxNanos, 0);
        java.util.Arrays.fill(packets, 0);
        java.util.Arrays.fill(rawBytes, 0);
        java.util.Arrays.fill(deflatedBytes, 0);
        java.util.Arrays.fill(maxPacketBytes, 0);
        startedAtMs = 0;
        stoppedAtMs = 0;
    }

    /** Returns a start stamp for {@link #end}; 0 while no session runs. */
    static long begin() {
        return enabled ? System.nanoTime() : 0L;
    }

    static void end(Stream s, long startStamp) {
        if (startStamp == 0L || !enabled) return;
        long d = System.nanoTime() - startStamp;
        addTime(s, d);
    }

    private static void addTime(Stream s, long d) {
        int i = s.ordinal();
        ticks[i]++;
        nanos[i] += d;
        if (d > maxNanos[i]) maxNanos[i] = d;
    }

    /** Sends the packet; while a session runs, also records its size (raw and deflated). */
    static void send(Stream s, EntityPlayerMP player, IMessage msg) {
        if (enabled) {
            long t0 = System.nanoTime();
            ByteBuf buf = Unpooled.buffer();
            try {
                msg.toBytes(buf);
                byte[] raw = new byte[buf.readableBytes()];
                buf.readBytes(raw);
                int i = s.ordinal();
                packets[i]++;
                rawBytes[i] += raw.length;
                if (raw.length > maxPacketBytes[i]) maxPacketBytes[i] = raw.length;
                deflatedBytes[i] += deflatedSize(raw);
            } finally {
                buf.release();
            }
            // Measuring is not part of the stream's own cost: take it back out of the stream time.
            nanos[s.ordinal()] -= System.nanoTime() - t0;
        }
        TravellersNetwork.sendToPlayer(player, msg);
    }

    private static int deflatedSize(byte[] raw) {
        Deflater d = new Deflater();
        try {
            d.setInput(raw);
            d.finish();
            byte[] sink = new byte[8192];
            int total = 0;
            while (!d.finished()) total += d.deflate(sink);
            return total;
        } finally {
            d.end();
        }
    }

    /** One line per stream, ready for chat. */
    public static java.util.List<String> report() {
        java.util.List<String> out = new java.util.ArrayList<>();
        long end = enabled ? System.currentTimeMillis() : stoppedAtMs;
        double secs = startedAtMs == 0 ? 0 : Math.max(1, end - startedAtMs) / 1000.0;
        out.add(String.format("Map streams %s, %.0f s measured",
                enabled ? "(running)" : "(stopped)", secs));
        for (Stream s : Stream.values()) {
            int i = s.ordinal();
            if (ticks[i] == 0 && packets[i] == 0) {
                out.add("  " + s + ": no activity");
                continue;
            }
            double avgMs = ticks[i] == 0 ? 0 : nanos[i] / 1e6 / ticks[i];
            String line = String.format("  %s: %.2f ms/s avg, %.1f ms max", s, avgMs, maxNanos[i] / 1e6);
            if (packets[i] > 0) {
                line += String.format(" | %d packets, avg %d B (max %d B), %.1f KB/s raw, %.1f KB/s deflated",
                        packets[i], rawBytes[i] / packets[i], maxPacketBytes[i],
                        rawBytes[i] / 1024.0 / secs, deflatedBytes[i] / 1024.0 / secs);
            }
            out.add(line);
        }
        return out;
    }
}
