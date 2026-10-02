package com.jubitus.travellers.unit.culture;

import com.jubitus.travellers.config.TravellersConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared include/exclude villager filter engine, used identically for traveller/raider/worker
 * appearance pools. Semantics: an excluded file or tag always rejects, regardless of includes.
 * With no include rules configured, everything not excluded is allowed. With include rules
 * configured, a file or tag match is required (tag matching is ANY or ALL depending on
 * tagMatchRequiresAll).
 */
public final class VillagerFilterRules {

    public static VillagerFilterRules TRAVELLER = fromConfig(
            TravellersConfig.FILTERS.excludedTravellerVillagerTagsRaw,
            TravellersConfig.FILTERS.excludedTravellerVillagerFilesRaw,
            TravellersConfig.FILTERS.includedTravellerVillagerTagsRaw,
            TravellersConfig.FILTERS.includedTravellerVillagerFilesRaw,
            TravellersConfig.FILTERS.includeTravellerTagMatchRequiresAll);

    public static VillagerFilterRules RAIDER = fromConfig(
            TravellersConfig.FILTERS.excludedRaiderVillagerTagsRaw,
            TravellersConfig.FILTERS.excludedRaiderVillagerFilesRaw,
            TravellersConfig.FILTERS.includedRaiderVillagerTagsRaw,
            TravellersConfig.FILTERS.includedRaiderVillagerFilesRaw,
            TravellersConfig.FILTERS.includeRaiderTagMatchRequiresAll);

    public static VillagerFilterRules WORKER = fromConfig(
            TravellersConfig.FILTERS.excludedWorkerVillagerTagsRaw,
            TravellersConfig.FILTERS.excludedWorkerVillagerFilesRaw,
            TravellersConfig.FILTERS.includedWorkerVillagerTagsRaw,
            TravellersConfig.FILTERS.includedWorkerVillagerFilesRaw,
            TravellersConfig.FILTERS.includeWorkerTagMatchRequiresAll);

    /** Wildcard-excluded filenames shared by all three pools (e.g. "special*", "quest*"). */
    private static List<String> wildcardExcludePatterns = normalizedWildcardList(
            TravellersConfig.FILTERS.excludedVillagerFileWildcards);

    /** Re-reads current config values. Must be called before culture bootstrap parses villager txts. */
    public static void refresh() {
        wildcardExcludePatterns = normalizedWildcardList(TravellersConfig.FILTERS.excludedVillagerFileWildcards);
        TRAVELLER = fromConfig(
                TravellersConfig.FILTERS.excludedTravellerVillagerTagsRaw,
                TravellersConfig.FILTERS.excludedTravellerVillagerFilesRaw,
                TravellersConfig.FILTERS.includedTravellerVillagerTagsRaw,
                TravellersConfig.FILTERS.includedTravellerVillagerFilesRaw,
                TravellersConfig.FILTERS.includeTravellerTagMatchRequiresAll);
        RAIDER = fromConfig(
                TravellersConfig.FILTERS.excludedRaiderVillagerTagsRaw,
                TravellersConfig.FILTERS.excludedRaiderVillagerFilesRaw,
                TravellersConfig.FILTERS.includedRaiderVillagerTagsRaw,
                TravellersConfig.FILTERS.includedRaiderVillagerFilesRaw,
                TravellersConfig.FILTERS.includeRaiderTagMatchRequiresAll);
        WORKER = fromConfig(
                TravellersConfig.FILTERS.excludedWorkerVillagerTagsRaw,
                TravellersConfig.FILTERS.excludedWorkerVillagerFilesRaw,
                TravellersConfig.FILTERS.includedWorkerVillagerTagsRaw,
                TravellersConfig.FILTERS.includedWorkerVillagerFilesRaw,
                TravellersConfig.FILTERS.includeWorkerTagMatchRequiresAll);
    }

    private static VillagerFilterRules fromConfig(String[] excludedTagsRaw, String[] excludedFilesRaw,
                                                   String[] includedTagsRaw, String[] includedFilesRaw,
                                                   boolean tagMatchRequiresAll) {
        return new VillagerFilterRules(excludedTagsRaw, excludedFilesRaw, includedTagsRaw, includedFilesRaw, tagMatchRequiresAll);
    }

    private final Set<String> excludedTags;
    private final Set<String> excludedFiles;
    private final Set<String> includedTags;
    private final Set<String> includedFiles;
    private final boolean tagMatchRequiresAll;

    private VillagerFilterRules(String[] excludedTagsRaw, String[] excludedFilesRaw,
                                 String[] includedTagsRaw, String[] includedFilesRaw,
                                 boolean tagMatchRequiresAll) {
        this.excludedTags = normalizedTagSet(excludedTagsRaw);
        this.excludedFiles = normalizedFileSet(excludedFilesRaw);
        this.includedTags = normalizedTagSet(includedTagsRaw);
        this.includedFiles = normalizedFileSet(includedFilesRaw);
        this.tagMatchRequiresAll = tagMatchRequiresAll;
    }

    private static Set<String> normalizedTagSet(String[] raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) return set;
        for (String t : raw) {
            if (t == null) continue;
            String s = t.trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) set.add(s);
        }
        return set;
    }

    private static Set<String> normalizedFileSet(String[] raw) {
        Set<String> set = new HashSet<>();
        if (raw == null) return set;
        for (String f : raw) {
            if (f == null) continue;
            String s = f.trim().toLowerCase(Locale.ROOT);
            if (s.endsWith(".txt")) s = s.substring(0, s.length() - 4);
            if (!s.isEmpty()) set.add(s);
        }
        return set;
    }

    /**
     * @param tagsInFile     tags declared by the villager txt (already lowercased)
     * @param villagerBaseName filename without .txt, lowercased
     */
    public boolean isAllowed(Set<String> tagsInFile, String villagerBaseName) {
        if (excludedFiles.contains(villagerBaseName)) return false;
        if (matchesAnyWildcard(villagerBaseName)) return false;
        for (String t : tagsInFile) {
            if (excludedTags.contains(t)) return false;
        }

        boolean hasFileIncludes = !includedFiles.isEmpty();
        boolean hasTagIncludes = !includedTags.isEmpty();
        if (!hasFileIncludes && !hasTagIncludes) return true;

        boolean fileMatch = hasFileIncludes && includedFiles.contains(villagerBaseName);
        boolean tagMatch = hasTagIncludes && (tagMatchRequiresAll
                ? tagsInFile.containsAll(includedTags)
                : !java.util.Collections.disjoint(tagsInFile, includedTags));

        return fileMatch || tagMatch;
    }

    public boolean hasIncludeRules() {
        return !includedFiles.isEmpty() || !includedTags.isEmpty();
    }

    private static List<String> normalizedWildcardList(String[] raw) {
        List<String> list = new ArrayList<>();
        if (raw == null) return list;
        for (String p : raw) {
            if (p == null) continue;
            String s = p.trim().toLowerCase(Locale.ROOT);
            if (s.endsWith(".txt")) s = s.substring(0, s.length() - 4);
            if (!s.isEmpty()) list.add(s);
        }
        return list;
    }

    /** Only "*" is supported as a wildcard (matches any sequence, including empty). */
    private static boolean matchesAnyWildcard(String villagerBaseName) {
        for (String pattern : wildcardExcludePatterns) {
            if (!pattern.contains("*")) {
                if (pattern.equals(villagerBaseName)) return true;
                continue;
            }
            String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
            if (villagerBaseName.matches(regex)) return true;
        }
        return false;
    }
}
