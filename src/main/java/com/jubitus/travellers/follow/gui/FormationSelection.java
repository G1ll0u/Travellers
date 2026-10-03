package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.FormationLeaderInfo;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What the Formation Hub has selected: a leader row (a group, or "You"), loose followers picked on the map, or nothing. */
final class FormationSelection {

    enum Mode { NONE, LEADER, FOLLOWERS }

    /** The leader rows, nearest first; refreshed in place by the hub. */
    final List<FormationLeaderInfo> leaders;

    Mode mode = Mode.NONE;
    /** Selected leader row, meaningful in LEADER mode. */
    int index = 0;
    List<UUID> followers = Collections.emptyList();
    /** After a group is formed from these followers, its new leader is selected when the refreshed list arrives. */
    Set<UUID> pendingFromFollowers = Collections.emptySet();

    FormationSelection(List<FormationLeaderInfo> leaders) {
        this.leaders = leaders;
    }

    boolean isFollowers() {
        return mode == Mode.FOLLOWERS;
    }

    @Nullable
    FormationLeaderInfo leader() {
        if (mode != Mode.LEADER || index < 0 || index >= leaders.size()) return null;
        return leaders.get(index);
    }

    /** The selected worker group, or null when "You", followers or nothing is selected. */
    @Nullable
    FormationLeaderInfo group() {
        FormationLeaderInfo sel = leader();
        return sel != null && !sel.isPlayer && sel.uuid != null ? sel : null;
    }

    /** The leader row the settings card shows (the selected one, clamped), or null when there are none. */
    @Nullable
    FormationLeaderInfo currentRow() {
        return leaders.isEmpty() ? null : leaders.get(Math.min(index, leaders.size() - 1));
    }

    @Nullable
    FormationLeaderInfo leaderByUuid(@Nullable UUID uuid) {
        int i = indexOf(uuid);
        return i >= 0 ? leaders.get(i) : null;
    }

    int indexOf(@Nullable UUID uuid) {
        if (uuid == null) return -1;
        for (int i = 0; i < leaders.size(); i++) {
            if (uuid.equals(leaders.get(i).uuid)) return i;
        }
        return -1;
    }

    int indexOfPlayer() {
        for (int i = 0; i < leaders.size(); i++) {
            if (leaders.get(i).isPlayer) return i;
        }
        return -1;
    }
}
