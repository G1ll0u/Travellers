package com.jubitus.travellers.server.map.pyramid;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Deflate compression for tile tone arrays, shared by NBT persistence and network payloads.
 * Tone data is mostly large flat regions (grass/water plains), so it compresses very well.
 */
public final class TileCompression {
    private TileCompression() {}

    public static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    public static byte[] inflate(byte[] compressed, int expectedLength) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        byte[] result = new byte[expectedLength];
        try {
            int totalRead = 0;
            while (!inflater.finished() && totalRead < expectedLength) {
                int n = inflater.inflate(result, totalRead, expectedLength - totalRead);
                if (n == 0 && inflater.needsInput()) break;
                totalRead += n;
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new RuntimeException("Corrupt tile data", e);
        } finally {
            inflater.end();
        }
        return result;
    }
}
