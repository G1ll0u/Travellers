package com.jubitus.travellers.camp.shelter;

import net.minecraftforge.fml.common.Loader;

import javax.annotation.Nullable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Loads shelter templates from config/travellers/structures/shelters/&lt;culture&gt;/*.nbt, one
 * subfolder per culture (folder name is authoritative — the "Culture" field baked into an individual
 * .nbt is only used for one-time migration of pre-folder files, see {@link #migrateLegacyFlatFiles}).
 * Multiple templates per culture are picked at random. Culture "default" is the fallback when a
 * traveller's culture has no template of its own.
 *
 * A template's identity for persistence (see {@code CampOccupant.shelterTemplateCulture}/
 * {@code shelterTemplateName}) is the (culture folder, Name field) pair, never a bare name — two
 * different cultures may both contain a template named "default" without colliding. Within a single
 * culture folder, names must be unique: {@link #reload()} loads files in a fixed (alphabetical) order
 * and skips + loudly logs any file whose Name duplicates one already loaded in that folder, so which
 * one "wins" is deterministic across restarts instead of depending on filesystem listing order.
 */
public final class ShelterTemplateRegistry {

    public static final String DEFAULT_CULTURE = "default";

    private static final Map<String, List<ShelterTemplate>> BY_CULTURE = new HashMap<>();
    private static boolean loaded = false;

    private ShelterTemplateRegistry() {
    }

    public static File sheltersDir() {
        return new File(Loader.instance().getConfigDir(), "travellers/structures/shelters");
    }

    public static synchronized void reload() {
        BY_CULTURE.clear();
        File root = sheltersDir();
        migrateLegacyFlatFiles(root);

        File[] cultureDirs = root.listFiles(File::isDirectory);
        if (cultureDirs == null) {
            loaded = true;
            return;
        }
        Arrays.sort(cultureDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File cultureDir : cultureDirs) {
            loadCultureFolder(cultureDir, cultureDir.getName().toLowerCase(Locale.ROOT));
        }
        loaded = true;
    }

    private static void loadCultureFolder(File cultureDir, String culture) {
        File[] files = cultureDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".nbt"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        List<ShelterTemplate> list = new ArrayList<>();
        Map<String, File> seenNames = new HashMap<>();
        for (File f : files) {
            ShelterTemplate t;
            try {
                t = ShelterTemplate.load(f);
            } catch (Exception e) {
                System.err.println("[Travellers] Failed to load shelter template " + f.getName() + ": " + e.getMessage());
                continue;
            }
            String key = t.name.toLowerCase(Locale.ROOT);
            File dupe = seenNames.get(key);
            if (dupe != null) {
                System.err.println("[Travellers] Shelter template name \"" + t.name + "\" in culture \"" + culture
                        + "\" is used by both " + dupe.getName() + " and " + f.getName()
                        + " - keeping " + dupe.getName() + ", skipping " + f.getName()
                        + ". Rename one of them so each template has a unique name within its culture folder.");
                continue;
            }
            seenNames.put(key, f);
            // Folder is authoritative: force the in-memory culture to match where the file actually
            // lives, regardless of what the file's own "Culture" metadata says (stale if hand-copied).
            list.add(new ShelterTemplate(t.name, culture, t.entranceFacing, t.template));
        }
        if (!list.isEmpty()) BY_CULTURE.put(culture, list);
    }

    /**
     * One-time upgrade path: templates saved before culture subfolders existed sit directly in
     * {@code sheltersDir()}. Move each into {@code <cultureFromItsOwnMetadata>/<name>.nbt} so existing
     * worlds don't lose their templates just from updating the mod. Safe to call every {@link #reload()}
     * - a no-op once the root has no more loose .nbt files.
     */
    private static void migrateLegacyFlatFiles(File root) {
        File[] loose = root.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".nbt"));
        if (loose == null || loose.length == 0) return;
        for (File f : loose) {
            ShelterTemplate t;
            try {
                t = ShelterTemplate.load(f);
            } catch (Exception e) {
                System.err.println("[Travellers] Failed to migrate legacy shelter template " + f.getName() + ": " + e.getMessage());
                continue;
            }
            String culture = (t.culture == null || t.culture.trim().isEmpty()) ? DEFAULT_CULTURE : t.culture;
            File destDir = new File(root, culture.toLowerCase(Locale.ROOT));
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
            File dest = new File(destDir, f.getName());
            if (dest.exists()) {
                dest = new File(destDir, t.name + "-migrated-" + System.currentTimeMillis() + ".nbt");
            }
            if (f.renameTo(dest)) {
                System.out.println("[Travellers] Migrated legacy shelter template " + f.getName()
                        + " into " + culture + "/" + dest.getName());
            } else {
                System.err.println("[Travellers] Could not migrate legacy shelter template " + f.getName()
                        + " - move it manually into " + culture + "/");
            }
        }
    }

    private static void ensureLoaded() {
        if (!loaded) reload();
    }

    /** Random template for a culture, falling back to "default". Null only if nothing is installed. */
    @Nullable
    public static synchronized ShelterTemplate pick(String culture, Random rand) {
        ensureLoaded();
        List<ShelterTemplate> list = BY_CULTURE.get(culture.toLowerCase(Locale.ROOT));
        if (list == null || list.isEmpty()) list = BY_CULTURE.get(DEFAULT_CULTURE);
        if (list == null || list.isEmpty()) return null;
        return list.get(rand.nextInt(list.size()));
    }

    /** Exact (culture, name) lookup — the only safe way to resolve a template an occupant already locked in. */
    @Nullable
    public static synchronized ShelterTemplate find(@Nullable String culture, String name) {
        ensureLoaded();
        if (culture != null) {
            List<ShelterTemplate> list = BY_CULTURE.get(culture.toLowerCase(Locale.ROOT));
            if (list != null) {
                for (ShelterTemplate t : list) {
                    if (t.name.equalsIgnoreCase(name)) return t;
                }
            }
            return null;
        }
        // No culture recorded (pre-migration save): best-effort scan, first match wins. Ambiguous if
        // the same name exists in more than one culture folder - callers should avoid relying on this.
        for (List<ShelterTemplate> list : BY_CULTURE.values()) {
            for (ShelterTemplate t : list) {
                if (t.name.equalsIgnoreCase(name)) return t;
            }
        }
        return null;
    }

    public static synchronized Map<String, List<ShelterTemplate>> view() {
        ensureLoaded();
        return BY_CULTURE;
    }
}
