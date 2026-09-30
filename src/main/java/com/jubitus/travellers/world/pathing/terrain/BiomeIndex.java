package com.jubitus.travellers.world.pathing.terrain;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable {@code ResourceLocation <-> short} mapping for biome ids stored in region files. One table
 * per save (the biome registry is global), persisted next to the region files as
 * {@code biome-index.tbl} so historical region files stay decodable across mod/pack changes.
 *
 * <p>On a version mismatch the file is ignored and the table is rebuilt from the live registry -
 * names are the real key, so a rebuild only renumbers, and a region file's numeric ids are remapped
 * by name on load (unmapped -> {@link TerrainProfile#BIOME_IX_UNKNOWN}). That remap is the reader's
 * job; this class just owns the name<->id bijection for the current run.
 */
public final class BiomeIndex {

    private static final int MAGIC = 0x54424958; // "TBIX"
    private static final int VERSION = 1;

    private final Object lock = new Object();
    private final Map<String, Short> byName = new HashMap<>();
    private final List<String> byId = new ArrayList<>(); // index == id
    private boolean dirty;

    private BiomeIndex() {
    }

    /**
     * Loads {@code biome-index.tbl} from {@code root} if present, then folds in every biome from the
     * live Forge registry, assigning ids to any that are new. Saves if anything changed.
     */
    public static BiomeIndex buildOrLoad(File root) {
        BiomeIndex idx = new BiomeIndex();
        File file = new File(root, "biome-index.tbl");
        idx.loadFile(file);
        idx.foldInRegistry();
        if (idx.dirty) idx.save(file);
        return idx;
    }

    /** Test hook: an empty in-memory table with no registry fold-in and no file. */
    static BiomeIndex empty() {
        return new BiomeIndex();
    }

    /** Test hook: a table populated only from {@code file} (no registry fold-in). */
    static BiomeIndex fromFileForTest(File file) {
        BiomeIndex idx = new BiomeIndex();
        idx.loadFile(file);
        return idx;
    }

    void loadFile(File file) {
        if (!file.isFile()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != MAGIC) return;
            if (in.readInt() != VERSION) return; // unknown version: rebuild from registry
            int count = in.readInt();
            synchronized (lock) {
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    byId.add(name);
                    byName.put(name, (short) (byId.size() - 1));
                }
            }
        } catch (IOException e) {
            System.err.println("[Travellers/Route] failed to read " + file + ": " + e);
        }
    }

    private void foldInRegistry() {
        try {
            for (Biome b : ForgeRegistries.BIOMES) {
                ResourceLocation rl = b.getRegistryName();
                if (rl != null) idFor(rl);
            }
        } catch (RuntimeException e) {
            System.err.println("[Travellers/Route] biome registry fold-in failed: " + e);
        }
    }

    public void save(File file) {
        List<String> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(byId);
            dirty = false;
        }
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(snapshot.size());
            for (String name : snapshot) out.writeUTF(name);
        } catch (IOException e) {
            System.err.println("[Travellers/Route] failed to write " + file + ": " + e);
        }
    }

    /** Id for a biome name, assigning a new one if unseen. Never negative. */
    public short idFor(ResourceLocation rl) {
        return idFor(rl.toString());
    }

    public short idFor(String name) {
        synchronized (lock) {
            Short id = byName.get(name);
            if (id != null) return id;
            short next = (short) byId.size();
            byId.add(name);
            byName.put(name, next);
            dirty = true;
            return next;
        }
    }

    /** Name for an id, or {@code null} if out of range. */
    @Nullable
    public String nameFor(short id) {
        if (id < 0) return null;
        synchronized (lock) {
            return id < byId.size() ? byId.get(id) : null;
        }
    }

    public int size() {
        synchronized (lock) {
            return byId.size();
        }
    }

    // Mountain-biome membership by name. The Forge biome registry is frozen at runtime, so this set
    // never changes once computed - a benign race on first use is fine (idempotent).
    private volatile java.util.Set<String> mountainNames;

    public boolean isMountainBiome(short id) {
        java.util.Set<String> mn = mountainNames;
        if (mn == null) mn = mountainNames = computeMountainNames();
        String n = nameFor(id);
        return n != null && mn.contains(n);
    }

    private static java.util.Set<String> computeMountainNames() {
        java.util.HashSet<String> s = new java.util.HashSet<>();
        try {
            for (Biome b : ForgeRegistries.BIOMES) {
                try {
                    if (BiomeDictionary.hasType(b, BiomeDictionary.Type.MOUNTAIN)) {
                        ResourceLocation rl = b.getRegistryName();
                        if (rl != null) s.add(rl.toString());
                    }
                } catch (RuntimeException ignored) {
                }
            }
        } catch (RuntimeException e) {
            System.err.println("[Travellers/Route] mountain-biome scan failed: " + e);
        }
        return s;
    }

    /** Test hook: force the mountain-name set (bypasses the Forge registry). */
    void setMountainNamesForTest(java.util.Set<String> names) {
        this.mountainNames = names;
    }
}
