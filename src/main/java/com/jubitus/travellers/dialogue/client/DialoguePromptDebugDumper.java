package com.jubitus.travellers.dialogue.client;

import com.jubitus.travellers.dialogue.client.DialogueClientSettings.ContextDetailLevel;
import com.jubitus.travellers.dialogue.DialogueContext;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Diagnostic dump of exactly what a dialogue conversation sends to the local LLM, gated behind
 * {@link DialogueClientSettings#isDebugDumpEnabled()} — built after 2026-09-11 feedback that a
 * traveller still couldn't state a nearby village's culture, with no way to see what it had actually
 * been told short of reading the source. One file per GUI session (opened once, appended to on every
 * message sent), mirroring the server-side per-traveller trace tool's file idiom
 * ({@code debug/trace/TraceSession}: {@code <Name>_<uuid8>_<HHmmss>.log} under {@code logs/}).
 *
 * <p>Writes two things, since "why didn't it know X" can fail at either point: the raw
 * {@link DialogueContext} the server actually built (before any client-side tiering — this is what
 * answers "did the server even resolve it"), and then the exact message list handed to Ollama for
 * every send (this is what answers "did tiering/wording drop it").
 */
public final class DialoguePromptDebugDumper {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Dialogue");

    private final File file;

    private DialoguePromptDebugDumper(File file) {
        this.file = file;
    }

    /** Nullable — the toggle is off, or the debug folder/file couldn't be created (logged either way). */
    public static DialoguePromptDebugDumper openIfEnabled(String travellerName, UUID travellerUuid, DialogueContext context) {
        if (!DialogueClientSettings.isDebugDumpEnabled()) return null;
        try {
            File dir = new File(Minecraft.getMinecraft().gameDir, "logs/travellers-dialogue-debug");
            if (!dir.exists() && !dir.mkdirs()) {
                LOGGER.warn("Could not create {}", dir);
                return null;
            }
            String stamp = new SimpleDateFormat("HHmmss").format(new Date());
            String safeName = (travellerName == null || travellerName.isEmpty() ? "unknown" : travellerName)
                    .replaceAll("[^A-Za-z0-9]", "");
            String shortId = travellerUuid != null ? travellerUuid.toString().substring(0, 8) : "nouuid";
            File file = new File(dir, safeName + "_" + shortId + "_" + stamp + ".log");
            DialoguePromptDebugDumper dumper = new DialoguePromptDebugDumper(file);
            dumper.writeRawContext(context);
            LOGGER.info("Dialogue debug dump: {}", file);
            return dumper;
        } catch (IOException ex) {
            LOGGER.warn("Could not open dialogue debug dump file", ex);
            return null;
        }
    }

    private void writeRawContext(DialogueContext c) throws IOException {
        try (Writer w = writer()) {
            w.write("# travellers-dialogue-debug v1\n");
            w.write("# opened=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n\n");
            w.write("=== RAW DialogueContext (built server-side, before any client-side tiering) ===\n");
            w.write("travellerDisplayName        = " + c.travellerDisplayName + "\n");
            w.write("cultureId                   = " + c.cultureId + "\n");
            w.write("personalityId               = " + c.personalityId + "\n");
            w.write("activity                    = " + c.activity + "\n");
            w.write("activityVillageName         = " + c.activityVillageName + "\n");
            w.write("activityVillageCultureId    = " + c.activityVillageCultureId + "\n");
            w.write("activityVillageIsLoneBuild. = " + c.activityVillageIsLoneBuilding + "\n");
            w.write("activityVillagePopulation   = " + c.activityVillagePopulationTier + "\n");
            w.write("activityVillageRelation     = " + c.activityVillageRelationToHome + "\n");
            w.write("activityVillageDistance     = " + c.activityVillageDistanceTier + "\n");
            w.write("originVillageName           = " + c.originVillageName + "\n");
            w.write("occupationLabel             = " + c.occupationLabel + "\n");
            w.write("travelMotivePhrase          = " + c.travelMotivePhrase + "\n");
            w.write("recentlyVisitedVillages     = " + c.recentlyVisitedVillages + "\n");
            w.write("cultureFlavorText           = " + c.cultureFlavorText + "\n");
            w.write("weatherPhrase               = " + c.weatherPhrase + "\n");
            w.write("timeOfDayPhrase             = " + c.timeOfDayPhrase + "\n");
            w.write("biomeName                   = " + c.biomeName + "\n");
            w.write("terrainPhrase               = " + c.terrainPhrase + "\n");
            w.write("atBiomeBorder               = " + c.atBiomeBorder + "\n");
            w.write("borderBiomeName             = " + c.borderBiomeName + "\n");
            w.write("standingInSnow              = " + c.standingInSnow + "\n");
            w.write("healthPhrase                = " + c.healthPhrase + "\n");
            w.write("mountPhrase                 = " + c.mountPhrase + "\n");
            w.write("camping                     = " + c.camping + "\n");
            w.write("recentlyInCombat            = " + c.recentlyInCombat + "\n");
            w.write("playerHealthPhrase          = " + c.playerHealthPhrase + "\n");
            w.write("playerHungerPhrase          = " + c.playerHungerPhrase + "\n");
            w.write("equipmentDescriptions       = " + c.equipmentDescriptions + "\n");
            w.write("playerEquipmentDescriptions = " + c.playerEquipmentDescriptions + "\n");
            w.write("nearbyAnimalDescriptions    = " + c.nearbyAnimalDescriptions + "\n");
            w.write("nearbyOtherPlayerCount      = " + c.nearbyOtherPlayerCount + "\n");
            w.write("nearbyOwnWorkerCount        = " + c.nearbyOwnWorkerCount + "\n");
            w.write("nearbyOtherTravellerCount   = " + c.nearbyOtherTravellerCount + "\n");
            w.write("nearbyRaiderPresent         = " + c.nearbyRaiderPresent + "\n");
            w.write("nearbyBiomeFeatures (" + c.nearbyBiomeFeatures.size() + "):\n");
            for (DialogueContext.BiomeFeatureBlurb b : c.nearbyBiomeFeatures) {
                w.write("  - " + b.biomeName + "  dx=" + b.relativeDxBucketed + "  dz=" + b.relativeDzBucketed + "\n");
            }
            w.write("nearbySettlements (" + c.nearbySettlements.size() + "):\n");
            for (DialogueContext.NearbySettlementBlurb b : c.nearbySettlements) {
                w.write("  - " + b.name + "  culture=" + b.cultureId
                        + "  dx=" + b.relativeDxBucketed + "  dz=" + b.relativeDzBucketed
                        + "  population=" + b.populationTier + "  relation=" + b.relationToHome
                        + "  distance=" + b.distanceTier + "\n");
            }
            w.write("currentLocationVillage      = " + describeSettlementOrNull(c.currentLocationVillage) + "\n");
            w.write("adjacentVillage             = " + describeSettlementOrNull(c.adjacentVillage) + "\n");
            w.write("metPlayerBefore             = " + c.metPlayerBefore + "\n");
            w.write("memoryEnabled               = " + c.memoryEnabled + "\n");
            w.write("memoryIndexPhrases          = " + c.memoryIndexPhrases + "\n");
            w.write("lifeSummaryPhrase           = " + c.lifeSummaryPhrase + "\n");
            w.write("\n");
        }
    }

    private static String describeSettlementOrNull(DialogueContext.NearbySettlementBlurb b) {
        if (b == null) return "null";
        return b.name + "  culture=" + b.cultureId + "  population=" + b.populationTier
                + "  relation=" + b.relationToHome + "  distance=" + b.distanceTier;
    }

    /** Appends the exact message list about to be sent to Ollama — the "raw, everything sent to the LLM" record. */
    public void appendPromptSent(ContextDetailLevel tier, List<OllamaClient.ChatMessage> messages,
                                  List<OllamaClient.ToolDefinition> tools) {
        try (Writer w = writer()) {
            w.write("=== PROMPT SENT  (tier=" + tier + "  " + new SimpleDateFormat("HH:mm:ss").format(new Date()) + ") ===\n");
            if (tools == null || tools.isEmpty()) {
                w.write("tools offered: none\n");
            } else {
                StringBuilder names = new StringBuilder();
                for (OllamaClient.ToolDefinition t : tools) {
                    if (names.length() > 0) names.append(", ");
                    names.append(t.name);
                }
                w.write("tools offered: " + names + "\n");
            }
            for (OllamaClient.ChatMessage m : messages) {
                w.write("--- " + m.role + " ---\n");
                w.write(m.content);
                if (!m.content.endsWith("\n")) w.write("\n");
            }
            w.write("\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    /** Logged the moment a tool round is actually dispatched to the server (Task G3/G4). */
    public void appendToolCall(String toolName, com.google.gson.JsonObject arguments) {
        try (Writer w = writer()) {
            w.write("--- TOOL CALL: " + toolName + "(" + arguments + ") ---\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    /** Logged the moment that tool call's {@code MsgDialogueToolResult} comes back. */
    public void appendToolResult(String toolName, String resultText, boolean error) {
        try (Writer w = writer()) {
            w.write("--- TOOL RESULT" + (error ? " (error)" : "") + " for " + toolName + ": " + resultText + " ---\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    /** Closes the loop on a {@code PROMPT SENT} block - without this, a stalled/failed call left nothing after it in the log. */
    public void appendReplyReceived(String content) {
        try (Writer w = writer()) {
            w.write("--- REPLY: " + content + "\n\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    public void appendError(OllamaClient.Outcome outcome, String message) {
        try (Writer w = writer()) {
            w.write("--- ERROR (" + outcome + "): " + message + "\n\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    private Writer writer() throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
    }

    public File getFile() {
        return file;
    }
}
