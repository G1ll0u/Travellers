package com.jubitus.travellers.worldmap.gui;

import com.jubitus.travellers.village.MillCultures;
import com.jubitus.travellers.village.net.SettlementMapInfo;
import com.jubitus.travellers.worker.net.MsgWorkerGoList;
import net.minecraft.util.math.BlockPos;

public final class WorldMapMarkerFactory {

    public static final int COLOR_ENEMY_VILLAGE = 0xFFCC3333;
    public static final int COLOR_BATTLE_MINE = 0xFFFF3030;
    public static final int COLOR_BATTLE_OTHERS = 0xFFFF9A30;
    public static final int COLOR_BATTLE_ENDED = 0xFFA0A0A0;
    public static final int COLOR_HOME    = 0xFFE8D5A3;

    private WorldMapMarkerFactory() {}

    public static GuiWorldMapPanel.Marker player(BlockPos pos, String name, int ownerRgb, float yawDeg) {
        return new GuiWorldMapPanel.Marker(pos, argb(ownerRgb),
                GuiWorldMapPanel.Marker.MarkerType.PLAYER, name, yawDeg);
    }

    public static GuiWorldMapPanel.Marker leader(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, argb(ownerRgb),
                GuiWorldMapPanel.Marker.MarkerType.LEADER, name);
    }

    public static GuiWorldMapPanel.Marker follower(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, argb(ownerRgb),
                GuiWorldMapPanel.Marker.MarkerType.FOLLOWER, name);
    }

    /**
     * A village or lone building: its Millénaire icon (or its culture's usual one), with a dot of your colour under it
     * when you control it, a red one when your henchmen count it an enemy.
     */
    public static GuiWorldMapPanel.Marker settlement(MsgWorkerGoList.Entry e, int ownerRgb) {
        SettlementMapInfo info = e.settlement;
        GuiWorldMapPanel.Marker.MarkerType type;
        int argb;
        if (e.kind == MsgWorkerGoList.Kind.LONE_BUILDING) {
            type = GuiWorldMapPanel.Marker.MarkerType.LONE_BUILDING;
            argb = 0xFFFFFFFF;
        } else if (e.kind == MsgWorkerGoList.Kind.CONTROLLED_VILLAGE) {
            type = GuiWorldMapPanel.Marker.MarkerType.CONTROLLED_VILLAGE;
            argb = argb(ownerRgb);
        } else if (info != null && info.enemy) {
            type = GuiWorldMapPanel.Marker.MarkerType.ENEMY_VILLAGE;
            argb = COLOR_ENEMY_VILLAGE;
        } else {
            type = GuiWorldMapPanel.Marker.MarkerType.VILLAGE;
            argb = 0xFFFFFFFF;
        }
        GuiWorldMapPanel.Marker m = new GuiWorldMapPanel.Marker(e.pos, argb, type, e.label);
        return info == null ? m : m.withIcon(MillCultures.settlementIcon(info.cultureKey, info.typeKey, info.lone));
    }

    /** A coloured dot under a settlement's icon, with its own tooltip (a recruiting trip's stop). */
    public static GuiWorldMapPanel.Marker settlementDot(BlockPos pos, int argb, String label) {
        return new GuiWorldMapPanel.Marker(pos, argb, GuiWorldMapPanel.Marker.MarkerType.SETTLEMENT_DOT, label);
    }

    public static GuiWorldMapPanel.Marker home(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, COLOR_HOME,
                GuiWorldMapPanel.Marker.MarkerType.HOME, name);
    }

    public static GuiWorldMapPanel.Marker home(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, argb(ownerRgb),
                GuiWorldMapPanel.Marker.MarkerType.HOME, name);
    }

    public static GuiWorldMapPanel.Marker stable(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, argb(ownerRgb),
                GuiWorldMapPanel.Marker.MarkerType.STABLE, name);
    }

    public static GuiWorldMapPanel.Marker destinationCross(BlockPos pos) {
        return new GuiWorldMapPanel.Marker(pos, 0xFFFF4444,
                GuiWorldMapPanel.Marker.MarkerType.DESTINATION_CROSS, null);
    }

    /**
     * Selected follower: drawn larger (workerMarkerSize + followerHighlightedSizeBonus) with
     * boosted saturation and lightness. Tweak vivify() constants to adjust the highlight strength.
     */
    public static GuiWorldMapPanel.Marker followerHighlighted(BlockPos pos, String name, int ownerRgb) {
        return new GuiWorldMapPanel.Marker(pos, 0xFF000000 | vivify(ownerRgb & 0xFFFFFF),
                GuiWorldMapPanel.Marker.MarkerType.FOLLOWER_HIGHLIGHTED, name);
    }

    /**
     * Boosts saturation and pushes lightness toward a bright range, keeping the hue.
     * Saturation: multiplied by 1.5 (clamped to 1).
     * Lightness:  pushed toward 0.70 (darker colors climb up, already-bright ones stay).
     */
    private static int vivify(int rgb) {
        float[] hsl = rgbToHsl(rgb);
        hsl[1] = Math.min(1f, hsl[1] * 1.5f);
        hsl[2] = Math.min(1f, hsl[2] + (0.70f - hsl[2]) * 0.6f);
        return hslToRgb(hsl[0], hsl[1], hsl[2]);
    }

    private static float[] rgbToHsl(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >>  8) & 0xFF) / 255f;
        float b = ( rgb        & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;
        float s = 0f, h = 0f;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if      (max == r) h = (g - b) / d + (g < b ? 6f : 0f);
            else if (max == g) h = (b - r) / d + 2f;
            else               h = (r - g) / d + 4f;
            h /= 6f;
        }
        return new float[]{ h, s, l };
    }

    private static int hslToRgb(float h, float s, float l) {
        if (s == 0f) {
            int v = clamp((int)(l * 255f));
            return (v << 16) | (v << 8) | v;
        }
        float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
        float p = 2f * l - q;
        return (hue(p, q, h + 1f/3f) << 16) | (hue(p, q, h) << 8) | hue(p, q, h - 1f/3f);
    }

    private static int hue(float p, float q, float t) {
        if (t < 0f) t += 1f;
        if (t > 1f) t -= 1f;
        float v;
        if      (t < 1f/6f) v = p + (q - p) * 6f * t;
        else if (t < 1f/2f) v = q;
        else if (t < 2f/3f) v = p + (q - p) * (2f/3f - t) * 6f;
        else                 v = p;
        return clamp((int)(v * 255f));
    }

    public static GuiWorldMapPanel.Marker leaderHighlighted(BlockPos pos, String name) {
        return new GuiWorldMapPanel.Marker(pos, 0xFFFFEE44,
                GuiWorldMapPanel.Marker.MarkerType.LEADER, name);
    }

    /** Blends an RGB value toward white by `factor` (0=unchanged, 1=white). */
    public static int lighten(int rgb, float factor) {
        int r = clamp((int)(((rgb >> 16) & 0xFF) + (255 - ((rgb >> 16) & 0xFF)) * factor));
        int g = clamp((int)(((rgb >>  8) & 0xFF) + (255 - ((rgb >>  8) & 0xFF)) * factor));
        int b = clamp((int)(( rgb        & 0xFF) + (255 - ( rgb        & 0xFF)) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /** Darkens an RGB value (no alpha) by factor (0=black, 1=unchanged). */
    public static int darken(int rgb, float factor) {
        int r = clamp((int)(((rgb >> 16) & 0xFF) * factor));
        int g = clamp((int)(((rgb >>  8) & 0xFF) * factor));
        int b = clamp((int)(( rgb        & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /** Loaded = fully opaque, virtual = translucent so the two are visually distinguishable. */
    public static final int COLOR_POP_TRAVELLER = 0x55CC66;
    public static final int COLOR_POP_RAIDER    = 0xCC4444;
    public static final int COLOR_POP_WORKER    = 0x4488CC;
    private static final int VIRTUAL_ALPHA = 0xAA000000;
    private static final int LOADED_ALPHA  = 0xFF000000;

    public static GuiWorldMapPanel.Marker populationTraveller(BlockPos pos, boolean virtual, String label) {
        return new GuiWorldMapPanel.Marker(pos, (virtual ? VIRTUAL_ALPHA : LOADED_ALPHA) | COLOR_POP_TRAVELLER,
                GuiWorldMapPanel.Marker.MarkerType.POP_TRAVELLER, label);
    }

    public static GuiWorldMapPanel.Marker populationRaider(BlockPos pos, boolean virtual, String label) {
        return new GuiWorldMapPanel.Marker(pos, (virtual ? VIRTUAL_ALPHA : LOADED_ALPHA) | COLOR_POP_RAIDER,
                GuiWorldMapPanel.Marker.MarkerType.POP_RAIDER, label);
    }

    /** A raider of a running raid: the raider icon in a colour no other marker uses. */
    public static GuiWorldMapPanel.Marker populationRaidRaider(BlockPos pos, boolean virtual, String label) {
        return new GuiWorldMapPanel.Marker(pos, (virtual ? VIRTUAL_ALPHA : LOADED_ALPHA) | 0xFF00FF,
                GuiWorldMapPanel.Marker.MarkerType.POP_RAIDER, label);
    }

    public static GuiWorldMapPanel.Marker populationWorker(BlockPos pos, boolean virtual, String label) {
        return new GuiWorldMapPanel.Marker(pos, (virtual ? VIRTUAL_ALPHA : LOADED_ALPHA) | COLOR_POP_WORKER,
                GuiWorldMapPanel.Marker.MarkerType.POP_WORKER, label);
    }

    /** Stable zone on the population map: its own icon, translucent while the pen is unloaded
     * (its pool is being run by the offscreen timer). The label carries the full pool breakdown. */
    public static GuiWorldMapPanel.Marker populationStable(BlockPos pos, boolean virtual, String label) {
        return new GuiWorldMapPanel.Marker(pos, (virtual ? VIRTUAL_ALPHA : LOADED_ALPHA) | 0xCCAA66,
                GuiWorldMapPanel.Marker.MarkerType.STABLE, label);
    }

    /** Foreign hired worker discovered via vision: owner-tinted instead of the generic worker blue. */
    public static GuiWorldMapPanel.Marker foreignHiredWorker(BlockPos pos, boolean virtual, int ownerRgb, String label) {
        return new GuiWorldMapPanel.Marker(pos, (virtual ? VIRTUAL_ALPHA : LOADED_ALPHA) | (ownerRgb & 0xFFFFFF),
                GuiWorldMapPanel.Marker.MarkerType.POP_WORKER, label);
    }

    // --- Camp system debug markers ---
    public static final int COLOR_CAMP_FIREPIT_LIT   = 0xFFCC6600; // orange embers
    public static final int COLOR_CAMP_FIREPIT_UNLIT = 0xFF555555; // cold ash
    public static final int COLOR_CAMP_SLOT_EMPTY        = 0xFF888888; // gray
    public static final int COLOR_CAMP_SLOT_DISABLED     = 0xFFCC2222; // red
    public static final int COLOR_CAMP_SLOT_BUILDING     = 0xFFDDDD44; // yellow
    public static final int COLOR_CAMP_SLOT_BUILT        = 0xFF55CC66; // green
    public static final int COLOR_CAMP_SLOT_TEARING_DOWN = 0xFFDD8844; // amber

    /** Firepit anchor: opaque once physically built, translucent while only planned logically. */
    public static GuiWorldMapPanel.Marker campFirepit(BlockPos pos, boolean built, boolean lit, String label) {
        int rgb = lit ? COLOR_CAMP_FIREPIT_LIT : COLOR_CAMP_FIREPIT_UNLIT;
        int alpha = built ? LOADED_ALPHA : VIRTUAL_ALPHA;
        return new GuiWorldMapPanel.Marker(pos, alpha | rgb,
                GuiWorldMapPanel.Marker.MarkerType.CAMP_FIREPIT, label);
    }

    /**
     * Shelter slot: color follows build state, alpha follows whether the occupant is currently a
     * loaded entity (opaque) or virtualized (translucent) — same loaded/virtual convention as the
     * population markers, letting virtual/physical mismatches jump out visually.
     */
    public static GuiWorldMapPanel.Marker campSlot(BlockPos pos, com.jubitus.travellers.worldmap.net.MsgMapCampUpdate.SlotState state,
                                                     boolean occupantVirtual, String label) {
        int rgb;
        switch (state) {
            case DISABLED:      rgb = COLOR_CAMP_SLOT_DISABLED; break;
            case BUILDING:      rgb = COLOR_CAMP_SLOT_BUILDING; break;
            case BUILT:         rgb = COLOR_CAMP_SLOT_BUILT; break;
            case TEARING_DOWN:  rgb = COLOR_CAMP_SLOT_TEARING_DOWN; break;
            default:            rgb = COLOR_CAMP_SLOT_EMPTY; break;
        }
        boolean hasOccupant = state != com.jubitus.travellers.worldmap.net.MsgMapCampUpdate.SlotState.EMPTY
                && state != com.jubitus.travellers.worldmap.net.MsgMapCampUpdate.SlotState.DISABLED;
        int alpha = (hasOccupant && occupantVirtual) ? VIRTUAL_ALPHA : LOADED_ALPHA;
        return new GuiWorldMapPanel.Marker(pos, alpha | rgb,
                GuiWorldMapPanel.Marker.MarkerType.CAMP_SLOT, label);
    }

    public static final int COLOR_REST_SPOT = 0xFF33BBBB; // teal

    public static GuiWorldMapPanel.Marker restSpot(BlockPos pos, String label) {
        return new GuiWorldMapPanel.Marker(pos, COLOR_REST_SPOT, GuiWorldMapPanel.Marker.MarkerType.REST_SPOT, label);
    }

    /**
     * Battle: red when the viewer's units fight in it, orange otherwise, grey once ended (result in the tooltip).
     * The bar underneath shows who is still standing: the viewer's side green against red, else light against dark.
     */
    /** A fight with this many fighters or fewer (both sides, fallen included) is a skirmish: smaller marker. */
    private static final int SKIRMISH_MAX_FIGHTERS = 6;

    public static GuiWorldMapPanel.Marker battle(com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Entry e) {
        boolean running = e.result == com.jubitus.travellers.worldmap.net.MsgMapBattleUpdate.Result.RUNNING;
        int argb = !running ? COLOR_BATTLE_ENDED : e.involved ? COLOR_BATTLE_MINE : COLOR_BATTLE_OTHERS;
        int fighters = e.first.standing + e.first.fallen + e.second.standing + e.second.fallen;
        GuiWorldMapPanel.Marker m = new GuiWorldMapPanel.Marker(BattleMarkerHold.positionOf(e.id, e.pos), argb,
                fighters <= SKIRMISH_MAX_FIGHTERS ? GuiWorldMapPanel.Marker.MarkerType.BATTLE_SKIRMISH
                        : GuiWorldMapPanel.Marker.MarkerType.BATTLE, null)
                .withBattle(com.jubitus.travellers.battle.client.BattleTooltip.forMap(e));
        if (!running) return m;
        int total = e.first.standing + e.second.standing;
        float share = total <= 0 ? 0.5f : e.first.standing / (float) total;
        return e.involved ? m.withBalance(share, 0x55CC55, 0xDD3333) : m.withBalance(share, 0xE0E0E0, 0x707070);
    }

    private static int argb(int rgb) { return 0xFF000000 | (rgb & 0xFFFFFF); }
    private static int clamp(int v)  { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
