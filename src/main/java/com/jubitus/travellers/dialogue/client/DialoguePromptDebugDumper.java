package com.jubitus.travellers.dialogue.client;

import com.jubitus.travellers.dialogue.DialogueContext;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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
 * answers "did the server even resolve it"), and then the exact message list handed to the model for
 * every send (this is what answers "did tiering/wording drop it"), plus the model's reasoning, tool
 * round trips, per-reply stats and the end-of-conversation memory extraction.
 */
public final class DialoguePromptDebugDumper {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Dialogue");

    private final File file;
    /** What the previous send contained, so the next one only logs what is new. */
    private String lastSystemPrompt;
    private List<LlmClient.ChatMessage> lastLoggedConversation = new ArrayList<>();

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
            dumper.writeHeader(context);
            LOGGER.info("Dialogue debug dump: {}", file);
            return dumper;
        } catch (IOException ex) {
            LOGGER.warn("Could not open dialogue debug dump file", ex);
            return null;
        }
    }

    private void writeRawContext(DialogueContext c) throws IOException {
        try (Writer w = writer()) {
            w.write("# travellers-dialogue-debug v2\n");
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
            w.write("cultureNames                = " + c.cultureNames + "\n");
            w.write("\n");
        }
    }

    private static String describeSettlementOrNull(DialogueContext.NearbySettlementBlurb b) {
        if (b == null) return "null";
        return b.name + "  culture=" + b.cultureId + "  population=" + b.populationTier
                + "  relation=" + b.relationToHome + "  distance=" + b.distanceTier;
    }

    /** Once at the top: which server, model and settings are used, and the tools exactly as the model sees them. */
    private void writeHeader(DialogueContext context) throws IOException {
        LlmClient.ChatOptions o = DialogueClientSettings.currentChatOptions();
        try (Writer w = writer()) {
            w.write("=== CONNECTION ===\n");
            w.write("server   = " + DialogueClientSettings.getServerType().displayName + "  "
                    + DialogueClientSettings.getHost() + ":" + DialogueClientSettings.getPort() + "\n");
            w.write("model    = " + DialogueClientSettings.getModel() + "\n");
            w.write("tier     = " + DialogueClientSettings.getContextDetailLevel() + "\n");
            w.write("options  = think=" + o.think
                    + "  num_ctx=" + intOrDefault(o.numCtx) + "  temperature=" + doubleOrDefault(o.temperature)
                    + "  top_p=" + doubleOrDefault(o.topP) + "  top_k=" + intOrDefault(o.topK)
                    + "  repeat_penalty=" + doubleOrDefault(o.repeatPenalty)
                    + "  max_reply=" + intOrDefault(o.numPredict) + "\n");
            List<LlmClient.ToolDefinition> tools = DialogueToolCatalog.currentTools(context);
            if (tools.isEmpty()) {
                w.write("tools    = none\n");
            } else {
                w.write("tools (" + tools.size() + "):\n");
                for (LlmClient.ToolDefinition t : tools) {
                    w.write("  - " + t.name + ": " + t.description + "\n");
                    w.write("    parameters: " + t.parameters + "\n");
                }
            }
            w.write("\n");
        }
    }

    private static String intOrDefault(int value) {
        return value == LlmClient.ChatOptions.UNSET_INT ? "default" : String.valueOf(value);
    }

    private static String doubleOrDefault(double value) {
        return Double.isNaN(value) ? "default" : String.valueOf(value);
    }

    /**
     * Logs a send. The system prompt is written in full the first time, then only the lines that changed;
     * earlier conversation messages already logged are counted, not repeated.
     */
    public void appendPromptSent(List<LlmClient.ChatMessage> messages, List<LlmClient.ToolDefinition> tools) {
        try (Writer w = writer()) {
            w.write("=== PROMPT SENT  (" + clock() + ") ===\n");
            if (tools == null || tools.isEmpty()) {
                w.write("tools offered: none\n");
            } else {
                StringBuilder names = new StringBuilder();
                for (LlmClient.ToolDefinition t : tools) {
                    if (names.length() > 0) names.append(", ");
                    names.append(t.name);
                }
                w.write("tools offered: " + names + "\n");
            }

            int first = 0;
            if (!messages.isEmpty() && "system".equals(messages.get(0).role)) {
                writeSystemPrompt(w, messages.get(0).content);
                first = 1;
            }
            List<LlmClient.ChatMessage> rest = messages.subList(first, messages.size());
            int unchanged = 0;
            while (unchanged < rest.size() && unchanged < lastLoggedConversation.size()
                    && sameMessage(rest.get(unchanged), lastLoggedConversation.get(unchanged))) {
                unchanged++;
            }
            if (unchanged > 0) w.write("--- (" + unchanged + " earlier message(s), already logged above) ---\n");
            for (int i = unchanged; i < rest.size(); i++) {
                writeMessage(w, rest.get(i));
            }
            lastLoggedConversation = new ArrayList<>(rest);
            w.write("\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    private void writeSystemPrompt(Writer w, String system) throws IOException {
        if (lastSystemPrompt == null) {
            w.write("--- system ---\n");
            w.write(system);
            if (!system.endsWith("\n")) w.write("\n");
        } else if (lastSystemPrompt.equals(system)) {
            w.write("--- system (unchanged) ---\n");
        } else {
            // Line-level difference: enough to spot what changed in a prompt built line by line.
            List<String> oldLines = Arrays.asList(lastSystemPrompt.split("\n"));
            List<String> newLines = Arrays.asList(system.split("\n"));
            w.write("--- system (changed lines only) ---\n");
            for (String line : oldLines) {
                if (!newLines.contains(line)) w.write("- " + line + "\n");
            }
            for (String line : newLines) {
                if (!oldLines.contains(line)) w.write("+ " + line + "\n");
            }
        }
        lastSystemPrompt = system;
    }

    private static void writeMessage(Writer w, LlmClient.ChatMessage m) throws IOException {
        String label = m.role;
        if (m.toolCallId != null) label += " (answers " + m.toolCallId + ")";
        w.write("--- " + label + " ---\n");
        if (m.toolCalls != null) {
            for (LlmClient.ToolCallRequest call : m.toolCalls) {
                w.write("[asks for tool] " + call.name + call.arguments + (call.id != null ? "  id=" + call.id : "") + "\n");
            }
        }
        if (!m.content.isEmpty()) {
            w.write(m.content);
            if (!m.content.endsWith("\n")) w.write("\n");
        }
    }

    private static boolean sameMessage(LlmClient.ChatMessage a, LlmClient.ChatMessage b) {
        return a.role.equals(b.role) && a.content.equals(b.content) && a.toolCalls == b.toolCalls;
    }

    /** The model's hidden reasoning for one round; flagged when it reasons although thinking was switched off. */
    public void appendThinking(String thinking, boolean thinkRequested) {
        try (Writer w = writer()) {
            w.write("--- THINKING (" + thinking.length() + " chars) ---\n");
            if (!thinkRequested) {
                w.write("WARNING: the model reasoned although Extended thinking is off - its chat template "
                        + "probably ignores the switch, so every answer pays this delay.\n");
            }
            w.write(thinking);
            if (!thinking.endsWith("\n")) w.write("\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    /** Timing measured here plus whatever the server reported; {@code firstWordMs} is -1 when no text came. */
    public void appendStats(long firstWordMs, long totalMs, @Nullable LlmClient.ReplyStats stats) {
        StringBuilder sb = new StringBuilder("--- STATS: ");
        sb.append("first word ").append(firstWordMs < 0 ? "none" : seconds(firstWordMs));
        sb.append(", total ").append(seconds(totalMs));
        if (stats != null) {
            if (stats.loadMs > 0) sb.append(", model load ").append(seconds(stats.loadMs));
            if (stats.promptTokens >= 0) sb.append(", prompt ").append(stats.promptTokens).append(" tok");
            if (stats.replyTokens >= 0) sb.append(", reply ").append(stats.replyTokens).append(" tok");
            if (!Double.isNaN(stats.tokensPerSecond)) {
                sb.append(String.format(Locale.ROOT, ", %.1f tok/s", stats.tokensPerSecond));
            }
            if (stats.finishReason != null) sb.append(", finish=").append(stats.finishReason);
        }
        sb.append(" ---\n");
        if (stats != null && "length".equals(stats.finishReason)) {
            sb.append("WARNING: TRUNCATED - the reply hit the max reply length (or the context size) and was cut off.\n");
        }
        try (Writer w = writer()) {
            w.write(sb.toString());
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** Logged the moment a tool round is actually dispatched to the server. */
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

    public void appendError(LlmClient.Outcome outcome, String message) {
        try (Writer w = writer()) {
            w.write("--- ERROR (" + outcome + "): " + message + "\n\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    /** The end-of-conversation memory extraction request, written in full (it is a separate, one-off prompt). */
    public void appendMemoryExtractionSent(List<LlmClient.ChatMessage> messages) {
        try (Writer w = writer()) {
            w.write("=== MEMORY EXTRACTION SENT  (" + clock() + ") ===\n");
            for (LlmClient.ChatMessage m : messages) {
                writeMessage(w, m);
            }
            w.write("\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    /** What the model answered for the extraction, and what was actually kept from it. */
    public void appendMemoryExtractionResult(String rawReply, List<String> facts, String summary) {
        try (Writer w = writer()) {
            w.write("--- RAW REPLY ---\n" + rawReply + (rawReply.endsWith("\n") ? "" : "\n"));
            w.write("--- KEPT: " + facts.size() + " fact(s) ---\n");
            for (String fact : facts) w.write("  - " + fact + "\n");
            w.write("--- KEPT SUMMARY: " + (summary.isEmpty() ? "(no update)" : summary) + "\n");
            if (facts.isEmpty() && summary.isEmpty()) w.write("Nothing sent to the server.\n");
            w.write("\n");
        } catch (IOException ex) {
            LOGGER.warn("Could not append to dialogue debug dump file {}", file, ex);
        }
    }

    private static String clock() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private Writer writer() throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
    }

    public File getFile() {
        return file;
    }
}
