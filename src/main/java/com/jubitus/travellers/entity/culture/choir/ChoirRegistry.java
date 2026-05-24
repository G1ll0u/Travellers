package com.jubitus.travellers.entity.culture.choir;

import com.jubitus.travellers.Reference;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.*;

public final class ChoirRegistry {
    // key: "lang:personality" -> sorted list of songs (alphabetical by name)
    private static final Map<String, List<ChoirSongDef>> BY_KEY = new HashMap<>();

    private ChoirRegistry() {}

    public static void load(ChoirIndex idx) {
        BY_KEY.clear();
        if (idx == null || idx.songs == null) return;

        for (Map.Entry<String, Map<String, List<ChoirIndex.SongEntry>>> langEntry : idx.songs.entrySet()) {
            String lang = langEntry.getKey().toLowerCase(Locale.ROOT);
            if (langEntry.getValue() == null) continue;

            for (Map.Entry<String, List<ChoirIndex.SongEntry>> persEntry : langEntry.getValue().entrySet()) {
                String pers = persEntry.getKey().toLowerCase(Locale.ROOT);
                if (persEntry.getValue() == null) continue;

                List<ChoirIndex.SongEntry> entries = new ArrayList<>(persEntry.getValue());
                entries.sort(Comparator.comparing(e -> e.name != null ? e.name : ""));

                List<ChoirSongDef> defs = new ArrayList<>();
                for (ChoirIndex.SongEntry entry : entries) {
                    if (entry.name == null || entry.stems == null || entry.stems.isEmpty()) continue;
                    SoundEvent[] stemEvents = resolveStemEvents(entry.stems);
                    if (stemEvents != null) defs.add(new ChoirSongDef(entry.name, stemEvents, entry.durationTicks));
                }

                if (!defs.isEmpty()) BY_KEY.put(lang + ":" + pers, defs);
            }
        }

        if (idx.aliases != null) {
            for (Map.Entry<String, Map<String, String>> langEntry : idx.aliases.entrySet()) {
                String lang = langEntry.getKey().toLowerCase(Locale.ROOT);
                if (langEntry.getValue() == null) continue;
                for (Map.Entry<String, String> aliasEntry : langEntry.getValue().entrySet()) {
                    String alias = aliasEntry.getKey().toLowerCase(Locale.ROOT);
                    String target = aliasEntry.getValue() == null ? "" : aliasEntry.getValue().toLowerCase(Locale.ROOT);
                    List<ChoirSongDef> songs = BY_KEY.get(lang + ":" + target);
                    if (songs != null) BY_KEY.put(lang + ":" + alias, songs);
                }
            }
        }
    }

    private static SoundEvent[] resolveStemEvents(List<String> stemKeys) {
        SoundEvent[] events = new SoundEvent[stemKeys.size()];
        for (int i = 0; i < stemKeys.size(); i++) {
            String key = stemKeys.get(i);
            if (key == null || key.isEmpty()) return null;
            SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(Reference.MOD_ID, key));
            if (ev == null) return null;
            events[i] = ev;
        }
        return events;
    }

    public static List<ChoirSongDef> getSongs(String lang, String personality) {
        return BY_KEY.getOrDefault(
                lang.toLowerCase(Locale.ROOT) + ":" + personality.toLowerCase(Locale.ROOT),
                Collections.emptyList()
        );
    }

    public static boolean hasAnySongs() {
        return !BY_KEY.isEmpty();
    }
}
