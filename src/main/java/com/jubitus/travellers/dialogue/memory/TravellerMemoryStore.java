package com.jubitus.travellers.dialogue.memory;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Global, per-traveller NPC memory (identity-memory plan, Part M - supersedes Phase 3's per-
 * {@code (traveller, player)} design, see CLAUDE.md's dialogue section and
 * {@code project_npc_dialogue_system.md} for the M-1 reversal rationale: a traveller is one person in
 * the game world, and two players talking to the same traveller should be talking to the same person,
 * not two mutually unaware versions of it). One instance lives on each {@code EntityTraveller}
 * ({@code dialogueMemory}), NBT round-tripped through {@code writeEntityToNBT}/{@code readEntityFromNBT}
 * exactly like every other piece of traveller state — memory is only ever read/written while the
 * traveller is loaded and a player is mid-dialogue with it, so this needs no separate
 * {@code VirtualTravellerRecord} plumbing.
 *
 * <p>Two-tier, capacity-driven ring buffer (M-2/M-4), now global rather than per-player: the small
 * "index" tier is always folded into {@code DialogueContext} (no tool call needed); the larger
 * "archive" tier is only reachable via the {@code recall_memory} tool call. Inserting into a full index
 * demotes its oldest entry to the archive; inserting into a full archive deletes its oldest entry
 * outright — real, permanent forgetting, no salience scoring, no time-based fading. A rolling narrative
 * {@link #lifeSummary} (N-4) is the second, independently-toggleable content shape, rewritten wholesale
 * at the end of every conversation, never appended.
 *
 * <p>The "have I met this player before" roster (N-5) is the one piece that structurally cannot be
 * global — you cannot merge "met player A" and "met player B" into a single flag — and stays keyed by
 * player UUID as a lightweight roster, not a memory store in its own right.
 */
public final class TravellerMemoryStore {

    /** Personality-driven forgetfulness (M-5): a small hardcoded exception list, not a personality file field. */
    private static final Map<String, Double> PERSONALITY_CAP_MULTIPLIER = new HashMap<>();

    static {
        PERSONALITY_CAP_MULTIPLIER.put("veteran_amnesique", 0.4);
        PERSONALITY_CAP_MULTIPLIER.put("paranoiaque", 2.0);
    }

    public static final class Entry {
        public final String text;
        public final long createdTick;

        Entry(String text, long createdTick) {
            this.text = text;
            this.createdTick = createdTick;
        }
    }

    private static final class Roster {
        boolean everMet = false;
        /** World tick of the previous conversation's start, or -1 if unknown (an old save from before this field existed). */
        long lastMetTick = -1;
    }

    private final Deque<Entry> index = new ArrayDeque<>();
    private final Deque<Entry> archive = new ArrayDeque<>();
    private String lifeSummary = "";
    private final Map<UUID, Roster> roster = new LinkedHashMap<>();

    /**
     * Marks this player as having talked to this traveller before - always-on, independent of
     * {@code DIALOGUE.memoryEnabled} (basic conversational realism - "have we met" - not the elaborate
     * opt-in fact-memory feature above). {@code nowTick} becomes the new {@link #getLastMetTick} - read
     * that first if the caller needs the *previous* value (same ordering {@code hasMetBefore}/{@code
     * markMet} already require: build this visit's context, then mark).
     */
    public void markMet(UUID playerId, long nowTick) {
        if (playerId == null) return;
        Roster r = roster.computeIfAbsent(playerId, k -> new Roster());
        r.everMet = true;
        r.lastMetTick = nowTick;
    }

    /** True once {@link #markMet} has ever been called for this player, false for a stranger. */
    public boolean hasMetBefore(UUID playerId) {
        Roster r = roster.get(playerId);
        return r != null && r.everMet;
    }

    /** World tick of this player's previous conversation with this traveller, or -1 if none/unknown. */
    public long getLastMetTick(UUID playerId) {
        Roster r = roster.get(playerId);
        return r == null ? -1 : r.lastMetTick;
    }

    /** Adds one fact to the global index tier, applying ring-buffer eviction. Blank facts are ignored. */
    public void remember(String personalityId, String fact, long now) {
        if (fact == null || fact.trim().isEmpty()) return;
        int indexCap = Math.max(1, capFor(personalityId, TravellersConfig.DIALOGUE.memoryIndexCap));
        int archiveCap = Math.max(1, capFor(personalityId, TravellersConfig.DIALOGUE.memoryArchiveCap));

        index.addLast(new Entry(fact.trim(), now));
        while (index.size() > indexCap) {
            archive.addLast(index.removeFirst());
            while (archive.size() > archiveCap) {
                archive.removeFirst();
            }
        }
    }

    /** Global index-tier phrases for {@code DialogueContext} - cheap, always injected, oldest first. Never null. */
    public List<String> indexPhrasesFor() {
        List<String> out = new ArrayList<>();
        for (Entry e : index) out.add(e.text);
        return out;
    }

    /** Best-effort substring match against the global archive tier only ({@code recall_memory} tool). Never null. */
    public List<String> recall(String topic) {
        List<String> matches = new ArrayList<>();
        if (topic == null) return matches;
        String needle = topic.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return matches;
        for (Entry e : archive) {
            if (e.text.toLowerCase(Locale.ROOT).contains(needle)) matches.add(e.text);
        }
        return matches;
    }

    /** The rolling narrative life summary (N-4), or empty if none has been generated yet. Never null. */
    public String getLifeSummary() {
        return lifeSummary;
    }

    /** Replaces the whole summary wholesale - never appends, matching the model being asked each time
     * to produce the full updated version, not a delta. */
    public void updateLifeSummary(String summary) {
        this.lifeSummary = summary == null ? "" : summary;
    }

    /** Debug/admin recovery: wipes the fact list and life summary, keeping the "have we met" roster
     * intact - e.g. to purge memories a bad extraction call got wrong ("/travellersdebug dialogue memory clear"). */
    public void clearMemories() {
        index.clear();
        archive.clear();
        lifeSummary = "";
    }

    /** Debug-only dump ("/travellersdebug dialogue memory dump") - never used by normal gameplay code. */
    public List<String> debugDumpLines() {
        List<String> lines = new ArrayList<>();
        lines.add("index=" + index.size() + " archive=" + archive.size() + " roster=" + roster.size());
        for (Entry entry : index) lines.add("  [I] " + entry.text);
        for (Entry entry : archive) lines.add("  [A] " + entry.text);
        if (!lifeSummary.isEmpty()) lines.add("  [SUMMARY] " + lifeSummary);
        for (Map.Entry<UUID, Roster> e : roster.entrySet()) {
            lines.add("  [MET] " + e.getKey().toString().substring(0, 8) + " last=" + e.getValue().lastMetTick);
        }
        return lines;
    }

    private static int capFor(String personalityId, int baseCap) {
        Double multiplier = personalityId == null ? null : PERSONALITY_CAP_MULTIPLIER.get(personalityId);
        return multiplier == null ? baseCap : (int) Math.round(baseCap * multiplier);
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Index", entriesToNbt(index));
        root.setTag("Archive", entriesToNbt(archive));
        root.setString("LifeSummary", lifeSummary);

        NBTTagList rosterList = new NBTTagList();
        for (Map.Entry<UUID, Roster> e : roster.entrySet()) {
            NBTTagCompound playerTag = new NBTTagCompound();
            playerTag.setUniqueId("Player", e.getKey());
            playerTag.setBoolean("EverMet", e.getValue().everMet);
            playerTag.setLong("LastMetTick", e.getValue().lastMetTick);
            rosterList.appendTag(playerTag);
        }
        root.setTag("Roster", rosterList);
        return root;
    }

    private static NBTTagList entriesToNbt(Deque<Entry> entries) {
        NBTTagList list = new NBTTagList();
        for (Entry e : entries) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("Text", e.text);
            tag.setLong("Tick", e.createdTick);
            list.appendTag(tag);
        }
        return list;
    }

    /**
     * Replaces all in-memory state from NBT — never merges. Called unconditionally from
     * {@code readEntityFromNBT} (frozen virtual NBT vs. a live entity's own state must never mix), per
     * CLAUDE.md's virtualization checklist trap on nullable/collection fields. No migration from the
     * old per-player format — this whole dialogue system is unreleased (CLAUDE.md precedent for the
     * mounts/stable system applies equally here); a missing/old-format tag simply reads as empty.
     */
    public void loadFrom(NBTTagCompound root) {
        index.clear();
        archive.clear();
        roster.clear();
        lifeSummary = root.getString("LifeSummary");
        readEntries(root.getTagList("Index", Constants.NBT.TAG_COMPOUND), index);
        readEntries(root.getTagList("Archive", Constants.NBT.TAG_COMPOUND), archive);

        NBTTagList rosterList = root.getTagList("Roster", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < rosterList.tagCount(); i++) {
            NBTTagCompound playerTag = rosterList.getCompoundTagAt(i);
            UUID playerId = playerTag.getUniqueId("Player");
            Roster r = new Roster();
            r.everMet = playerTag.getBoolean("EverMet");
            r.lastMetTick = playerTag.hasKey("LastMetTick") ? playerTag.getLong("LastMetTick") : -1;
            roster.put(playerId, r);
        }
    }

    private static void readEntries(NBTTagList list, Deque<Entry> out) {
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            out.addLast(new Entry(tag.getString("Text"), tag.getLong("Tick")));
        }
    }
}
