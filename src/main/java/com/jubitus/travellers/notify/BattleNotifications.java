package com.jubitus.travellers.notify;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.notify.net.MsgJournalHistoryData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A battle told to an owner as one story: a start, one updated line per fallen henchman, a result.
 * Every beat shares the battle's story key, so it is one toast updated in place and one journal row;
 * the casualty list is carried from beat to beat by that journal row.
 */
public final class BattleNotifications {

    public enum Outcome { WON, LOST, BROKEN_OFF }

    private static final int NAMES_SHOWN = 3;

    private BattleNotifications() {
    }

    private static final String STORY_PREFIX = "battle:";

    public static String storyKey(UUID battleId) {
        return STORY_PREFIX + battleId;
    }

    public static boolean isStory(@Nullable String storyKey) {
        return storyKey != null && storyKey.startsWith(STORY_PREFIX);
    }

    public static void started(@Nullable MinecraftServer server, UUID ownerId, UUID battleId,
                               NoticePlace battlePlace, BattleSummary.Counts counts) {
        if (server == null) return;
        BattleSummary previous = previousSummary(server, ownerId, storyKey(battleId));
        List<BattleSummary.CasualtyEntry> casualties = previous != null
                ? previous.ownWorkerCasualties : Collections.emptyList();
        BattleSummary summary = new BattleSummary(battlePlace.pos, battlePlace.dimension, casualties, counts,
                BattleSummary.State.RUNNING);
        NotificationService.notifyStory(server, ownerId, storyKey(battleId), NotificationDefs.BATTLE_UNDER_ATTACK,
                summary, battlePlace, null);
    }

    /**
     * Henchmen fell since the last beat (gathered so a big battle updates its toast only now and then).
     * Call after {@code ZoneAssignments.onWorkerDied}, which decides whether each henchman comes back.
     */
    public static void workersFell(@Nullable MinecraftServer server, UUID ownerId, UUID battleId, NoticePlace battlePlace,
                                   List<BattleSummary.CasualtyEntry> fallenSinceLastBeat, BattleSummary.Counts counts) {
        if (server == null || fallenSinceLastBeat.isEmpty()) return;
        String key = storyKey(battleId);
        BattleSummary previous = previousSummary(server, ownerId, key);
        List<BattleSummary.CasualtyEntry> casualties = new ArrayList<>();
        if (previous != null) casualties.addAll(previous.ownWorkerCasualties);
        casualties.addAll(fallenSinceLastBeat);
        BattleSummary summary = new BattleSummary(battlePlace.pos, battlePlace.dimension, casualties, counts,
                BattleSummary.State.RUNNING);

        List<String> fallen = new ArrayList<>();
        for (BattleSummary.CasualtyEntry c : casualties) {
            if (c.died) fallen.add(c.name);
        }
        String shown = String.join(", ", fallen.subList(Math.max(0, fallen.size() - NAMES_SHOWN), fallen.size()));
        int hidden = Math.max(0, fallen.size() - NAMES_SHOWN);
        if (hidden > 0) {
            NotificationService.notifyStory(server, ownerId, key, NotificationDefs.BATTLE_FALLEN_MORE, summary,
                    battlePlace, null, String.valueOf(fallen.size()), shown, String.valueOf(hidden));
        } else {
            NotificationService.notifyStory(server, ownerId, key, NotificationDefs.BATTLE_FALLEN, summary,
                    battlePlace, null, String.valueOf(fallen.size()), shown);
        }
    }

    public static void ended(@Nullable MinecraftServer server, UUID ownerId, UUID battleId, NoticePlace battlePlace,
                             Outcome outcome, int fallen, BattleSummary.Counts counts) {
        if (server == null) return;
        String key = storyKey(battleId);
        BattleSummary previous = previousSummary(server, ownerId, key);
        BattleSummary.State state = outcome == Outcome.WON ? BattleSummary.State.WON
                : outcome == Outcome.LOST ? BattleSummary.State.LOST : BattleSummary.State.BROKEN_OFF;
        BattleSummary summary = new BattleSummary(battlePlace.pos, battlePlace.dimension,
                previous != null ? previous.ownWorkerCasualties : Collections.emptyList(), counts, state);
        NotificationDef def = outcome == Outcome.WON ? NotificationDefs.BATTLE_WON
                : outcome == Outcome.LOST ? NotificationDefs.BATTLE_LOST : NotificationDefs.BATTLE_BROKEN_OFF;
        NotificationService.notifyStory(server, ownerId, key, def, summary, battlePlace, null, String.valueOf(fallen));
    }

    /** Two battles merged: the absorbed battle's row and casualties continue under the surviving battle's story. */
    public static void merged(@Nullable MinecraftServer server, UUID ownerId, UUID absorbedId, UUID survivorId) {
        if (server == null) return;
        NotificationJournalData journal = NotificationJournalData.get(server.getWorld(0));
        JournalEntry absorbed = journal.removeStory(ownerId, storyKey(absorbedId));
        if (absorbed == null) return;
        String key = storyKey(survivorId);
        JournalEntry survivor = journal.findStory(ownerId, key);
        JournalEntry base = survivor != null ? survivor : absorbed;
        BattleSummary summary = base.battleSummary;
        if (survivor != null && survivor.battleSummary != null && absorbed.battleSummary != null) {
            List<BattleSummary.CasualtyEntry> casualties = new ArrayList<>(absorbed.battleSummary.ownWorkerCasualties);
            casualties.addAll(survivor.battleSummary.ownWorkerCasualties);
            summary = new BattleSummary(survivor.battleSummary.location, survivor.battleSummary.dimension,
                    casualties, survivor.battleSummary.counts, survivor.battleSummary.state);
        }
        journal.append(ownerId, new JournalEntry(base.type, base.i18nKey, base.args, base.worldTime, summary,
                base.place, base.detail, key));
        EntityPlayerMP online = server.getPlayerList().getPlayerByUUID(ownerId);
        if (online != null) TravellersNetwork.sendToPlayer(online, new MsgJournalHistoryData(journal.getFor(ownerId)));
    }

    @Nullable
    private static BattleSummary previousSummary(MinecraftServer server, UUID ownerId, String key) {
        JournalEntry previous = NotificationService.storyEntry(server, ownerId, key);
        return previous != null ? previous.battleSummary : null;
    }
}
