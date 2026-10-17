package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate;
import net.minecraft.client.resources.I18n;

import java.util.ArrayList;
import java.util.List;

/** Tooltip of a battle marker, written on the client from the viewer-relative numbers. */
final class BattleMarkerLabel {

    // Share of the standing fighters above which a running battle reads as going one way.
    private static final float TREND_SHARE = 0.6f;

    private BattleMarkerLabel() {
    }

    static String describe(MsgMapBattleUpdate.Entry e) {
        List<String> lines = new ArrayList<>();
        lines.add(title(e));
        if (e.involved) {
            // Your henchmen never mixed with the others fighting on your side.
            MsgMapBattleUpdate.Side s = e.first;
            lines.add(I18n.format("travellers.map.battle.side_line", I18n.format("travellers.map.battle.your_side"),
                    s.yoursStanding, s.yoursFallen));
            int allyStanding = s.standing - s.yoursStanding;
            int allyFallen = s.fallen - s.yoursFallen;
            int allyKinds = s.kinds & ~MsgMapBattleUpdate.KIND_YOURS;
            if (allyStanding + allyFallen > 0) {
                lines.add(I18n.format("travellers.map.battle.side_line",
                        I18n.format("travellers.map.battle.allies", names(allyKinds)), allyStanding, allyFallen));
            }
        } else {
            lines.add(sideLine(names(e.first.kinds), e.first));
        }
        lines.add(sideLine(e.involved ? I18n.format("travellers.map.battle.enemies") : names(e.second.kinds), e.second));
        if (e.involved && e.result == MsgMapBattleUpdate.Result.RUNNING) {
            float share = firstShare(e);
            String trend = share >= TREND_SHARE ? "good" : share <= 1f - TREND_SHARE ? "bad" : "even";
            lines.add(I18n.format("travellers.map.battle.trend." + trend));
        }
        return String.join("\n", lines);
    }

    /** Share of the standing fighters on the first side, 0..1. */
    static float firstShare(MsgMapBattleUpdate.Entry e) {
        int total = e.first.standing + e.second.standing;
        return total <= 0 ? 0.5f : e.first.standing / (float) total;
    }

    private static String title(MsgMapBattleUpdate.Entry e) {
        String time = (e.seconds / 60) + ":" + String.format("%02d", e.seconds % 60);
        switch (e.result) {
            case RUNNING:
                return I18n.format(e.campAssault ? "travellers.map.battle.title_camp" : "travellers.map.battle.title", time);
            case FIRST_WON:
                return I18n.format(e.involved ? "travellers.map.battle.won" : "travellers.map.battle.over_winner",
                        names(e.first.kinds), time);
            case SECOND_WON:
                return I18n.format(e.involved ? "travellers.map.battle.lost" : "travellers.map.battle.over_winner",
                        names(e.second.kinds), time);
            default:
                return I18n.format("travellers.map.battle.broken_off", time);
        }
    }

    private static String sideLine(String name, MsgMapBattleUpdate.Side side) {
        return I18n.format("travellers.map.battle.side_line", name, side.standing, side.fallen);
    }

    private static String names(int kinds) {
        List<String> parts = new ArrayList<>();
        if ((kinds & MsgMapBattleUpdate.KIND_YOURS) != 0) parts.add(I18n.format("travellers.map.battle.kind.yours"));
        if ((kinds & MsgMapBattleUpdate.KIND_HIRED) != 0) parts.add(I18n.format("travellers.map.battle.kind.hired"));
        if ((kinds & MsgMapBattleUpdate.KIND_TRAVELLERS) != 0) parts.add(I18n.format("travellers.map.battle.kind.travellers"));
        if ((kinds & MsgMapBattleUpdate.KIND_RAIDERS) != 0) parts.add(I18n.format("travellers.map.battle.kind.raiders"));
        return parts.isEmpty() ? "?" : String.join(" & ", parts);
    }
}
