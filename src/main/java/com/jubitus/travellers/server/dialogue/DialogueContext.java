package com.jubitus.travellers.server.dialogue;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small, immutable, already player-safe snapshot of one {@code EntityTraveller}'s situation, sent
 * to the interacting player's client so it can build an LLM prompt without ever asking the server
 * for anything else. "Player-safe" is load-bearing: no raw absolute coordinates and no internal ids
 * the player couldn't otherwise see (mirrors {@code LocationFormat}'s existing rule for
 * notifications — see the dialogue system's D-8 in the plan / CLAUDE.md). Every phrase-shaped field
 * (health, population, relation...) is bucketed into words for the same reason (E-3 of the
 * context-expansion plan).
 *
 * <p>Built by {@link DialogueContextBuilder} running the registered {@link DialogueContextProvider}s
 * in order — this class itself doesn't know how its fields get filled, which is the seam a future
 * provider (persistent per-NPC memory, say) plugs into without this class changing. Every field here
 * is always populated server-side regardless of the player's context-detail-level preference (E-2) —
 * {@code DialoguePromptBuilder} is what decides which of these actually reach the prompt.
 */
public final class DialogueContext {

    public enum ActivityKind {
        TRAVELLING_TO_VILLAGE,
        IDLING_IN_VILLAGE,
        ROAMING
    }

    /** One nearby settlement worth mentioning, curated (never a full dump) by whichever provider adds it. */
    public static final class NearbySettlementBlurb {
        public final String name;
        public final String cultureId;
        /** Offset from the traveller, rounded/bucketed — never raw world coordinates (D-8). */
        public final int relativeDxBucketed;
        public final int relativeDzBucketed;
        /** Bucketed population word (hamlet/village/town/city), never a raw count (E-3). */
        public final String populationTier;
        /** Bucketed relation-to-home word (hostile/tense/neutral/friendly/allied), or null with no home village. */
        public final String relationToHome;
        /** Bucketed distance word ("a short walk"/"a fair distance"/...), scaled to how far apart
         * settlements actually get placed on this world — see {@link DialoguePhrasing#villageDistanceTier}. */
        public final String distanceTier;
        /** Whether the straight line here crosses open sea (2026-09-17 feedback: a bearing is a
         * straight-line fact, but implies a walkable route unless the traveller also says a sea lies
         * between here and there). Never checked for a river crossing — fords/bridges/boats make that
         * a normal part of any journey, not worth a caveat. */
        public final boolean acrossSea;

        public NearbySettlementBlurb(String name, String cultureId, int relativeDxBucketed, int relativeDzBucketed,
                                      String populationTier, String relationToHome, String distanceTier,
                                      boolean acrossSea) {
            this.name = name;
            this.cultureId = cultureId;
            this.relativeDxBucketed = relativeDxBucketed;
            this.relativeDzBucketed = relativeDzBucketed;
            this.populationTier = populationTier;
            this.relationToHome = relationToHome;
            this.distanceTier = distanceTier;
            this.acrossSea = acrossSea;
        }

        NBTTagCompound toNbt() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("Name", name);
            tag.setString("CultureId", cultureId == null ? "" : cultureId);
            tag.setInteger("Dx", relativeDxBucketed);
            tag.setInteger("Dz", relativeDzBucketed);
            if (populationTier != null) tag.setString("PopulationTier", populationTier);
            if (relationToHome != null) tag.setString("RelationToHome", relationToHome);
            if (distanceTier != null) tag.setString("DistanceTier", distanceTier);
            tag.setBoolean("AcrossSea", acrossSea);
            return tag;
        }

