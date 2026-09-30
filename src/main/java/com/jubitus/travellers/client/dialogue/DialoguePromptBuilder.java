package com.jubitus.travellers.client.dialogue;

import com.jubitus.travellers.client.dialogue.DialogueClientSettings.ContextDetailLevel;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.dialogue.DialogueContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure function, no I/O: turns a {@link DialogueContext} snapshot plus the running conversation
 * into the {@code role}/{@code content} message list Ollama's {@code /api/chat} expects. Being pure
 * and side-effect-free, this would be a reasonable first candidate for a real unit test in this
 * project (JUnit 5 is configured but unused per CLAUDE.md) — not required for this phase.
 *
 * <p>Grounding (see the dialogue plan's D-8) is enforced two ways at once: the facts handed to the
 * model are already vague (bucketed offsets, no raw coordinates — see {@link DialogueContext}), and
 * the system prompt explicitly forbids stating exact numbers and instructs an in-character "I don't
 * know" over inventing an answer.
 *
 * <p>Response language (D-10): the player asked for "whatever language I type in", so the system
 * prompt instructs the model to mirror the player's own language rather than pinning one.
 *
 * <p>Context detail tiering (context-expansion plan, Part F): the server always sends the full,
 * richest {@link DialogueContext} (E-2) — this class is what actually decides how much of it reaches
 * the prompt, based on the player's own {@link DialogueClientSettings#getContextDetailLevel()}.
 * {@code MINIMAL} writes exactly what Phase 1 wrote; each tier above it is additive.
 */
public final class DialoguePromptBuilder {

    private static final String[] CARDINALS = {
            "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"
    };

    private DialoguePromptBuilder() {
    }

    /**
     * @param elapsedTicksSinceLastTurn real in-game ticks since the player's previous message in this
     *                                  same conversation (or since the GUI opened, for the first one)
     *                                  — see {@code DialogueSession.ticksSinceLastTurn}. Deliberately
     *                                  real elapsed time, not the world's calendar (contrast {@link
     *                                  DialogueContext#timeSinceLastMetPhrase}, which is about a gap
     *                                  between separate conversations and does use the calendar) — this
     *                                  is about a pause mid-exchange, closer to "you went quiet for a
     *                                  bit" than "days have passed".
     */
    public static List<OllamaClient.ChatMessage> build(DialogueContext context, List<DialogueTurn> history,
                                                         long elapsedTicksSinceLastTurn) {
        List<OllamaClient.ChatMessage> messages = new ArrayList<>();
        // More than just the turn currently being sent means a real prior exchange already happened -
        // gates the note below so a slow first reply (reading the screen before typing) never talks
        // about "since your last exchange" when there wasn't one yet.
        boolean hasPriorTurns = history.size() > 1;
        messages.add(new OllamaClient.ChatMessage("system",
                buildSystemPrompt(context, hasPriorTurns, elapsedTicksSinceLastTurn)));
        for (DialogueTurn turn : history) {
            String role = turn.role == DialogueTurn.Role.PLAYER ? "user" : "assistant";
            messages.add(new OllamaClient.ChatMessage(role, turn.text));
        }
        return messages;
    }

    private static String buildSystemPrompt(DialogueContext context, boolean hasPriorTurns, long elapsedTicksSinceLastTurn) {
        ContextDetailLevel tier = DialogueClientSettings.getContextDetailLevel();
        String name = context.travellerDisplayName == null || context.travellerDisplayName.isEmpty()
                ? "a traveller" : context.travellerDisplayName;

        StringBuilder sb = new StringBuilder();

        // ---- MINIMAL: identity, personality, activity, nearby settlements - exactly Phase 1's
        // original prompt content. This block must never change shape, or a MINIMAL-tier player
        // silently gets a different prompt than before this phase existed (validation checklist #2).
        sb.append("You are ").append(name).append(", a traveller");
        if (context.cultureId != null && !context.cultureId.isEmpty()) {
            sb.append(" of the ").append(context.cultureId).append(" people");
        }
        sb.append(" met on the road.\n");

        // Millénaire's own per-culture lore line, reused verbatim rather than hand-authored (2026-09-13
        // feedback: travellers should stick closer to their own culture). Basic identity, not "rich"
        // detail - same reasoning as the destination village's culture below, so it's MINIMAL tier too.
        if (context.cultureFlavorText != null && !context.cultureFlavorText.isEmpty()) {
            sb.append("What people say about your own people: ").append(context.cultureFlavorText).append("\n");
        }

        // Occupation identity (Part K) - as basic a fact about a person as their name, drawn from real
        // Millénaire data, never invented. Travel motive (Part L) - a grounded, consistent reason for
        // being on the road, rolled once at spawn. Both MINIMAL tier: this is exactly the missing data
        // behind the plan's motivating complaint ("what are you doing here, so far from home?").
        if (context.occupationLabel != null && !context.occupationLabel.isEmpty()) {
            sb.append("Your trade or role: ").append(context.occupationLabel).append(".\n");
        }
        if (context.travelMotivePhrase != null && !context.travelMotivePhrase.isEmpty()) {
            sb.append(context.travelMotivePhrase).append("\n");
        }

        // The raw personality id (e.g. "vener") means nothing to the model on its own - mentioning
        // it unqualified is what used to make the NPC talk about "its personality" without knowing
        // what that word means. Only a written character sheet actually describes anything; with
        // none, personality is simply not mentioned (see PersonalityProfileLoader's class doc).
        // Explicit boundary lines around the personality sheet (Part 0, N-7): without them, whatever
        // gets appended right after silently reads as a continuation of the sheet's own last section
        // (e.g. a "## Quirks" heading) to both a human reading a debug dump and the model itself.
        String personalitySheet = PersonalityProfileLoader.forCultureAndPersonality(context.cultureId, context.personalityId);
        if (!personalitySheet.isEmpty()) {
            sb.append("Your temperament and manner of speaking:\n---\n").append(personalitySheet).append("\n---\n");
        }

        // Early half of the language "sandwich" below (2026-09-14 feedback: sometimes replies in
        // English to a French message, seemingly at random) - placed right after identity/personality,
        // never inside the guarded MINIMAL block above, so MINIMAL-tier parity (checklist #2) still
        // holds. A single instruction late in a long prompt is easy for a small local model to drop;
        // stating it once early (primacy) and once again at the very end (recency, below) is a known
        // mitigation for weak instruction-following - not a guarantee, local models can still drift.
        sb.append("Always answer in the same language the player writes to you in.\n");

        sb.append(activityPhrase(context, tier)).append("\n");

        if (!context.nearbySettlements.isEmpty()) {
            sb.append("Settlements you know of nearby: ");
            for (int i = 0; i < context.nearbySettlements.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(describeSettlement(context.nearbySettlements.get(i), tier));
            }
            sb.append(".\n");
        }

        // ---- STANDARD/RICH: a headed list of additional facts, kept separate from the identity/
        // activity prose above so the model reads it as discrete facts rather than more narrative
        // (Part 0). Individual sentence content is unchanged - this only adds the heading itself,
        // written once even when both tiers contribute lines under it.
        StringBuilder facts = new StringBuilder();
        if (tier == ContextDetailLevel.STANDARD || tier == ContextDetailLevel.RICH) {
            appendIfNotEmpty(facts, originVillagePhrase(context));
            appendIfNotEmpty(facts, recentTravelPhrase(context));
            appendIfNotEmpty(facts, currentLocationPhrase(context));
            appendIfNotEmpty(facts, worldStatePhrase(context));
            appendIfNotEmpty(facts, travellerStatePhrase(context));
            appendIfNotEmpty(facts, nearbyEntitiesPhrase(context));
            appendIfNotEmpty(facts, metBeforePhrase(context));
            appendIfNotEmpty(facts, memoryPhrase(context));
            appendIfNotEmpty(facts, lifeSummaryPhraseText(context));
            if (hasPriorTurns) {
                appendIfNotEmpty(facts, elapsedSinceLastTurnPhrase(elapsedTicksSinceLastTurn));
            }
        }
        if (tier == ContextDetailLevel.RICH) {
            appendIfNotEmpty(facts, equipmentPhrase(context));
            appendIfNotEmpty(facts, playerStatePhrase(context));
            appendIfNotEmpty(facts, playerEquipmentPhrase(context));
        }
        if (facts.length() > 0) {
            sb.append("Additional things you know:\n").append(facts);
        }

        // ---- Rules: how to behave, grouped under one heading and separated from the character/
        // world-facts content above (Part 0) - these are a different kind of content (behavioral
        // instructions, not facts about the world or the traveller) and used to read as one
        // continuous paragraph soup together with everything above.
        //
        // 2026-09-12 fine-tuning pass (in-world feedback): three failure modes seen in real
        // transcripts - the NPC not treating this as a live face-to-face exchange, no sense of
        // living in a low-tech medieval setting, and inventing unverifiable claims about the player
        // ("that's a heavy burden you carry" to an empty-handed player). Kept to a few dense lines,
        // not a wall of rules - this all runs on small local models too (see the plan's token-budget
        // note), so every added sentence has a real, observed failure behind it.
        sb.append("How to behave:\n");
        sb.append("You are speaking face to face, right now, with the person in front of you - a live exchange, "
                + "not a story about them. You are not travelling with him, you just met him and started a conversation.\n");
        sb.append("This is a low-technology, medieval-era world, in the spirit of the year 1100. Never speak of "
                + "games, players, or not being real - you simply live here.\n");
        // Static lifestyle flavor, not tied to any DialogueContext data - cheap and always true, so it
        // isn't tier-gated (2026-09-13 feedback: give travellers a general sense of what people like
        // them actually do on the road, without needing per-behavior live-state plumbing for all of it).
        sb.append("Like other travellers, you sometimes make camp for the night, fish by a river, sing together "
                + "with others on the road, or stop a while in a village - ordinary parts of life on the road, "
                + "nothing remarkable to dwell on unless asked.\n");
        sb.append("Stay fully in character. Speak naturally and briefly, like someone actually met on the road "
                + "(a sentence or two, not a speech).\n");
        // 2026-09-13 fine-tuning pass (in-world feedback): models kept writing *action* narration
        // ("*Startles slightly, eyes darting around*") instead of plain speech. A traveller met on
        // the road speaks - it doesn't get described. This must hold for every personality, so it
        // lives here once rather than being repeated in every character sheet.
        sb.append("Only write the words you actually say out loud, in plain sentences - nothing else. Never write "
                + "actions, gestures, descriptions of your body or face, or any narration (no asterisks, no italics, "
                + "no third-person description of yourself). If a trait makes you nervous or excitable, show it "
                + "through your wording and tone alone, not through a description of what you are doing.\n");
        // Humor license (2026-09-22 feedback): several personalities are built to be genuinely funny,
        // and a small local model defaults to safe, over-explained, or watered-down humor unless told
        // otherwise. Two problems addressed at once: (1) tone - this is a rough, superstitious setting
        // where dark/crude subject matter is fair game, not something to soften with modern manners;
        // (2) delivery - land one comedic beat and stop, never narrate/announce/explain the joke (a
        // model's default reflex), which would also violate the anti-narration rule right above it.
        sb.append("This is a rough, superstitious, low-technology world - violence, death, filth, drunkenness "
                + "and misfortune can be talked about lightly or played for laughs here, the way old village "
                + "tales and farces did; do not soften this with modern sensibilities. When your temperament "
                + "calls for humor, commit to one comedic angle at a time and never announce, explain, or "
                + "comment on your own joke - just say it plainly and move on. If your character is foolish or "
                + "dim-witted, sound genuinely and confidently certain of absurd or wrong things - you are not "
                + "aware that you are being foolish, and you never wink at the listener about it.\n");
        // Recency half of the sandwich (see the early copy above) - stated again, more forcefully,
        // right before generation since it's the instruction most often dropped by a small local model.
        sb.append("IMPORTANT: always answer in the exact same language as the player's latest message below - "
                + "if they wrote in French, answer only in French; if English, answer only in English. Never "
                + "answer in a different language than the one they just used, even mid-conversation.\n");
        sb.append("You only know what is written above about the world, yourself and this conversation. Never "
                + "claim to see something about the person in front of you - their clothes, what they carry, how "
                + "tired they look - unless it is stated above; vague remarks about the wider world (rumors, "
                + "distant events) are fine, specific claims about what's right in front of you are not.\n");
        sb.append("Never state exact coordinates, block numbers or distances in blocks - you don't think in "
                + "those terms. Only vague directions and rough distances, if any.\n");
        sb.append("You are stopped while talking to the person in front of you. "
                + "You are not travelling together.\n");

        return sb.toString();
    }

    /**
     * The destination/current village's culture is folded into its descriptor at every tier — this
     * is basic geographic awareness, not extra "rich" detail (2026-09-11 in-world feedback: a
     * traveller couldn't say what culture its own destination was). Population and relation-to-home
     * are real extra detail and stay STANDARD+ only.
     */
    private static String activityPhrase(DialogueContext context, ContextDetailLevel tier) {
        String village = context.activityVillageName;
        String descr = village == null ? null : villageDescriptor(context, village);

        StringBuilder sb = new StringBuilder();
        switch (context.activity) {
            case TRAVELLING_TO_VILLAGE:
                sb.append(descr != null
                        ? "You are currently travelling toward " + descr + "."
                        : "You are currently travelling, though the destination isn't fixed in your mind right now.");
                break;
            case IDLING_IN_VILLAGE:
                sb.append(descr != null
                        ? "You are currently staying in " + descr + " for a while."
                        : "You are currently staying put for a while.");
                break;
            case FOLLOWING_SOMEONE:
                sb.append("You are currently travelling together with someone else, and can't stop to talk for long.");
                break;
            case ROAMING:
            default:
                sb.append("You are currently wandering, with no fixed destination right now.");
        }

        if (village != null && tier != ContextDetailLevel.MINIMAL) {
            if (context.activityVillagePopulationTier != null && !context.activityVillagePopulationTier.isEmpty()) {
                sb.append(" It is ").append(context.activityVillagePopulationTier).append(".");
            }
            if (context.activityVillageRelationToHome != null && !context.activityVillageRelationToHome.isEmpty()) {
                sb.append(" Your home village is on ").append(context.activityVillageRelationToHome).append(" terms with it.");
            }
            if (context.activityVillageDistanceTier != null && !context.activityVillageDistanceTier.isEmpty()) {
                sb.append(" It is ").append(context.activityVillageDistanceTier).append(" from here.");
            }
        }
        return sb.toString();
    }

    private static String villageDescriptor(DialogueContext context, String village) {
        boolean hasCulture = context.activityVillageCultureId != null && !context.activityVillageCultureId.isEmpty();
        if (context.activityVillageIsLoneBuilding) {
            return hasCulture
                    ? "the " + context.activityVillageCultureId + " building of " + village
                    : "the isolated building of " + village;
        }
        if (hasCulture) {
            return "the " + context.activityVillageCultureId + " village of " + village;
        }
        return village;
    }

    /**
     * Culture is folded into the label at every tier, same as {@link #villageDescriptor} for the
     * activity village - it was being computed and sent (see {@code NearbySettlementBlurb.cultureId})
     * but never actually written into the sentence, which is exactly the bug a 2026-09-11 debug-dump
     * session caught: the destination village's culture worked, a nearby one's didn't. STANDARD/RICH
     * add population and relation on top.
     */
    private static String describeSettlement(DialogueContext.NearbySettlementBlurb blurb, ContextDetailLevel tier) {
        // No populationTier is exactly how AmbientActivityDialogueContextProvider flags a lone
        // building - there's no separate boolean on the wire, so this mirrors that signal.
        boolean isLoneBuilding = blurb.populationTier == null || blurb.populationTier.isEmpty();

        // Distance wording comes from the server (DialoguePhrasing.villageDistanceTier), scaled to this
        // world's own village spacing rather than a fixed number of blocks - cardinal direction is
        // pure geometry and stays a client-side computation from the bucketed offset.
        String distance = blurb.distanceTier != null && !blurb.distanceTier.isEmpty()
                ? blurb.distanceTier : distancePhrase(blurb.relativeDxBucketed, blurb.relativeDzBucketed);

        StringBuilder sb = new StringBuilder();
        sb.append(villageLabel(blurb)).append(" (").append(distance)
                .append(" to the ").append(cardinal(blurb.relativeDxBucketed, blurb.relativeDzBucketed));
        if (tier != ContextDetailLevel.MINIMAL) {
            if (!isLoneBuilding) {
                sb.append(", ").append(blurb.populationTier);
            }
            if (blurb.relationToHome != null && !blurb.relationToHome.isEmpty()) {
                sb.append(", ").append(blurb.relationToHome).append(" with your home village");
            }
            if (blurb.acrossSea) {
                sb.append(", across the sea");
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private static void appendIfNotEmpty(StringBuilder sb, String phrase) {
        if (phrase != null && !phrase.isEmpty()) sb.append(phrase);
    }

    private static String originVillagePhrase(DialogueContext context) {
        if (context.originVillageName == null || context.originVillageName.isEmpty()) return "";
        return "You originally come from " + context.originVillageName + ".\n";
    }

    private static String recentTravelPhrase(DialogueContext context) {
        if (context.recentlyVisitedVillages.isEmpty()) return "";
        return "You recently passed through: " + String.join(", ", context.recentlyVisitedVillages) + ".\n";
    }

    private static String nearbyEntitiesPhrase(DialogueContext context) {
        StringBuilder sb = new StringBuilder();
        if (!context.nearbyAnimalDescriptions.isEmpty()) {
            sb.append("You can see ").append(String.join(" and ", context.nearbyAnimalDescriptions)).append(" nearby. ");
        }
        if (context.nearbyOtherPlayerCount == 1) {
            sb.append("There is one other person standing around as well. ");
        } else if (context.nearbyOtherPlayerCount > 1) {
            sb.append("There are ").append(context.nearbyOtherPlayerCount).append(" other people standing around as well. ");
        }
        if (context.nearbyOwnWorkerCount == 1) {
            sb.append("One of this person's hired workers is also nearby. ");
        } else if (context.nearbyOwnWorkerCount > 1) {
            sb.append(context.nearbyOwnWorkerCount).append(" of this person's hired workers are also nearby. ");
        }
        if (context.nearbyOtherTravellerCount == 1) {
            sb.append("Another traveller is passing nearby. ");
        } else if (context.nearbyOtherTravellerCount > 1) {
            sb.append("A few other travellers are passing nearby. ");
        }
        if (context.nearbyRaiderPresent) {
            sb.append("There is a raider lurking nearby - reason enough to be on edge. ");
        }
        return sb.length() == 0 ? "" : sb.toString().trim() + "\n";
    }

    private static String metBeforePhrase(DialogueContext context) {
        if (!context.metPlayerBefore) return "";
        if (context.timeSinceLastMetPhrase == null || context.timeSinceLastMetPhrase.isEmpty()) {
            return "You have spoken with this person once before.\n";
        }
        return "You have spoken with this person before - the last time was " + context.timeSinceLastMetPhrase + ".\n";
    }

    /**
     * Physical proximity to a settlement (2026-09-14 feedback) - independent of {@link #activityPhrase},
     * which only ever names the destination/idling-in village, not wherever the traveller physically
     * happens to be standing right now while roaming or travelling elsewhere.
     */
    private static String currentLocationPhrase(DialogueContext context) {
        if (context.currentLocationVillage != null) {
            // Already said via activityPhrase when idling in this same place - don't repeat it.
            if (context.currentLocationVillage.name.equals(context.activityVillageName)) return "";
            return "You are currently standing right in " + villageLabel(context.currentLocationVillage) + ".\n";
        }
        if (context.adjacentVillage != null) {
            return "You are right next to " + villageLabel(context.adjacentVillage) + ".\n";
        }
        return "";
    }

    /** Same "the <culture> village/building of <name>" shape as {@link #describeSettlement}, without the distance/population/relation suffix. */
    private static String villageLabel(DialogueContext.NearbySettlementBlurb blurb) {
        boolean isLoneBuilding = blurb.populationTier == null || blurb.populationTier.isEmpty();
        return blurb.cultureId != null && !blurb.cultureId.isEmpty()
                ? "the " + blurb.cultureId + (isLoneBuilding ? " building of " : " village of ") + blurb.name
                : blurb.name;
    }

    /**
     * A pause mid-conversation (player took a while to reply, or tabbed out) — real elapsed ticks,
     * never bucketed against the world calendar the way {@link DialogueContext#timeSinceLastMetPhrase}
     * is, since this is about a gap the player themselves just made, not "how long ago was that other
     * conversation". 1200 ticks = one real minute at normal game speed.
     */
    private static String elapsedSinceLastTurnPhrase(long elapsedTicks) {
        if (elapsedTicks <= 0) return "";
        long minTicks = Math.max(1, TravellersConfig.DIALOGUE.messageGapMinMinutesToMention) * 1200L;
        if (elapsedTicks < minTicks) return "";
        if (elapsedTicks < 12000L) { // under ~10 real minutes
            return "A few minutes have passed since your last exchange with this person.\n";
        }
        if (elapsedTicks < 24000L) { // under ~20 real minutes
            return "A good while has passed since your last exchange with this person - long enough that "
                    + "the light itself may have shifted.\n";
        }
        return "A long while has passed since your last exchange with this person - almost like meeting again.\n";
    }

    private static String memoryPhrase(DialogueContext context) {
        if (context.memoryIndexPhrases.isEmpty()) return "";
        return "Things you remember from past conversations: "
                + String.join("; ", context.memoryIndexPhrases) + ".\n";
    }

    /** The rolling narrative summary of the traveller's whole life (Part M, N-4) - global, not scoped
     * to the interacting player, so it may cover things learned from someone else entirely. */
    private static String lifeSummaryPhraseText(DialogueContext context) {
        if (context.lifeSummaryPhrase == null || context.lifeSummaryPhrase.isEmpty()) return "";
        return "How you'd sum up your life and travels so far: " + context.lifeSummaryPhrase + "\n";
    }

    private static String worldStatePhrase(DialogueContext context) {
        boolean hasTime = context.timeOfDayPhrase != null && !context.timeOfDayPhrase.isEmpty();
        boolean hasWeather = context.weatherPhrase != null && !context.weatherPhrase.isEmpty();
        boolean hasBiome = context.biomeName != null && !context.biomeName.isEmpty();
        boolean hasTerrain = context.terrainPhrase != null && !context.terrainPhrase.isEmpty();
        boolean hasBorder = context.atBiomeBorder && context.borderBiomeName != null && !context.borderBiomeName.isEmpty();
        boolean hasLandmarks = !context.nearbyBiomeFeatures.isEmpty();
        if (!hasTime && !hasWeather && !hasBiome && !hasTerrain && !hasBorder
                && !context.standingInSnow && !hasLandmarks) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (hasTime || hasWeather) {
            sb.append("Right now it is");
            if (hasTime) sb.append(" ").append(context.timeOfDayPhrase);
            if (hasWeather) sb.append(hasTime ? " and " : " ").append(context.weatherPhrase);
            sb.append(". ");
        }
        if (hasBiome) {
            sb.append("You are standing in a ").append(context.biomeName).append(" area. ");
        }
        if (hasBorder) {
            sb.append("You are close to the edge of this area - just beyond it lies a ")
                    .append(context.borderBiomeName).append(" area. ");
        }
        if (hasTerrain) {
            sb.append("The terrain here is ").append(context.terrainPhrase).append(". ");
        }
        if (context.standingInSnow) {
            sb.append("You are standing right in the snow. ");
        }
        if (hasLandmarks) {
            sb.append("Further off you can make out: ");
            for (int i = 0; i < context.nearbyBiomeFeatures.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(describeBiomeFeature(context.nearbyBiomeFeatures.get(i)));
            }
            sb.append(".\n");
        }
        return sb.toString().trim() + "\n";
    }

    /** The server-side scanner already decides the full noun phrase (land: "...forest area", water:
     * "the sea"/"a lake"/"a river") - this only ever appends the distance/direction suffix. */
    private static String describeBiomeFeature(DialogueContext.BiomeFeatureBlurb blurb) {
        return blurb.biomeName + " (" + distancePhrase(blurb.relativeDxBucketed, blurb.relativeDzBucketed)
                + " to the " + cardinal(blurb.relativeDxBucketed, blurb.relativeDzBucketed) + ")";
    }

    private static String travellerStatePhrase(DialogueContext context) {
        StringBuilder sb = new StringBuilder();
        if (context.healthPhrase != null && !context.healthPhrase.isEmpty() && !"healthy".equals(context.healthPhrase)) {
            sb.append("You are currently feeling ").append(context.healthPhrase).append(". ");
        }
        if (context.mountPhrase != null && !context.mountPhrase.isEmpty()) {
            sb.append("You are currently ").append(context.mountPhrase).append(". ");
        }
        if (context.camping) {
            sb.append("You are camping for the time being. ");
        }
        if (context.recentlyInCombat) {
            sb.append("You were just in a skirmish a moment ago and are still a little shaken. ");
        }
        return sb.length() == 0 ? "" : sb.toString().trim() + "\n";
    }

    private static String equipmentPhrase(DialogueContext context) {
        if (context.equipmentDescriptions.isEmpty()) return "";
        return "You are currently equipped with: " + String.join(", ", context.equipmentDescriptions) + ".\n";
    }

    private static String playerEquipmentPhrase(DialogueContext context) {
        if (context.playerEquipmentDescriptions.isEmpty()) return "";
        return "The person you're talking to is currently equipped with: "
                + String.join(", ", context.playerEquipmentDescriptions) + ".\n";
    }

    private static String playerStatePhrase(DialogueContext context) {
        boolean hasHealth = context.playerHealthPhrase != null && !context.playerHealthPhrase.isEmpty();
        boolean hasHunger = context.playerHungerPhrase != null && !context.playerHungerPhrase.isEmpty();
        if (!hasHealth && !hasHunger) return "";
        StringBuilder sb = new StringBuilder("The person you're talking to looks");
        if (hasHealth) sb.append(" ").append(context.playerHealthPhrase);
        if (hasHealth && hasHunger) sb.append(" and");
        if (hasHunger) sb.append(" ").append(context.playerHungerPhrase);
        sb.append(".\n");
        return sb.toString();
    }

    /** Same angle convention as {@code LocationFormat} (+X = east, +Z = south) — mirrored, not shared, to keep this pure. */
    private static String cardinal(int dx, int dz) {
        if (dx == 0 && dz == 0) return "right here";
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        if (angle < 0) angle += 360.0;
        int index = ((int) Math.round(angle / 45.0)) % 8;
        return CARDINALS[index];
    }

    /**
     * Fixed absolute breakpoints - fine for a stretch of terrain (used by {@link #describeBiomeFeature}),
     * which has no "how far apart do these get placed" concept to scale against. A settlement's own
     * distance wording comes from the server instead ({@code NearbySettlementBlurb.distanceTier}, see
     * {@link #describeSettlement}) so it can scale with this world's actual village spacing; this stays
     * only as that field's fallback for an old/incomplete blurb.
     */
    private static String distancePhrase(int dx, int dz) {
        double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (dist < 300) return "a short walk";
        if (dist < 1000) return "a fair distance";
        if (dist < 3000) return "a long way";
        return "a very long way";
    }
}
