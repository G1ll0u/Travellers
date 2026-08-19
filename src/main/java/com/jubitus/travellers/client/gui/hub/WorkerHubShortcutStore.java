package com.jubitus.travellers.client.gui.hub;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists the player's hub shortcuts to {@code config/travellers/worker_hub_shortcuts.json}.
 *
 * <p>Deliberately a flat local file, not a {@code WorldSavedData}: a shortcut layout is a purely
 * client-side UI preference (like which branches were open, see {@link WorkerHubNavState}, except
 * this one is meant to survive a restart), it needs no server round-trip and no per-world scoping
 * — the same menu tree exists in every world.
 *
 * <p>Keyed by node id, one entry per node — this is what enforces "one shortcut per button" from
 * the data model itself rather than a separate check.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubShortcutStore {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/HubShortcuts");
    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Entry>>() {}.getType();

    /** Plain DTO mirroring {@link WorkerHubShortcut}'s fields, for a stable JSON shape. */
    private static final class Entry {
        String nodeId;
        float fracX;
        float fracY;
    }

    private static final Map<String, WorkerHubShortcut> SHORTCUTS = new LinkedHashMap<>();
    private static boolean loaded;

    private WorkerHubShortcutStore() {}

    public static List<WorkerHubShortcut> all() {
        ensureLoaded();
        return new ArrayList<>(SHORTCUTS.values());
    }

    @Nullable
    public static WorkerHubShortcut get(String nodeId) {
        ensureLoaded();
        return SHORTCUTS.get(nodeId);
    }

    public static boolean has(String nodeId) {
        return get(nodeId) != null;
    }

    /** Creates or relocates the shortcut for this node, then persists immediately. */
    public static void put(String nodeId, float fracX, float fracY) {
        ensureLoaded();
        WorkerHubShortcut existing = SHORTCUTS.get(nodeId);
        if (existing != null) {
            existing.fracX = fracX;
            existing.fracY = fracY;
        } else {
            SHORTCUTS.put(nodeId, new WorkerHubShortcut(nodeId, fracX, fracY));
        }
        save();
    }

    public static void remove(String nodeId) {
        ensureLoaded();
        if (SHORTCUTS.remove(nodeId) != null) save();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        load();
    }

    private static File file() {
        File configDir = Loader.instance().getConfigDir();
        return new File(configDir, "travellers/worker_hub_shortcuts.json");
    }

    private static void load() {
        File file = file();
        if (!file.exists()) return;
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            List<Entry> entries = GSON.fromJson(reader, LIST_TYPE);
            if (entries == null) return;
            for (Entry e : entries) {
                if (e.nodeId == null) continue;
                SHORTCUTS.put(e.nodeId, new WorkerHubShortcut(e.nodeId, e.fracX, e.fracY));
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("Could not load worker hub shortcuts from {}", file, ex);
        }
    }

    private static void save() {
        File file = file();
        File dir = file.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warn("Could not create {}", dir);
            return;
        }

        List<Entry> entries = new ArrayList<>(SHORTCUTS.size());
        for (WorkerHubShortcut s : SHORTCUTS.values()) {
            Entry e = new Entry();
            e.nodeId = s.nodeId;
            e.fracX = s.fracX;
            e.fracY = s.fracY;
            entries.add(e);
        }

        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            GSON.toJson(entries, LIST_TYPE, writer);
        } catch (IOException ex) {
            LOGGER.warn("Could not save worker hub shortcuts to {}", file, ex);
        }
    }
}