        static NearbySettlementBlurb fromNbt(NBTTagCompound tag) {
            return new NearbySettlementBlurb(
                    tag.getString("Name"), tag.getString("CultureId"),
                    tag.getInteger("Dx"), tag.getInteger("Dz"),
                    tag.hasKey("PopulationTier") ? tag.getString("PopulationTier") : null,
                    tag.hasKey("RelationToHome") ? tag.getString("RelationToHome") : null,
                    tag.hasKey("DistanceTier") ? tag.getString("DistanceTier") : null,
                    tag.getBoolean("AcrossSea"));
        }
    }

    /** One notable stretch of landscape (mountains, forest, desert...) worth mentioning, bucketed like {@link NearbySettlementBlurb}. */
    public static final class BiomeFeatureBlurb {
        public final String biomeName;
        public final int relativeDxBucketed;
        public final int relativeDzBucketed;

        public BiomeFeatureBlurb(String biomeName, int relativeDxBucketed, int relativeDzBucketed) {
            this.biomeName = biomeName;
            this.relativeDxBucketed = relativeDxBucketed;
            this.relativeDzBucketed = relativeDzBucketed;
        }

        NBTTagCompound toNbt() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("BiomeName", biomeName);
            tag.setInteger("Dx", relativeDxBucketed);
            tag.setInteger("Dz", relativeDzBucketed);
            return tag;
        }

        static BiomeFeatureBlurb fromNbt(NBTTagCompound tag) {
            return new BiomeFeatureBlurb(tag.getString("BiomeName"), tag.getInteger("Dx"), tag.getInteger("Dz"));
        }
    }

    public final String travellerDisplayName;
    public final String cultureId;
    public final String personalityId;
    public final ActivityKind activity;
    /** Nullable — only meaningful paired with {@link ActivityKind#TRAVELLING_TO_VILLAGE} or {@link ActivityKind#IDLING_IN_VILLAGE}. */
    public final String activityVillageName;
    /** Nullable — the culture of {@link #activityVillageName}, when resolved. */
    public final String activityVillageCultureId;
    /** True when {@link #activityVillageName} is a lone building rather than a full village. */
    public final boolean activityVillageIsLoneBuilding;
    /** Nullable — bucketed population word, never set for a lone building. */
    public final String activityVillagePopulationTier;
    /** Nullable — bucketed relation-to-home word, or null with no home village / no relation data. */
    public final String activityVillageRelationToHome;
    /** Nullable — bucketed distance word, only set for {@link ActivityKind#TRAVELLING_TO_VILLAGE}
     * (an already-reached {@link ActivityKind#IDLING_IN_VILLAGE} destination has no distance worth
     * stating). Same scaling as {@link NearbySettlementBlurb#distanceTier}. */
    public final String activityVillageDistanceTier;
    public final List<NearbySettlementBlurb> nearbySettlements;

    /** Nullable — the settlement this traveller/raider calls home, if any is resolved (E1). */
    public final String originVillageName;

    /** Nullable — human-readable trade/role label drawn from real Millénaire data (identity-memory
     * plan, Part K), e.g. "Farmer" or "Merchant art". Never LLM-generated. */
    public final String occupationLabel;

    /** Nullable — a pre-phrased, deterministic reason for currently being on the road (identity-memory
     * plan, Part L). Rolled once at spawn, code-generated, never an LLM call. */
    public final String travelMotivePhrase;

    /** Names of villages recently passed through, newest first, from the same anti-ping-pong ring
     * {@code TravelDestinationChooser} keeps — never a separate history mechanism. */
    public final List<String> recentlyVisitedVillages;

    /** Millénaire's own per-culture lore line ({@code travelbook.culture.desc}), or empty if that
     * culture never wrote one. */
    public final String cultureFlavorText;

    /** "clear" / "raining" / "thundering" (E2). */
    public final String weatherPhrase;
    /** "dawn" / "day" / "dusk" / "night" (E2). */
    public final String timeOfDayPhrase;
    /** The biome's own display name at the traveller's position (E2). */
    public final String biomeName;
    /** Nullable — roughness/water text summary of the ground nearby, or null with no terrain data resident. */
    public final String terrainPhrase;
    /** True when a meaningfully different biome sits just past {@code DIALOGUE.biomeBorderCheckRadius}. */
    public final boolean atBiomeBorder;
    /** Nullable — the neighbouring biome's display name, only meaningful when {@link #atBiomeBorder}. */
    public final String borderBiomeName;
    /** Notable landscape (mountains, forest, desert...) within a wider radius, closest first, direction-bucketed. */
    public final List<BiomeFeatureBlurb> nearbyBiomeFeatures;
    /** True when the ground right under the traveller is snow-covered — independent of {@link #weatherPhrase}. */
    public final boolean standingInSnow;

    /** Name + condition of currently worn/held real items, ghost display copies filtered out (E4). */
    public final List<String> equipmentDescriptions;

    /** Bucketed health word for the traveller itself (E5). */
    public final String healthPhrase;
    /** Nullable — "riding a horse" / "leading a horse on foot", or null with no bonded mount (E5). */
    public final String mountPhrase;
    public final boolean camping;
    public final boolean recentlyInCombat;

    /** Bucketed health/hunger words for the interacting player (E6). */
    public final String playerHealthPhrase;
    public final String playerHungerPhrase;
    /** Name + condition of the interacting player's own currently worn/held items (E4/E6) — a
     * separate list from {@link #equipmentDescriptions}, since the prompt needs to describe "your
     * gear" and "their gear" as two different things, never merged. */
    public final List<String> playerEquipmentDescriptions;

    /** Grazing/passive animals worth mentioning, pre-phrased with a rough quantity ("a few sheep") —
     * never an exact count, unlike the person-shaped counts below (those are a headcount of
     * individuals a person would naturally notice exactly, not a flock nobody bothers counting). */
    public final List<String> nearbyAnimalDescriptions;
    /** Other players standing nearby, not counting whoever is actually talking right now. */
    public final int nearbyOtherPlayerCount;
    /** Nearby hired workers whose owner is the interacting player. */
    public final int nearbyOwnWorkerCount;
    /** Nearby plain travellers or other players' hired workers — excludes the interacting player's
     * own workers ({@link #nearbyOwnWorkerCount}) and raiders ({@link #nearbyRaiderPresent}). */
    public final int nearbyOtherTravellerCount;
    /** True when at least one raider is standing nearby right now. */
    public final boolean nearbyRaiderPresent;

    /** Server's {@code DIALOGUE.memoryEnabled} switch (Phase 3 memory, M-6) - lets the client gate offering the {@code recall_memory} tool without duplicating server-authoritative config client-side. */
    public final boolean memoryEnabled;
    /** Global index-tier memory phrases (identity-memory plan, Part M: no longer scoped to the
     * interacting player) - already short/bucketed text, empty unless {@link #memoryEnabled}. */
    public final List<String> memoryIndexPhrases;
    /** Nullable — the traveller's rolling narrative life summary (Part M, N-4), empty/null unless
     * {@code DIALOGUE.memorySummaryEnabled} and something has been generated yet. */
    public final String lifeSummaryPhrase;
    /** True when this player has talked to this traveller before - always computed, independent of {@link #memoryEnabled} (basic "have we met", not the opt-in fact-memory feature). */
    public final boolean metPlayerBefore;
    /** Nullable — bucketed "how long ago" word for the previous conversation, only meaningful when {@link #metPlayerBefore}. Null on an old save with no recorded timestamp yet. */
    public final String timeSinceLastMetPhrase;

    /** Nullable — the village/lone-building the traveller is physically standing inside right now (within {@code DIALOGUE.villageInsideRadius}), independent of travel-AI activity/destination. */
    public final NearbySettlementBlurb currentLocationVillage;
    /** Nullable — set only when {@link #currentLocationVillage} is null: a settlement close enough to be "right next to" ({@code DIALOGUE.villageAdjacentRadius}). */
    public final NearbySettlementBlurb adjacentVillage;

    private DialogueContext(Builder b) {
        this.travellerDisplayName = b.travellerDisplayName;
        this.cultureId = b.cultureId;
        this.personalityId = b.personalityId;
        this.activity = b.activity;
        this.activityVillageName = b.activityVillageName;
        this.activityVillageCultureId = b.activityVillageCultureId;
        this.activityVillageIsLoneBuilding = b.activityVillageIsLoneBuilding;
        this.activityVillagePopulationTier = b.activityVillagePopulationTier;
        this.activityVillageRelationToHome = b.activityVillageRelationToHome;
        this.activityVillageDistanceTier = b.activityVillageDistanceTier;
        this.nearbySettlements = Collections.unmodifiableList(new ArrayList<>(b.nearbySettlements));
        this.originVillageName = b.originVillageName;
        this.occupationLabel = b.occupationLabel;
        this.travelMotivePhrase = b.travelMotivePhrase;
        this.recentlyVisitedVillages = Collections.unmodifiableList(new ArrayList<>(b.recentlyVisitedVillages));
        this.cultureFlavorText = b.cultureFlavorText;
        this.weatherPhrase = b.weatherPhrase;
        this.timeOfDayPhrase = b.timeOfDayPhrase;
        this.biomeName = b.biomeName;
        this.terrainPhrase = b.terrainPhrase;
        this.atBiomeBorder = b.atBiomeBorder;
        this.borderBiomeName = b.borderBiomeName;
        this.nearbyBiomeFeatures = Collections.unmodifiableList(new ArrayList<>(b.nearbyBiomeFeatures));
        this.standingInSnow = b.standingInSnow;
        this.equipmentDescriptions = Collections.unmodifiableList(new ArrayList<>(b.equipmentDescriptions));
        this.healthPhrase = b.healthPhrase;
        this.mountPhrase = b.mountPhrase;
        this.camping = b.camping;
        this.recentlyInCombat = b.recentlyInCombat;
        this.playerHealthPhrase = b.playerHealthPhrase;
        this.playerHungerPhrase = b.playerHungerPhrase;
        this.playerEquipmentDescriptions = Collections.unmodifiableList(new ArrayList<>(b.playerEquipmentDescriptions));
        this.nearbyAnimalDescriptions = Collections.unmodifiableList(new ArrayList<>(b.nearbyAnimalDescriptions));
        this.nearbyOtherPlayerCount = b.nearbyOtherPlayerCount;
        this.nearbyOwnWorkerCount = b.nearbyOwnWorkerCount;
        this.nearbyOtherTravellerCount = b.nearbyOtherTravellerCount;
        this.nearbyRaiderPresent = b.nearbyRaiderPresent;
        this.memoryEnabled = b.memoryEnabled;
        this.memoryIndexPhrases = Collections.unmodifiableList(new ArrayList<>(b.memoryIndexPhrases));
        this.lifeSummaryPhrase = b.lifeSummaryPhrase;
        this.metPlayerBefore = b.metPlayerBefore;
        this.timeSinceLastMetPhrase = b.timeSinceLastMetPhrase;
        this.currentLocationVillage = b.currentLocationVillage;
        this.adjacentVillage = b.adjacentVillage;
    }

    /**
     * Serializes the whole snapshot as one NBT tag — the wire format for {@code MsgOpenTravellerDialogue}.
     * Deliberately NBT rather than hand-mirrored {@code ByteBuf} reads/writes (what this class used at
     * first): a forgotten field there compiled fine and silently never reached the client twice in one
     * session. A new field only needs adding here and in {@link #fromNbt}, both right next to each
     * other, nowhere else.
     */
    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("TravellerDisplayName", travellerDisplayName);
        tag.setString("CultureId", cultureId);
        tag.setString("PersonalityId", personalityId);
        tag.setInteger("ActivityOrdinal", activity.ordinal());
        if (activityVillageName != null) tag.setString("ActivityVillageName", activityVillageName);
        if (activityVillageCultureId != null) tag.setString("ActivityVillageCultureId", activityVillageCultureId);
        tag.setBoolean("ActivityVillageIsLoneBuilding", activityVillageIsLoneBuilding);
        if (activityVillagePopulationTier != null) tag.setString("ActivityVillagePopulationTier", activityVillagePopulationTier);
        if (activityVillageRelationToHome != null) tag.setString("ActivityVillageRelationToHome", activityVillageRelationToHome);
        if (activityVillageDistanceTier != null) tag.setString("ActivityVillageDistanceTier", activityVillageDistanceTier);

        NBTTagList settlementsTag = new NBTTagList();
        for (NearbySettlementBlurb blurb : nearbySettlements) settlementsTag.appendTag(blurb.toNbt());
        tag.setTag("NearbySettlements", settlementsTag);

        if (originVillageName != null) tag.setString("OriginVillageName", originVillageName);
        if (occupationLabel != null) tag.setString("OccupationLabel", occupationLabel);
        if (travelMotivePhrase != null) tag.setString("TravelMotivePhrase", travelMotivePhrase);

        NBTTagList recentVillagesTag = new NBTTagList();
        for (String name : recentlyVisitedVillages) recentVillagesTag.appendTag(new NBTTagString(name));
        tag.setTag("RecentlyVisitedVillages", recentVillagesTag);

        tag.setString("CultureFlavorText", cultureFlavorText);
        tag.setString("WeatherPhrase", weatherPhrase);
        tag.setString("TimeOfDayPhrase", timeOfDayPhrase);
        tag.setString("BiomeName", biomeName);
        if (terrainPhrase != null) tag.setString("TerrainPhrase", terrainPhrase);
        tag.setBoolean("AtBiomeBorder", atBiomeBorder);
        if (borderBiomeName != null) tag.setString("BorderBiomeName", borderBiomeName);
        tag.setBoolean("StandingInSnow", standingInSnow);

        NBTTagList biomeFeaturesTag = new NBTTagList();
        for (BiomeFeatureBlurb blurb : nearbyBiomeFeatures) biomeFeaturesTag.appendTag(blurb.toNbt());
        tag.setTag("NearbyBiomeFeatures", biomeFeaturesTag);

        NBTTagList equipmentTag = new NBTTagList();
        for (String desc : equipmentDescriptions) equipmentTag.appendTag(new NBTTagString(desc));
        tag.setTag("EquipmentDescriptions", equipmentTag);

        tag.setString("HealthPhrase", healthPhrase);
        if (mountPhrase != null) tag.setString("MountPhrase", mountPhrase);
        tag.setBoolean("Camping", camping);
        tag.setBoolean("RecentlyInCombat", recentlyInCombat);
        tag.setString("PlayerHealthPhrase", playerHealthPhrase);
        tag.setString("PlayerHungerPhrase", playerHungerPhrase);

        NBTTagList playerEquipmentTag = new NBTTagList();
        for (String desc : playerEquipmentDescriptions) playerEquipmentTag.appendTag(new NBTTagString(desc));
        tag.setTag("PlayerEquipmentDescriptions", playerEquipmentTag);

        NBTTagList nearbyAnimalsTag = new NBTTagList();
        for (String desc : nearbyAnimalDescriptions) nearbyAnimalsTag.appendTag(new NBTTagString(desc));
        tag.setTag("NearbyAnimalDescriptions", nearbyAnimalsTag);
        tag.setInteger("NearbyOtherPlayerCount", nearbyOtherPlayerCount);
        tag.setInteger("NearbyOwnWorkerCount", nearbyOwnWorkerCount);
        tag.setInteger("NearbyOtherTravellerCount", nearbyOtherTravellerCount);
        tag.setBoolean("NearbyRaiderPresent", nearbyRaiderPresent);

        tag.setBoolean("MemoryEnabled", memoryEnabled);
        NBTTagList memoryTag = new NBTTagList();
        for (String phrase : memoryIndexPhrases) memoryTag.appendTag(new NBTTagString(phrase));
        tag.setTag("MemoryIndexPhrases", memoryTag);
        if (lifeSummaryPhrase != null) tag.setString("LifeSummaryPhrase", lifeSummaryPhrase);
        tag.setBoolean("MetPlayerBefore", metPlayerBefore);
        if (timeSinceLastMetPhrase != null) tag.setString("TimeSinceLastMetPhrase", timeSinceLastMetPhrase);
        if (currentLocationVillage != null) tag.setTag("CurrentLocationVillage", currentLocationVillage.toNbt());
        if (adjacentVillage != null) tag.setTag("AdjacentVillage", adjacentVillage.toNbt());
        return tag;
    }

    public static DialogueContext fromNbt(NBTTagCompound tag) {
        Builder b = new Builder()
                .travellerDisplayName(tag.getString("TravellerDisplayName"))
                .cultureId(tag.getString("CultureId"))
                .personalityId(tag.getString("PersonalityId"))
                .activityVillageName(tag.hasKey("ActivityVillageName") ? tag.getString("ActivityVillageName") : null)
                .activityVillageCultureId(tag.hasKey("ActivityVillageCultureId") ? tag.getString("ActivityVillageCultureId") : null)
                .activityVillageIsLoneBuilding(tag.getBoolean("ActivityVillageIsLoneBuilding"))
                .activityVillagePopulationTier(tag.hasKey("ActivityVillagePopulationTier") ? tag.getString("ActivityVillagePopulationTier") : null)
                .activityVillageRelationToHome(tag.hasKey("ActivityVillageRelationToHome") ? tag.getString("ActivityVillageRelationToHome") : null)
                .activityVillageDistanceTier(tag.hasKey("ActivityVillageDistanceTier") ? tag.getString("ActivityVillageDistanceTier") : null)
                .originVillageName(tag.hasKey("OriginVillageName") ? tag.getString("OriginVillageName") : null)
                .occupationLabel(tag.hasKey("OccupationLabel") ? tag.getString("OccupationLabel") : null)
                .travelMotivePhrase(tag.hasKey("TravelMotivePhrase") ? tag.getString("TravelMotivePhrase") : null)
                .cultureFlavorText(tag.getString("CultureFlavorText"))
                .weatherPhrase(tag.getString("WeatherPhrase"))
                .timeOfDayPhrase(tag.getString("TimeOfDayPhrase"))
                .biomeName(tag.getString("BiomeName"))
                .terrainPhrase(tag.hasKey("TerrainPhrase") ? tag.getString("TerrainPhrase") : null)
                .atBiomeBorder(tag.getBoolean("AtBiomeBorder"))
                .borderBiomeName(tag.hasKey("BorderBiomeName") ? tag.getString("BorderBiomeName") : null)
                .standingInSnow(tag.getBoolean("StandingInSnow"))
                .healthPhrase(tag.getString("HealthPhrase"))
                .mountPhrase(tag.hasKey("MountPhrase") ? tag.getString("MountPhrase") : null)
                .camping(tag.getBoolean("Camping"))
                .recentlyInCombat(tag.getBoolean("RecentlyInCombat"))
                .playerHealthPhrase(tag.getString("PlayerHealthPhrase"))
                .playerHungerPhrase(tag.getString("PlayerHungerPhrase"))
                .nearbyOtherPlayerCount(tag.getInteger("NearbyOtherPlayerCount"))
                .nearbyOwnWorkerCount(tag.getInteger("NearbyOwnWorkerCount"))
                .nearbyOtherTravellerCount(tag.getInteger("NearbyOtherTravellerCount"))
                .nearbyRaiderPresent(tag.getBoolean("NearbyRaiderPresent"))
                .memoryEnabled(tag.getBoolean("MemoryEnabled"))
                .lifeSummaryPhrase(tag.hasKey("LifeSummaryPhrase") ? tag.getString("LifeSummaryPhrase") : null)
                .metPlayerBefore(tag.getBoolean("MetPlayerBefore"))
                .timeSinceLastMetPhrase(tag.hasKey("TimeSinceLastMetPhrase") ? tag.getString("TimeSinceLastMetPhrase") : null)
                .currentLocationVillage(tag.hasKey("CurrentLocationVillage")
                        ? NearbySettlementBlurb.fromNbt(tag.getCompoundTag("CurrentLocationVillage")) : null)
                .adjacentVillage(tag.hasKey("AdjacentVillage")
                        ? NearbySettlementBlurb.fromNbt(tag.getCompoundTag("AdjacentVillage")) : null);

        ActivityKind[] kinds = ActivityKind.values();
        int ord = tag.getInteger("ActivityOrdinal");
        b.activity(ord >= 0 && ord < kinds.length ? kinds[ord] : ActivityKind.ROAMING);

        NBTTagList settlementsTag = tag.getTagList("NearbySettlements", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < settlementsTag.tagCount(); i++) {
            b.addNearbySettlement(NearbySettlementBlurb.fromNbt(settlementsTag.getCompoundTagAt(i)));
        }

        NBTTagList recentVillagesTag = tag.getTagList("RecentlyVisitedVillages", Constants.NBT.TAG_STRING);
        for (int i = 0; i < recentVillagesTag.tagCount(); i++) {
            b.addRecentlyVisitedVillage(recentVillagesTag.getStringTagAt(i));
        }

        NBTTagList biomeFeaturesTag = tag.getTagList("NearbyBiomeFeatures", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < biomeFeaturesTag.tagCount(); i++) {
            b.addNearbyBiomeFeature(BiomeFeatureBlurb.fromNbt(biomeFeaturesTag.getCompoundTagAt(i)));
        }

        NBTTagList equipmentTag = tag.getTagList("EquipmentDescriptions", Constants.NBT.TAG_STRING);
        for (int i = 0; i < equipmentTag.tagCount(); i++) {
            b.addEquipmentDescription(equipmentTag.getStringTagAt(i));
        }

        NBTTagList nearbyAnimalsTag = tag.getTagList("NearbyAnimalDescriptions", Constants.NBT.TAG_STRING);
        for (int i = 0; i < nearbyAnimalsTag.tagCount(); i++) {
            b.addNearbyAnimalDescription(nearbyAnimalsTag.getStringTagAt(i));
        }

        NBTTagList playerEquipmentTag = tag.getTagList("PlayerEquipmentDescriptions", Constants.NBT.TAG_STRING);
        for (int i = 0; i < playerEquipmentTag.tagCount(); i++) {
            b.addPlayerEquipmentDescription(playerEquipmentTag.getStringTagAt(i));
        }

        NBTTagList memoryTag = tag.getTagList("MemoryIndexPhrases", Constants.NBT.TAG_STRING);
        for (int i = 0; i < memoryTag.tagCount(); i++) {
            b.addMemoryIndexPhrase(memoryTag.getStringTagAt(i));
        }

        return b.build();
    }

    public static final class Builder {
        private String travellerDisplayName = "";
        private String cultureId = "";
        private String personalityId = "";
        private ActivityKind activity = ActivityKind.ROAMING;
        private String activityVillageName = null;
        private String activityVillageCultureId = null;
        private boolean activityVillageIsLoneBuilding = false;
        private String activityVillagePopulationTier = null;
        private String activityVillageRelationToHome = null;
        private String activityVillageDistanceTier = null;
        private final List<NearbySettlementBlurb> nearbySettlements = new ArrayList<>();
        private String originVillageName = null;
        private String occupationLabel = null;
        private String travelMotivePhrase = null;
        private final List<String> recentlyVisitedVillages = new ArrayList<>();
        private String cultureFlavorText = "";
        private String weatherPhrase = "";
        private String timeOfDayPhrase = "";
        private String biomeName = "";
        private String terrainPhrase = null;
        private boolean atBiomeBorder = false;
        private String borderBiomeName = null;
        private final List<BiomeFeatureBlurb> nearbyBiomeFeatures = new ArrayList<>();
        private boolean standingInSnow = false;
        private final List<String> equipmentDescriptions = new ArrayList<>();
        private String healthPhrase = "";
        private String mountPhrase = null;
        private boolean camping = false;
        private boolean recentlyInCombat = false;
        private String playerHealthPhrase = "";
        private String playerHungerPhrase = "";
        private final List<String> playerEquipmentDescriptions = new ArrayList<>();
        private final List<String> nearbyAnimalDescriptions = new ArrayList<>();
        private int nearbyOtherPlayerCount = 0;
        private int nearbyOwnWorkerCount = 0;
        private int nearbyOtherTravellerCount = 0;
        private boolean nearbyRaiderPresent = false;
        private boolean memoryEnabled = false;
        private final List<String> memoryIndexPhrases = new ArrayList<>();
        private String lifeSummaryPhrase = null;
        private boolean metPlayerBefore = false;
        private String timeSinceLastMetPhrase = null;
        private NearbySettlementBlurb currentLocationVillage = null;
        private NearbySettlementBlurb adjacentVillage = null;

        public Builder travellerDisplayName(String v) {
            this.travellerDisplayName = v == null ? "" : v;
            return this;
        }

        public Builder cultureId(String v) {
            this.cultureId = v == null ? "" : v;
            return this;
        }

        public Builder personalityId(String v) {
            this.personalityId = v == null ? "" : v;
            return this;
        }

        public Builder activity(ActivityKind v) {
            this.activity = v == null ? ActivityKind.ROAMING : v;
            return this;
        }

        public Builder activityVillageName(String v) {
            this.activityVillageName = v;
            return this;
        }

        public Builder activityVillageCultureId(String v) {
            this.activityVillageCultureId = v;
            return this;
        }

        public Builder activityVillageIsLoneBuilding(boolean v) {
            this.activityVillageIsLoneBuilding = v;
            return this;
        }

        public Builder activityVillagePopulationTier(String v) {
            this.activityVillagePopulationTier = v;
            return this;
        }

        public Builder activityVillageRelationToHome(String v) {
            this.activityVillageRelationToHome = v;
            return this;
        }

        public Builder activityVillageDistanceTier(String v) {
            this.activityVillageDistanceTier = v;
            return this;
        }

        public Builder addNearbySettlement(NearbySettlementBlurb blurb) {
            if (blurb != null) this.nearbySettlements.add(blurb);
            return this;
        }

        public Builder originVillageName(String v) {
            this.originVillageName = v;
            return this;
        }

        public Builder occupationLabel(String v) {
            this.occupationLabel = v;
            return this;
        }

        public Builder travelMotivePhrase(String v) {
            this.travelMotivePhrase = v;
            return this;
        }

        public Builder addRecentlyVisitedVillage(String v) {
            if (v != null && !v.isEmpty()) this.recentlyVisitedVillages.add(v);
            return this;
        }

        public Builder cultureFlavorText(String v) {
            this.cultureFlavorText = v == null ? "" : v;
            return this;
        }

        public Builder weatherPhrase(String v) {
            this.weatherPhrase = v == null ? "" : v;
            return this;
        }

        public Builder timeOfDayPhrase(String v) {
            this.timeOfDayPhrase = v == null ? "" : v;
            return this;
        }

        public Builder biomeName(String v) {
            this.biomeName = v == null ? "" : v;
            return this;
        }

        public Builder terrainPhrase(String v) {
            this.terrainPhrase = v;
            return this;
        }

        public Builder atBiomeBorder(boolean v) {
            this.atBiomeBorder = v;
            return this;
        }

        public Builder borderBiomeName(String v) {
            this.borderBiomeName = v;
            return this;
        }

        public Builder addNearbyBiomeFeature(BiomeFeatureBlurb blurb) {
            if (blurb != null) this.nearbyBiomeFeatures.add(blurb);
            return this;
        }

        public Builder standingInSnow(boolean v) {
            this.standingInSnow = v;
            return this;
        }

        public Builder addEquipmentDescription(String v) {
            if (v != null && !v.isEmpty()) this.equipmentDescriptions.add(v);
            return this;
        }

        public Builder healthPhrase(String v) {
            this.healthPhrase = v == null ? "" : v;
            return this;
        }

        public Builder mountPhrase(String v) {
            this.mountPhrase = v;
            return this;
        }

        public Builder camping(boolean v) {
            this.camping = v;
            return this;
        }

        public Builder recentlyInCombat(boolean v) {
            this.recentlyInCombat = v;
            return this;
        }

        public Builder playerHealthPhrase(String v) {
            this.playerHealthPhrase = v == null ? "" : v;
            return this;
        }

        public Builder playerHungerPhrase(String v) {
            this.playerHungerPhrase = v == null ? "" : v;
            return this;
        }

        public Builder addPlayerEquipmentDescription(String v) {
            if (v != null && !v.isEmpty()) this.playerEquipmentDescriptions.add(v);
            return this;
        }

        public Builder addNearbyAnimalDescription(String v) {
            if (v != null && !v.isEmpty()) this.nearbyAnimalDescriptions.add(v);
            return this;
        }

        public Builder nearbyOtherPlayerCount(int v) {
            this.nearbyOtherPlayerCount = v;
            return this;
        }

        public Builder nearbyOwnWorkerCount(int v) {
            this.nearbyOwnWorkerCount = v;
            return this;
        }

        public Builder nearbyOtherTravellerCount(int v) {
            this.nearbyOtherTravellerCount = v;
            return this;
        }

        public Builder nearbyRaiderPresent(boolean v) {
            this.nearbyRaiderPresent = v;
            return this;
        }

        public Builder memoryEnabled(boolean v) {
            this.memoryEnabled = v;
            return this;
        }

        public Builder addMemoryIndexPhrase(String v) {
            if (v != null && !v.isEmpty()) this.memoryIndexPhrases.add(v);
            return this;
        }

        public Builder lifeSummaryPhrase(String v) {
            this.lifeSummaryPhrase = v;
            return this;
        }

        public Builder metPlayerBefore(boolean v) {
            this.metPlayerBefore = v;
            return this;
        }

        public Builder timeSinceLastMetPhrase(String v) {
            this.timeSinceLastMetPhrase = v;
            return this;
        }

        public Builder currentLocationVillage(NearbySettlementBlurb v) {
            this.currentLocationVillage = v;
            return this;
        }

        public Builder adjacentVillage(NearbySettlementBlurb v) {
            this.adjacentVillage = v;
            return this;
        }

        public DialogueContext build() {
            return new DialogueContext(this);
        }
    }
}
