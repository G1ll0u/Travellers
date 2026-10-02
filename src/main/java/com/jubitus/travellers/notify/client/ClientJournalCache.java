package com.jubitus.travellers.notify.client;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.JournalEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side mirror of the player's notification journal, feeding the history panel in
 * GuiWorkerFormationHub. Refetched in full every time the hub opens (MsgRequestJournalHistory)
 * and kept current afterwards via live pushes (MsgJournalEntryAdded) for as long as the client
 * stays connected. Client thread only.
 */
public final class ClientJournalCache {

    // Oldest first, newest last (same ordering as NotificationJournalData.getFor).
    private static final List<JournalEntry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    private ClientJournalCache() {}

    public static void setAll(List<JournalEntry> entries) {
        ENTRIES.clear();
        ENTRIES.addAll(entries);
        trim();
        loaded = true;
    }

    public static void append(JournalEntry entry) {
        ENTRIES.add(entry);
        trim();
    }

    private static void trim() {
        int cap = TravellersConfig.NOTIFICATIONS.historyPanelMaxEntries;
        while (ENTRIES.size() > cap) {
            ENTRIES.remove(0);
        }
    }

    public static List<JournalEntry> entries() {
        return new ArrayList<>(ENTRIES);
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
