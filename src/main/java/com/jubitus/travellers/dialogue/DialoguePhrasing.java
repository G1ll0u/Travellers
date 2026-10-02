package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.equipment.TravellerEquipmentPolicy;
import net.minecraft.item.ItemStack;
import org.millenaire.common.config.MillConfigValues;

/**
 * Shared word-bucketing helpers for every dialogue context provider (E-3 of the context-expansion
 * plan): population, village relation, health, hunger, equipment condition and village distance are
 * all sent as a handful of descriptive words, never as a raw number — see {@link DialogueContext}'s
 * class doc / plan D-8.
 */
public final class DialoguePhrasing {

    private DialoguePhrasing() {
    }

    /** Thresholds are the maintainer's own (2026-09-11 feedback), not derived from any config value. */
    static String populationTier(int villagers) {
        if (villagers >= 50) return "a very large village";
        if (villagers >= 30) return "a large village";
        if (villagers >= 20) return "a medium-sized village";
        if (villagers >= 10) return "a small village";
        return "a tiny hamlet";
    }

    /**
     * Millénaire relation, roughly -100..100 (see {@code TravelDestinationChooser.relationTerm}); null =
     * neutral. Public: also the bucketing the {@code village_relation} dialogue tool call reuses, per
     * CLAUDE.md's "don't duplicate logic" rule for relation-to-word phrasing.
     */
    public static String relationTier(Integer relation) {
        int rel = relation == null ? 0 : relation;
        if (rel < -50) return "hostile";
        if (rel < -15) return "tense";
        if (rel <= 15) return "neutral";
        if (rel <= 50) return "friendly";
        return "allied";
    }

    /** Bucketed against {@code COMBAT.lowHealthCombatFrac} - the same threshold combat AI itself uses for "low health". */
    static String healthTier(float health, float maxHealth) {
        if (maxHealth <= 0) return "unknown";
        float frac = health / maxHealth;
        float low = TravellersConfig.COMBAT.lowHealthCombatFrac;
        if (frac <= low) return "badly hurt";
        if (frac < 0.6f) return "wounded";
        return "healthy";
    }

    /**
     * Vanilla food level is 0-20; saturation is the hidden buffer above it that gets spent first, so
     * a full-but-unsaturated bar and a full-and-saturated one are genuinely different states, not
     * just cosmetically (2026-09-12 feedback: "well-fed" was shown at 70% food). Thresholds are the
     * maintainer's own: full+saturated / full only / anywhere below full / empty.
     */
    static String hungerTier(int foodLevel, float saturation) {
        if (foodLevel <= 0) return "starving";
        if (foodLevel < 20) return "hungry";
        return saturation > 0f ? "well-fed" : "fed";
    }

    /**
     * Bucketed against {@code EQUIPMENT.minDurabilityPercentToEquip} - the same threshold equipment-
     * swap AI already uses to decide "this is too far gone to equip", reused here as the worst
     * bucket's own boundary rather than a separately invented number (same precedent as
     * {@link #healthTier} reusing {@code COMBAT.lowHealthCombatFrac}).
     */
    static String durabilityTier(double fraction) {
        double worstFloor = TravellersConfig.EQUIPMENT.minDurabilityPercentToEquip / 100.0;
        if (fraction >= 0.9) return "like new";
        if (fraction >= 0.6) return "worn";
        if (fraction > worstFloor) return "battered";
        return "about to break";
    }

    /**
     * "Iron Sword (worn)" style description of one equipped/held item - the single implementation
     * shared by the traveller's own gear and the interacting player's, so condition wording is never
     * computed twice. A non-damageable stack (a stick, a block - {@code isItemStackDamageable() ==
     * false}) skips the condition suffix entirely: {@link TravellerEquipmentPolicy#durabilityFraction}
     * always reports {@code 1.0} for those, and a permanent, never-changing "(like new)" on something
     * that can never be otherwise would just be clutter in the prompt.
     */
    static String describeEquipmentPiece(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String name = stack.getDisplayName();
        if (!stack.isItemStackDamageable()) return name;
        return name + " (" + durabilityTier(TravellerEquipmentPolicy.durabilityFraction(stack)) + ")";
    }

