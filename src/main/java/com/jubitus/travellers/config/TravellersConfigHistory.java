package com.jubitus.travellers.config;

import net.minecraftforge.fml.common.Loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TravellersConfigHistory {

    private static final Map<String, ArrayDeque<String>> valueHistory = new LinkedHashMap<>();
    private static final List<String> modOrder = new ArrayList<>();
    public static int maxSize = 16;

    private TravellersConfigHistory() {}

    private static File getHistoryFile() {
        return new File(Loader.instance().getConfigDir(), "travellers/travellers_config_history.properties");
    }

    // Called server-side when config is saved. Compares baseline (values before save) to new values.
    public static void onSave(Map<String, String> baseline, Map<String, String> newValues) {
        for (Map.Entry<String, String> e : newValues.entrySet()) {
            String path = e.getKey();
            String newVal = e.getValue();
            String oldVal = baseline.get(path);
            if (oldVal != null && !oldVal.equals(newVal)) {
                ArrayDeque<String> h = valueHistory.computeIfAbsent(path, k -> new ArrayDeque<>());
                h.addLast(newVal); // record the saved-to value, not the old value
                while (h.size() > maxSize) h.removeFirst();
                modOrder.remove(path);
                modOrder.add(path);
            }
        }
        while (modOrder.size() > maxSize) {
            String removed = modOrder.remove(0);
            valueHistory.remove(removed);
        }
        saveToFile();
    }

    // Recency in [0,1]: 1=most recently changed, 0=oldest changed. Returns -1 if not tracked.
    public static float getRecency(String path) {
        int idx = modOrder.indexOf(path);
        if (idx < 0) return -1f;
        int n = modOrder.size();
        return n == 1 ? 1f : (float) idx / (n - 1);
    }

    public static List<String> getHistory(String path) {
        ArrayDeque<String> h = valueHistory.get(path);
        return h == null ? Collections.emptyList() : new ArrayList<>(h);
    }

    public static List<String> getModOrder() {
        return Collections.unmodifiableList(modOrder);
    }

    public static Map<String, List<String>> getAllHistory() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ArrayDeque<String>> e : valueHistory.entrySet()) {
            result.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return result;
    }

    public static void setMaxSize(int size) {
        maxSize = Math.max(1, size);
        for (ArrayDeque<String> h : valueHistory.values()) {
            while (h.size() > maxSize) h.removeFirst();
        }
        while (modOrder.size() > maxSize) {
            String removed = modOrder.remove(0);
            valueHistory.remove(removed);
        }
    }

    public static void saveToFile() {
        File f = getHistoryFile();
        f.getParentFile().mkdirs();
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.write("# Travellers config history — do not edit manually");
            w.newLine();
            w.write("maxSize=" + maxSize);
            w.newLine();
            w.write("order=" + joinPipe(modOrder));
            w.newLine();
            for (Map.Entry<String, ArrayDeque<String>> e : valueHistory.entrySet()) {
                w.write("hist." + e.getKey() + "=" + joinPipe(new ArrayList<>(e.getValue())));
                w.newLine();
            }
        } catch (IOException ex) {
            System.err.println("[TravellersConfig] Failed to save history: " + ex.getMessage());
        }
    }

    public static void loadFromFile() {
        File f = getHistoryFile();
        if (!f.exists()) return;
        valueHistory.clear();
        modOrder.clear();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1);
                if (key.equals("maxSize")) {
                    try { maxSize = Integer.parseInt(val.trim()); } catch (NumberFormatException ignored) {}
                } else if (key.equals("order")) {
                    for (String p : splitPipe(val)) modOrder.add(p);
                } else if (key.startsWith("hist.")) {
                    String path = key.substring(5);
                    List<String> vals = splitPipe(val);
                    if (!vals.isEmpty()) valueHistory.put(path, new ArrayDeque<>(vals));
                }
            }
        } catch (IOException ex) {
            System.err.println("[TravellersConfig] Failed to load history: " + ex.getMessage());
        }
    }

    private static String joinPipe(List<String> vals) {
        if (vals.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append(vals.get(i));
        }
        return sb.toString();
    }

    private static List<String> splitPipe(String s) {
        List<String> result = new ArrayList<>();
        if (s == null || s.isEmpty()) return result;
        for (String part : s.split("\\|", -1)) {
            if (!part.isEmpty()) result.add(part);
        }
        return result;
    }
}
