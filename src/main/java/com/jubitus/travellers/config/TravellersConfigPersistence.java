package com.jubitus.travellers.config;

import net.minecraftforge.fml.common.Loader;

import java.io.*;
import java.util.*;

public final class TravellersConfigPersistence {

    // Increment this when the config structure changes in a breaking way.
    // On next startup the old overrides file is backed up and ignored so
    // users start fresh. They can copy values manually from the backup.
    public static final int CONFIG_VERSION = 1;

    private static final String VERSION_KEY = "__configVersion__";

    private static File getOverrideFile() {
        return new File(Loader.instance().getConfigDir(), "travellers/travellers_overrides.properties");
    }

    private static File getBackupFile(int oldVersion) {
        return new File(Loader.instance().getConfigDir(),
                "travellers/travellers_overrides_backup_v" + oldVersion + ".properties");
    }

    public static void save(Map<String, String> values) {
        File f = getOverrideFile();
        f.getParentFile().mkdirs();
        // Write version + user values, skipping any internal keys that slipped in
        Properties props = new Properties();
        props.setProperty(VERSION_KEY, String.valueOf(CONFIG_VERSION));
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!e.getKey().startsWith("__")) props.setProperty(e.getKey(), e.getValue());
        }
        try (Writer w = new FileWriter(f)) {
            props.store(w, "Travellers live config overrides — edited via GUI");
        } catch (IOException e) {
            System.err.println("[TravellersConfig] Failed to save overrides: " + e.getMessage());
        }
    }

    public static Map<String, String> load() {
        File f = getOverrideFile();
        if (!f.exists()) return Collections.emptyMap();
        Properties props = new Properties();
        try (Reader r = new FileReader(f)) {
            props.load(r);
        } catch (IOException e) {
            System.err.println("[TravellersConfig] Failed to load overrides: " + e.getMessage());
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("__")) map.put(key, props.getProperty(key));
        }
        return map;
    }

    public static void applyOnStartup() {
        File f = getOverrideFile();
        if (!f.exists()) return;

        // Read raw file to check stored version before deciding to apply
        Properties props = new Properties();
        try (Reader r = new FileReader(f)) {
            props.load(r);
        } catch (IOException e) {
            System.err.println("[TravellersConfig] Failed to read overrides on startup: " + e.getMessage());
            return;
        }

        int savedVersion = 0;
        String vStr = props.getProperty(VERSION_KEY);
        if (vStr != null) {
            try { savedVersion = Integer.parseInt(vStr.trim()); } catch (NumberFormatException ignored) {}
        }

        if (savedVersion != CONFIG_VERSION) {
            // Version mismatch: back up and skip — user starts fresh with defaults
            File backup = getBackupFile(savedVersion);
            if (f.renameTo(backup)) {
                System.out.println("[TravellersConfig] Config version changed (" + savedVersion
                        + " → " + CONFIG_VERSION + "). Old overrides backed up to: " + backup.getName()
                        + " — starting with defaults.");
            } else {
                System.err.println("[TravellersConfig] Config version changed but backup rename failed. Overrides NOT applied.");
            }
            return;
        }

        // Version matches: apply overrides normally
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("__")) saved.put(key, props.getProperty(key));
        }
        if (saved.isEmpty()) return;
        List<TravellersConfigEntry> entries = TravellersConfigReflector.buildEntries();
        TravellersConfigReflector.applyAll(entries, saved);
        System.out.println("[TravellersConfig] Applied " + saved.size() + " GUI overrides from properties file.");
    }
}