    /**
     * How far a settlement feels, scaled to how far apart settlements actually get placed on THIS
     * world rather than a fixed number of blocks (2026-09-14 feedback: a village 300 blocks away read
     * as "very far" regardless of whether the world's villages sit a few hundred or several thousand
     * blocks apart). Reuses Millénaire's own per-world spacing config —
     * {@code minDistanceBetweenVillages}/{@code minDistanceBetweenVillagesAndLoneBuildings} — as the
     * baseline instead of inventing a new number, same precedent as {@link #healthTier}/
     * {@link #durabilityTier}. At the default settings (500/250) this lands close to the old fixed
     * breakpoints, so a default-configured world barely changes.
     *
     * <p><b>Trap:</b> {@code MillConfigValues} only holds the real per-world value on whichever side
     * actually loaded the server's config — same class of bug as {@code Culture.getCultureString}
     * silently reading the wrong language client-side (see the culture-flavor trap elsewhere in this
     * package). Call this server-side only and send the resulting word over the wire, never the raw
     * config number.
     */
    static String villageDistanceTier(double distance, boolean targetIsLoneBuilding) {
        int baseline = targetIsLoneBuilding
                ? MillConfigValues.minDistanceBetweenVillagesAndLoneBuildings
                : MillConfigValues.minDistanceBetweenVillages;
        if (baseline <= 0) baseline = targetIsLoneBuilding ? 250 : 500; // Millénaire's own defaults
        if (distance <= baseline * 0.5) return "a short walk";
        if (distance <= baseline * 1.5) return "a fair distance";
        if (distance <= baseline * 3.0) return "a long way";
        return "a very long way";
    }

    /**
     * How long ago a previous conversation with this same player was, in in-game time (1000 ticks =
     * one nominal "hour", 24000 = one day-night cycle — the same convention the time-of-day phrase
     * already implies) rather than real elapsed playtime, since a traveller's sense of time is the
     * world's own clock, not the player's wall clock. 2026-09-22 maintainer feedback: travellers had no
     * temporal awareness at all, between conversations or within one (see {@code DialoguePromptBuilder}
     * for the within-conversation counterpart, which stays real-time based since it's about a pause in
     * an ongoing exchange, not the world's calendar).
     */
    static String timeSinceTier(long elapsedTicks) {
        if (elapsedTicks < 1000L) return "just a moment ago";
        if (elapsedTicks < 24000L) return "earlier today";
        if (elapsedTicks < 3 * 24000L) return "a day or two ago";
        if (elapsedTicks < 10 * 24000L) return "several days ago";
        return "a long time ago";
    }

    /** Rough quantity word for a group of animals — never an exact count (a flock isn't something anyone stops to count). */
    static String quantityWord(int count) {
        if (count <= 1) return "a";
        if (count == 2) return "a couple of";
        if (count <= 5) return "a few";
        return "a small herd of";
    }

    /**
     * Roughness is the coarse per-chunk {@code amplitude} (max-min surface Y within the chunk, 0-255)
     * the terrain grid already tracks for Plan A route planning - reused here as a cheap text
     * alternative to sending an actual heightmap image (considered and shelved 2026-09-12: needs a
     * vision-capable local model, and a stylized elevation render is a poor fit for models trained on
     * natural photos). Thresholds are the maintainer's own, tuned by eye against real terrain.
     *
     * <p><b>Trap:</b> {@code nearMountainBiome} exists because the amplitude number alone lied for a
     * biome-estimated cell (2026-09-12 bug report): {@code BiomeTerrainEstimator} derives amplitude
     * straight from the biome's own {@code heightVariation} property, which badly undersells real
     * generated relief for a mountain-type biome ("extreme hills" read back as "gently rolling" - see
     * {@code BiomeTerrainEstimatorTest}). Never let a mountain biome describe itself as flatter than
     * "hilly", regardless of the raw number.
     */
    static String terrainTier(double avgAmplitude, double waterFraction, boolean nearMountainBiome) {
        if (waterFraction >= 0.35) return "mostly open water";
        String roughness;
        if (avgAmplitude < 20) roughness = "flat and open";
        else if (avgAmplitude < 60) roughness = "gently rolling";
        else if (avgAmplitude < 120) roughness = "hilly";
        else roughness = "steep and rugged";
        if (nearMountainBiome && (roughness.equals("flat and open") || roughness.equals("gently rolling"))) {
            roughness = "hilly";
        }
        return waterFraction >= 0.1 ? roughness + ", with water nearby" : roughness;
    }
}
