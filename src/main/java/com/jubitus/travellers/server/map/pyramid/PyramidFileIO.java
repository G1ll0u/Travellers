package com.jubitus.travellers.server.map.pyramid;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Plain framed binary file for one pyramid level's tiles — deliberately not routed through
 * NBT/{@code WorldSavedData} (which vanilla always gzip-compresses synchronously on the calling
 * thread at every save-all). Tile bytes are already deflate-compressed by
 * {@link TileCompression} before reaching here, so this layer adds no compression of its own.
 */
final class PyramidFileIO {
    private PyramidFileIO() {}

    static final class Loaded {
        final int migrationVersion;
        final Map<Long, byte[]> compressedTiles;
        Loaded(int migrationVersion, Map<Long, byte[]> compressedTiles) {
            this.migrationVersion = migrationVersion;
            this.compressedTiles = compressedTiles;
        }
    }

    static Loaded read(File file) {
        if (!file.isFile()) return new Loaded(0, new HashMap<>());
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int migrationVersion = in.readInt();
            int count = in.readInt();
            Map<Long, byte[]> tiles = new HashMap<>(Math.max(16, count * 2));
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                int len = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                tiles.put(key, data);
            }
            return new Loaded(migrationVersion, tiles);
        } catch (IOException e) {
            // Corrupt/partial file (e.g. a crash mid-write, before the rename below could land)
            // — treat as empty rather than failing world load; tiles simply regenerate over time.
            System.err.println("[Travellers] Discarding unreadable worldmap tile file " + file + ": " + e);
            return new Loaded(0, new HashMap<>());
        }
    }

    /** Writes to a temp file then renames over the target, so a crash mid-write can't corrupt it. */
    static void write(File file, Map<Long, byte[]> compressedTiles, int migrationVersion) {
        File dir = file.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dir, file.getName() + ".tmp");

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(migrationVersion);
            out.writeInt(compressedTiles.size());
            for (Map.Entry<Long, byte[]> e : compressedTiles.entrySet()) {
                out.writeLong(e.getKey());
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
        } catch (IOException e) {
            System.err.println("[Travellers] Failed to write worldmap tile file " + file + ": " + e);
            return;
        }

        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
    }
}
