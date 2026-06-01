package com.jubitus.travellers.config;

import java.util.*;

public final class TravellersConfigGuiState {

    public static String lastSearch = "";
    public static int lastScrollOffset = 0;
    public static String lastSelectedPath = null;
    public static boolean sortByModification = false;
    public static int historyMaxSize = 16;

    // Client-side snapshot used for GUI display
    private static final Map<String, ArrayDeque<String>> displayHistory = new LinkedHashMap<>();
    private static final List<String> displayModOrder = new ArrayList<>();

    private TravellersConfigGuiState() {}

    // Sync display state from local TravellersConfigHistory (singleplayer / Mod Options).
    public static void syncFromLocalHistory() {
        displayHistory.clear();
        for (Map.Entry<String, List<String>> e : TravellersConfigHistory.getAllHistory().entrySet()) {
            displayHistory.put(e.getKey(), new ArrayDeque<>(e.getValue()));
        }
        displayModOrder.clear();
        displayModOrder.addAll(TravellersConfigHistory.getModOrder());
        historyMaxSize = TravellersConfigHistory.maxSize;
    }

    // Populate display state from a network message (multiplayer).
    public static void applyNetworkHistory(List<String> order, Map<String, List<String>> hist, int max) {
        displayHistory.clear();
        for (Map.Entry<String, List<String>> e : hist.entrySet()) {
            displayHistory.put(e.getKey(), new ArrayDeque<>(e.getValue()));
        }
        displayModOrder.clear();
        displayModOrder.addAll(order);
        historyMaxSize = max;
    }

    // Recency in [0,1]: 1=most recently changed, 0=oldest. Returns -1 if not in history.
    public static float getRecency(String path) {
        int idx = displayModOrder.indexOf(path);
        if (idx < 0) return -1f;
        int n = displayModOrder.size();
        return n == 1 ? 1f : (float) idx / (n - 1);
    }

    public static List<String> getHistory(String path) {
        ArrayDeque<String> h = displayHistory.get(path);
        return h == null ? Collections.emptyList() : new ArrayList<>(h);
    }

    public static int getHistoryCount(String path) {
        ArrayDeque<String> h = displayHistory.get(path);
        return h == null ? 0 : h.size();
    }

    public static List<String> getModificationOrder() {
        return Collections.unmodifiableList(displayModOrder);
    }
}
