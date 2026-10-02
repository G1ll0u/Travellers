package com.jubitus.travellers.dialogue.client;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Per-player Ollama connection settings: host, port, chosen model, and whether the player has
 * opted the dialogue feature in on their own client. Deliberately NOT a {@link
 * com.jubitus.travellers.config.TravellersConfig} field — that system is server-authoritative and
 * synced identically to every client (op-gated {@code /travellersconfig}), which is the wrong shape
 * for "which local Ollama, which model" (see CLAUDE.md's dialogue system section / plan D-7).
 *
 * <p>A flat {@code Properties} file under the mod's own client config folder, mirroring the general
 * idea in {@link com.jubitus.travellers.gui.hub.WorkerHubShortcutStore} (client-local
 * preference, no server round trip, no per-world scoping) but simpler — a handful of scalar fields
 * don't need JSON.
 */
@SideOnly(Side.CLIENT)
public final class DialogueClientSettings {

    /**
     * How much world/traveller detail is folded into the system prompt (see
     * {@code docs/npc-dialogue-context-expansion-plan.md}, decision E-1). A client-local preset
     * instead of a per-field checklist, since the real tradeoff is "how much can my local model
     * handle" — one question the player can answer, not fifteen individual toggles.
     */
    public enum ContextDetailLevel {
        /** Exactly Phase 1's context: identity, personality sheet, current activity. */
        MINIMAL,
        /** Adds origin village, weather/time/biome, nearby settlements, the traveller's own state. */
        STANDARD,
        /** Adds equipment and the player's own state on top of STANDARD. */
        RICH;

        public ContextDetailLevel next() {
            ContextDetailLevel[] values = values();
            return values[(this.ordinal() + 1) % values.length];
        }
    }

    /**
     * Sentinel meaning "not set, let Ollama/the model use its own default" for the integer LLM
     * tuning fields below — distinct from every real value a player could type, including
     * {@code num_predict}'s own legitimate {@code -1} ("no limit").
     */
    public static final int UNSET_INT = OllamaClient.ChatOptions.UNSET_INT;
    /** Same idea as {@link #UNSET_INT}, for the floating-point tuning fields. */
    public static final double UNSET_DOUBLE = OllamaClient.ChatOptions.UNSET_DOUBLE;

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Dialogue");

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 11434;
    private static final ContextDetailLevel DEFAULT_DETAIL_LEVEL = ContextDetailLevel.STANDARD;

    private static boolean loaded = false;
    private static String host = DEFAULT_HOST;
    private static int port = DEFAULT_PORT;
    private static String model = "";
    private static boolean enabled = false;
    private static ContextDetailLevel contextDetailLevel = DEFAULT_DETAIL_LEVEL;
    private static boolean debugDumpPrompt = false;
    private static boolean preloadOnStartup = false;

    // LLM tuning (Ollama /api/chat "options" + "think") - see GuiDialogueAdvanced. Every numeric
    // field defaults to UNSET_INT/UNSET_DOUBLE, meaning "don't send it, let Ollama decide" - so a
    // player who never opens the advanced screen gets exactly the old hardcoded behaviour.
    private static boolean think = false;
    private static int numCtx = UNSET_INT;
    private static double temperature = UNSET_DOUBLE;
    private static double topP = UNSET_DOUBLE;
    private static int topK = UNSET_INT;
    private static double repeatPenalty = UNSET_DOUBLE;
    private static int numPredict = UNSET_INT;

    // Tool calling (Phase 3, see docs/npc-dialogue-tools-memory-plan.md decision G-1) - opt-in and
    // client-local, since whether it actually works depends entirely on which model the player runs
    // locally. Default off: an unsupported model just ignores the "tools" field and behaves as before,
    // but there's no automatic capability probing in this phase, so a manual toggle is safest.
    private static boolean toolsEnabled = false;

    private DialogueClientSettings() {
    }

    public static String getHost() {
        ensureLoaded();
        return host;
    }

    public static int getPort() {
        ensureLoaded();
        return port;
    }

    public static String getModel() {
        ensureLoaded();
        return model;
    }

    public static boolean isEnabled() {
        ensureLoaded();
        return enabled;
    }

    public static ContextDetailLevel getContextDetailLevel() {
        ensureLoaded();
        return contextDetailLevel;
    }

    /** Whether {@link DialoguePromptDebugDumper} should write out every raw context/prompt to a log file. */
    public static boolean isDebugDumpEnabled() {
        ensureLoaded();
        return debugDumpPrompt;
    }

    /**
     * A quick on/off toggle separate from {@link #save}, so flipping this on to diagnose something
     * doesn't require re-typing host/port/model - it persists immediately, on its own.
     */
    public static void setDebugDumpPrompt(boolean value) {
        ensureLoaded();
        debugDumpPrompt = value;
        writeToDisk();
    }

    /** Whether {@code DialogueModelPreloader} should warm up {@link #getModel()} as soon as the client starts. */
    public static boolean isPreloadOnStartup() {
        ensureLoaded();
        return preloadOnStartup;
    }

    /** Same immediate-persist pattern as {@link #setDebugDumpPrompt} — its own quick toggle, not part of {@link #save}. */
    public static void setPreloadOnStartup(boolean value) {
        ensureLoaded();
        preloadOnStartup = value;
        writeToDisk();
    }

    /** True once the player has both opted in and picked a model — the "ready to actually talk" gate. */
    public static boolean isConfigured() {
        ensureLoaded();
        return enabled && !model.isEmpty();
    }

    public static boolean isThink() {
        ensureLoaded();
        return think;
    }

    public static int getNumCtx() {
        ensureLoaded();
        return numCtx;
    }

    public static double getTemperature() {
        ensureLoaded();
        return temperature;
    }

    public static double getTopP() {
        ensureLoaded();
        return topP;
    }

    public static int getTopK() {
        ensureLoaded();
        return topK;
    }

    public static double getRepeatPenalty() {
        ensureLoaded();
        return repeatPenalty;
    }

    public static int getNumPredict() {
        ensureLoaded();
        return numPredict;
    }

    public static boolean isToolsEnabled() {
        ensureLoaded();
        return toolsEnabled;
    }

    /** Builds the {@link OllamaClient.ChatOptions} to send for the next chat request. */
    public static OllamaClient.ChatOptions currentChatOptions() {
        ensureLoaded();
        return new OllamaClient.ChatOptions(think, numCtx, temperature, topP, topK, repeatPenalty, numPredict);
    }

    /** Applies every field at once and persists immediately, so a "Save" button is one call. */
    public static void save(String newHost, int newPort, String newModel, boolean newEnabled,
                             ContextDetailLevel newDetailLevel) {
        ensureLoaded();
        host = (newHost == null || newHost.trim().isEmpty()) ? DEFAULT_HOST : newHost.trim();
        port = newPort > 0 && newPort <= 65535 ? newPort : DEFAULT_PORT;
        model = newModel == null ? "" : newModel.trim();
        enabled = newEnabled;
        contextDetailLevel = newDetailLevel == null ? DEFAULT_DETAIL_LEVEL : newDetailLevel;
        writeToDisk();
    }

    /**
     * Applies the LLM tuning fields (see
     * {@code com.jubitus.travellers.dialogue.gui.GuiDialogueAdvanced}) and persists them.
     */
    public static void saveLlmTuning(boolean newThink, int newNumCtx, double newTemperature, double newTopP,
                                      int newTopK, double newRepeatPenalty, int newNumPredict,
                                      boolean newToolsEnabled) {
        ensureLoaded();
        think = newThink;
        numCtx = newNumCtx;
        temperature = newTemperature;
        topP = newTopP;
        topK = newTopK;
        repeatPenalty = newRepeatPenalty;
        numPredict = newNumPredict;
        toolsEnabled = newToolsEnabled;
        writeToDisk();
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        readFromDisk();
    }

    private static File file() {
        File configDir = Loader.instance().getConfigDir();
        return new File(configDir, "travellers/dialogue_client.properties");
    }

    private static void readFromDisk() {
        File file = file();
        if (!file.exists()) return;
        Properties props = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("Could not load dialogue client settings from {}", file, ex);
            return;
        }
        host = props.getProperty("host", DEFAULT_HOST);
        model = props.getProperty("model", "");
        enabled = Boolean.parseBoolean(props.getProperty("enabled", "false"));
        try {
            port = Integer.parseInt(props.getProperty("port", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException ex) {
            port = DEFAULT_PORT;
        }
        try {
            contextDetailLevel = ContextDetailLevel.valueOf(
                    props.getProperty("contextDetailLevel", DEFAULT_DETAIL_LEVEL.name()));
        } catch (IllegalArgumentException ex) {
            contextDetailLevel = DEFAULT_DETAIL_LEVEL;
        }
        debugDumpPrompt = Boolean.parseBoolean(props.getProperty("debugDumpPrompt", "false"));
        preloadOnStartup = Boolean.parseBoolean(props.getProperty("preloadOnStartup", "false"));
        think = Boolean.parseBoolean(props.getProperty("think", "false"));
        toolsEnabled = Boolean.parseBoolean(props.getProperty("toolsEnabled", "false"));
        numCtx = parseIntOr(props.getProperty("numCtx"), UNSET_INT);
        temperature = parseDoubleOr(props.getProperty("temperature"), UNSET_DOUBLE);
        topP = parseDoubleOr(props.getProperty("topP"), UNSET_DOUBLE);
        topK = parseIntOr(props.getProperty("topK"), UNSET_INT);
        repeatPenalty = parseDoubleOr(props.getProperty("repeatPenalty"), UNSET_DOUBLE);
        numPredict = parseIntOr(props.getProperty("numPredict"), UNSET_INT);
    }

    private static int parseIntOr(String text, int fallback) {
        if (text == null) return fallback;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String text, double fallback) {
        if (text == null) return fallback;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static void writeToDisk() {
        File file = file();
        File dir = file.getParentFile();
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warn("Could not create {}", dir);
            return;
        }
        Properties props = new Properties();
        props.setProperty("host", host);
        props.setProperty("port", String.valueOf(port));
        props.setProperty("model", model);
        props.setProperty("enabled", String.valueOf(enabled));
        props.setProperty("contextDetailLevel", contextDetailLevel.name());
        props.setProperty("debugDumpPrompt", String.valueOf(debugDumpPrompt));
        props.setProperty("preloadOnStartup", String.valueOf(preloadOnStartup));
        props.setProperty("think", String.valueOf(think));
        props.setProperty("toolsEnabled", String.valueOf(toolsEnabled));
        props.setProperty("numCtx", String.valueOf(numCtx));
        props.setProperty("temperature", String.valueOf(temperature));
        props.setProperty("topP", String.valueOf(topP));
        props.setProperty("topK", String.valueOf(topK));
        props.setProperty("repeatPenalty", String.valueOf(repeatPenalty));
        props.setProperty("numPredict", String.valueOf(numPredict));
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            props.store(writer, "Travellers dialogue: client-local Ollama connection. Never synced to the server.");
        } catch (IOException ex) {
            LOGGER.warn("Could not save dialogue client settings to {}", file, ex);
        }
    }
}
