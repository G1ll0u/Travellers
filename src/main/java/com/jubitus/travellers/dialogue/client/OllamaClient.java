package com.jubitus.travellers.dialogue.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Talks to a local (or player-configured) Ollama instance over plain {@link HttpURLConnection} —
 * no new HTTP dependency, matching this project's existing habit of not adding libraries for a
 * single feature. Every network call runs on {@link #EXECUTOR}; every callback into game/GUI state
 * is marshalled back onto the client thread via {@code Minecraft.getMinecraft().addScheduledTask},
 * exactly like every other cross-thread hand-off in this codebase (route planner results, map tile
 * decode, etc.) — nothing here may touch a GUI field directly from a background thread.
 *
 * <p><b>Reasoning models: {@code "think"} defaults to {@code false} and is player-configurable
 * ({@link ChatOptions#think}, see {@code GuiDialogueAdvanced}).</b> Verified against the
 * maintainer's own locally pulled models (Qwen/Gemma "thinking"-capable builds): with thinking
 * left on, Ollama streams the model's internal chain-of-thought as a separate {@code
 * message.thinking} field and the actual in-character reply ({@code message.content}) only starts
 * once that finishes — which can take a long time on a large local model and would otherwise either
 * read as a frozen GUI or, if displayed, leak raw reasoning into what's supposed to be an in-character
 * line. {@code think:false} (the default) skips straight to {@code content}.
 *
 * <p><b>Timeouts are sized for a real local model, not a cloud API.</b> A cold local model can take
 * well over a minute just to load into memory before producing a first token (measured: ~67s for a
 * 27B model on the maintainer's own machine) — {@link #CHAT_READ_TIMEOUT_MS} is generous on purpose.
 * Connecting to the Ollama HTTP server itself, by contrast, is near-instant when it's running at all,
 * so {@link #CONNECT_TIMEOUT_MS} stays short — a slow *connect* really does mean "unreachable."
 */
@SideOnly(Side.CLIENT)
public final class OllamaClient {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Dialogue");
    private static final Gson GSON = new Gson();

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int TAGS_READ_TIMEOUT_MS = 8_000;
    /** See the class doc note on cold local model loads. */
    private static final int CHAT_READ_TIMEOUT_MS = 180_000;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Travellers-Ollama-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private OllamaClient() {
    }

    /** Why a call failed — callers branch on this to decide what to show the player. */
    public enum Outcome {
        /** Couldn't even open a connection: nothing listening at host:port. */
        UNREACHABLE,
        /** Connected, but no data arrived within the read timeout (cold model load taking too long, etc.). */
        TIMEOUT,
        /** Ollama answered but with an error (bad model name, malformed request, a 5xx...). */
        SERVER_ERROR
    }

    public interface TagsCallback {
        /** Already on the client thread. */
        void onModels(List<String> modelNames);

        /** Already on the client thread. {@code message} is short and human-readable, not a stack trace. */
        void onError(Outcome outcome, String message);
    }

    public interface PreloadCallback {
        /** Already on the client thread. */
        void onLoaded();

        /** Already on the client thread. {@code message} is short and human-readable, not a stack trace. */
        void onError(Outcome outcome, String message);
    }

    public interface ChatCallback {
        /** One incremental piece of the reply. Already on the client thread. */
        void onToken(String token);

        /** The reply is finished. Already on the client thread. */
        void onDone();

        /**
         * The model asked to call one or more tools instead of (or before) finishing with content.
         * Already on the client thread. See the class doc's wire-format note: this fires instead of
         * {@link #onDone()} for this call, the caller is expected to execute the tools and start a
         * new {@link #chat} call with the results appended to the message history.
         */
        void onToolCall(List<ToolCallRequest> calls);

        /** Already on the client thread. */
        void onError(Outcome outcome, String message);
    }

    /** Lets a caller abort an in-flight chat request, e.g. because the GUI that started it closed. */
    public interface ChatHandle {
        void cancel();
    }

    public static final class ChatMessage {
        public final String role;
        public final String content;
        /** Non-null/non-empty only for a synthetic "assistant asked for these tools" message a
         * {@code ToolCallOrchestrator} round builds to resend within that same round - never part of
         * persisted {@code DialogueSession} history (see the tools/memory plan's locked decision on
         * discarding the tool exchange once a round produces its final answer). */
        @Nullable
        public final List<ToolCallRequest> toolCalls;

        public ChatMessage(String role, String content) {
            this(role, content, null);
        }

        public ChatMessage(String role, String content, @Nullable List<ToolCallRequest> toolCalls) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
        }
    }

    /**
     * One tool the model may call, in Ollama's {@code /api/chat} {@code tools} shape (OpenAI-style
     * function calling). {@code parameters} is a JSON-schema object, built by the caller with Gson
     * directly rather than yet another schema DSL for a two-tool phase (see the tools/memory plan's
     * Task G2).
     */
    public static final class ToolDefinition {
        public final String name;
        public final String description;
        public final JsonObject parameters;

        public ToolDefinition(String name, String description, JsonObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }

    /**
     * One tool invocation the model requested. {@code arguments} is whatever JSON object Ollama sent
     * back for that call - already parsed, not a string to re-parse (confirmed against a real Ollama
     * instance, see the tools/memory plan's Task G2 probe note).
     */
    public static final class ToolCallRequest {
        public final String name;
        public final JsonObject arguments;

        public ToolCallRequest(String name, JsonObject arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }

    /**
     * Player-tunable generation parameters (see {@code GuiDialogueAdvanced}), mirroring Ollama's own
     * {@code /api/chat} request shape: {@link #think} is a top-level field, everything else nests
     * under {@code options}. Every numeric field defaults to its {@code UNSET_*} sentinel, meaning
     * "don't send it, let Ollama/the model use its own default" — {@link #defaults()} reproduces the
     * exact request this class used to hardcode.
     */
    public static final class ChatOptions {
        /** Sentinel for "not set" on an int field — distinct from every real value, including num_predict's own -1. */
        public static final int UNSET_INT = Integer.MIN_VALUE;
        /** Sentinel for "not set" on a double field. */
        public static final double UNSET_DOUBLE = Double.NaN;

        public final boolean think;
        /** Context window size in tokens. */
        public final int numCtx;
        public final double temperature;
        public final double topP;
        public final int topK;
        public final double repeatPenalty;
        /** Max tokens to generate; Ollama treats -1 as "no limit", which is a real value here, not UNSET_INT. */
        public final int numPredict;

        public ChatOptions(boolean think, int numCtx, double temperature, double topP, int topK,
                            double repeatPenalty, int numPredict) {
            this.think = think;
            this.numCtx = numCtx;
            this.temperature = temperature;
            this.topP = topP;
            this.topK = topK;
            this.repeatPenalty = repeatPenalty;
            this.numPredict = numPredict;
        }

        public static ChatOptions defaults() {
            return new ChatOptions(false, UNSET_INT, UNSET_DOUBLE, UNSET_DOUBLE, UNSET_INT, UNSET_DOUBLE, UNSET_INT);
        }
    }

    /** {@code GET /api/tags} — models already pulled locally. Used by the setup screen's model picker. */
    public static void listModels(String host, int port, TagsCallback callback) {
        EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + host + ":" + port + "/api/tags");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(TAGS_READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");

                int status = conn.getResponseCode();
                String body = readFully(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());

                if (status < 200 || status >= 300) {
                    String msg = extractErrorMessage(body, "HTTP " + status);
                    postToClient(() -> callback.onError(Outcome.SERVER_ERROR, msg));
                    return;
                }

                List<String> names = new ArrayList<>();
                JsonObject root = GSON.fromJson(body, JsonObject.class);
                if (root != null && root.has("models")) {
                    for (com.google.gson.JsonElement el : root.getAsJsonArray("models")) {
                        JsonObject m = el.getAsJsonObject();
                        if (m.has("name")) names.add(m.get("name").getAsString());
                    }
                }
                postToClient(() -> callback.onModels(names));
            } catch (SocketTimeoutException ex) {
                postToClient(() -> callback.onError(Outcome.TIMEOUT, "No response from Ollama in time."));
            } catch (IOException ex) {
                postToClient(() -> callback.onError(Outcome.UNREACHABLE, "Could not reach Ollama: " + ex.getMessage()));
            } catch (RuntimeException ex) {
                LOGGER.warn("Unexpected error listing Ollama models", ex);
                postToClient(() -> callback.onError(Outcome.SERVER_ERROR, "Unexpected error: " + ex.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * {@code POST /api/chat} with an empty message list and {@code stream:false} — Ollama's documented
     * way to load a model into memory without generating anything. Used to warm up the configured model
     * as early as possible (see {@code DialogueModelPreloader}) so a player's first real question
     * doesn't pay the cold-load cost. Shares {@link #CHAT_READ_TIMEOUT_MS} with real chat calls since
     * it is bound by the exact same model load.
     */
    public static void preload(String host, int port, String model, PreloadCallback callback) {
        EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + host + ":" + port + "/api/chat");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(CHAT_READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                JsonObject root = new JsonObject();
                root.addProperty("model", model);
                root.addProperty("stream", false);
                root.add("messages", new com.google.gson.JsonArray());
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                String body = readFully(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (status < 200 || status >= 300) {
                    String msg = extractErrorMessage(body, "HTTP " + status);
                    postToClient(() -> callback.onError(Outcome.SERVER_ERROR, msg));
                    return;
                }
                postToClient(callback::onLoaded);
            } catch (SocketTimeoutException ex) {
                postToClient(() -> callback.onError(Outcome.TIMEOUT, "Ollama took too long to load the model."));
            } catch (IOException ex) {
                postToClient(() -> callback.onError(Outcome.UNREACHABLE, "Could not reach Ollama: " + ex.getMessage()));
            } catch (RuntimeException ex) {
                LOGGER.warn("Unexpected error preloading Ollama model", ex);
                postToClient(() -> callback.onError(Outcome.SERVER_ERROR, "Unexpected error: " + ex.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * {@code POST /api/chat} with {@code stream:true}. Delivers one {@link ChatCallback#onToken}
     * per streamed content fragment, then {@link ChatCallback#onDone}. Returns a handle the caller
     * can use to abort the request (e.g. the dialogue GUI was closed mid-reply).
     */
    public static ChatHandle chat(String host, int port, String model, List<ChatMessage> messages,
                                   ChatOptions options, ChatCallback callback) {
        return chat(host, port, model, messages, options, java.util.Collections.emptyList(), callback);
    }

    /** Same as {@link #chat(String, int, String, List, ChatOptions, ChatCallback)}, with tools offered to the model. */
    public static ChatHandle chat(String host, int port, String model, List<ChatMessage> messages,
                                   ChatOptions options, List<ToolDefinition> tools, ChatCallback callback) {
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<HttpURLConnection> connRef = new java.util.concurrent.atomic.AtomicReference<>();

        EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + host + ":" + port + "/api/chat");
                conn = (HttpURLConnection) url.openConnection();
                connRef.set(conn);
                if (cancelled.get()) {
                    conn.disconnect();
                    return;
                }
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(CHAT_READ_TIMEOUT_MS);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                String body = buildChatRequestBody(model, messages, options, tools);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    String errBody = readFully(conn.getErrorStream());
                    String msg = extractErrorMessage(errBody, "HTTP " + status);
                    postToClient(() -> callback.onError(Outcome.SERVER_ERROR, msg));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!cancelled.get() && (line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        // A tool-call chunk arrives with done:false (confirmed against a real Ollama
                        // instance - see the tools/memory plan's Task G2 probe note: done_reason on the
                        // final chunk reads "stop" either way, so it can't tell the two cases apart).
                        // Checked independently of done, and short-circuits the read - nothing after
                        // it matters, the caller starts a fresh round-trip with the tool's result.
                        List<ToolCallRequest> toolCalls = extractToolCalls(line);
                        if (toolCalls != null && !toolCalls.isEmpty()) {
                            postToClient(() -> callback.onToolCall(toolCalls));
                            return;
                        }
                        String token = extractContentToken(line);
                        boolean done = extractDone(line);
                        if (token != null && !token.isEmpty()) {
                            postToClient(() -> callback.onToken(token));
                        }
                        if (done) {
                            postToClient(callback::onDone);
                            return;
                        }
                    }
                    if (!cancelled.get()) {
                        // Stream ended without a done:true line - treat as a normal finish rather than
                        // an error, Ollama has done this on some versions once the model stops.
                        postToClient(callback::onDone);
                    }
                }
            } catch (SocketTimeoutException ex) {
                if (!cancelled.get()) {
                    postToClient(() -> callback.onError(Outcome.TIMEOUT, "Ollama took too long to answer."));
                }
            } catch (IOException ex) {
                if (!cancelled.get()) {
                    postToClient(() -> callback.onError(Outcome.UNREACHABLE, "Could not reach Ollama: " + ex.getMessage()));
                }
            } catch (RuntimeException ex) {
                if (!cancelled.get()) {
                    LOGGER.warn("Unexpected error during Ollama chat", ex);
                    postToClient(() -> callback.onError(Outcome.SERVER_ERROR, "Unexpected error: " + ex.getMessage()));
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        });

        return () -> {
            cancelled.set(true);
            HttpURLConnection conn = connRef.get();
            if (conn != null) conn.disconnect();
        };
    }

    private static String buildChatRequestBody(String model, List<ChatMessage> messages, ChatOptions options,
                                                List<ToolDefinition> tools) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", true);
        // See class doc: skips the separate chain-of-thought stream on reasoning-capable models.
        root.addProperty("think", options.think);

        com.google.gson.JsonArray msgs = new com.google.gson.JsonArray();
        for (ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);
            o.addProperty("content", m.content);
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                com.google.gson.JsonArray toolCallsArray = new com.google.gson.JsonArray();
                for (ToolCallRequest call : m.toolCalls) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name);
                    function.add("arguments", call.arguments);
                    JsonObject wrapper = new JsonObject();
                    wrapper.add("function", function);
                    toolCallsArray.add(wrapper);
                }
                o.add("tool_calls", toolCallsArray);
            }
            msgs.add(o);
        }
        root.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            com.google.gson.JsonArray toolsArray = new com.google.gson.JsonArray();
            for (ToolDefinition tool : tools) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name);
                function.addProperty("description", tool.description);
                function.add("parameters", tool.parameters);
                JsonObject wrapper = new JsonObject();
                wrapper.addProperty("type", "function");
                wrapper.add("function", function);
                toolsArray.add(wrapper);
            }
            root.add("tools", toolsArray);
        }

        JsonObject genOptions = new JsonObject();
        if (options.numCtx != ChatOptions.UNSET_INT) genOptions.addProperty("num_ctx", options.numCtx);
        if (!Double.isNaN(options.temperature)) genOptions.addProperty("temperature", options.temperature);
        if (!Double.isNaN(options.topP)) genOptions.addProperty("top_p", options.topP);
        if (options.topK != ChatOptions.UNSET_INT) genOptions.addProperty("top_k", options.topK);
        if (!Double.isNaN(options.repeatPenalty)) genOptions.addProperty("repeat_penalty", options.repeatPenalty);
        if (options.numPredict != ChatOptions.UNSET_INT) genOptions.addProperty("num_predict", options.numPredict);
        if (genOptions.size() > 0) root.add("options", genOptions);

        return GSON.toJson(root);
    }

    @Nullable
    private static String extractContentToken(String ndjsonLine) {
        try {
            JsonObject obj = GSON.fromJson(ndjsonLine, JsonObject.class);
            if (obj == null || !obj.has("message")) return null;
            JsonObject message = obj.getAsJsonObject("message");
            if (message == null || !message.has("content")) return null;
            return message.get("content").getAsString();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            return null;
        }
    }

    /** Nullable/empty when this line carries no tool calls - see the wire-format note on {@link ChatCallback#onToolCall}. */
    @Nullable
    private static List<ToolCallRequest> extractToolCalls(String ndjsonLine) {
        try {
            JsonObject obj = GSON.fromJson(ndjsonLine, JsonObject.class);
            if (obj == null || !obj.has("message")) return null;
            JsonObject message = obj.getAsJsonObject("message");
            if (message == null || !message.has("tool_calls")) return null;
            List<ToolCallRequest> calls = new ArrayList<>();
            for (com.google.gson.JsonElement el : message.getAsJsonArray("tool_calls")) {
                JsonObject call = el.getAsJsonObject();
                if (!call.has("function")) continue;
                JsonObject function = call.getAsJsonObject("function");
                String name = function.has("name") ? function.get("name").getAsString() : "";
                JsonObject arguments = function.has("arguments") && function.get("arguments").isJsonObject()
                        ? function.getAsJsonObject("arguments") : new JsonObject();
                calls.add(new ToolCallRequest(name, arguments));
            }
            return calls;
        } catch (JsonSyntaxException | IllegalStateException ex) {
            return null;
        }
    }

    private static boolean extractDone(String ndjsonLine) {
        try {
            JsonObject obj = GSON.fromJson(ndjsonLine, JsonObject.class);
            return obj != null && obj.has("done") && obj.get("done").getAsBoolean();
        } catch (JsonSyntaxException | IllegalStateException ex) {
            return false;
        }
    }

    private static String extractErrorMessage(@Nullable String body, String fallback) {
        if (body == null || body.trim().isEmpty()) return fallback;
        try {
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            if (obj != null && obj.has("error")) {
                return obj.get("error").getAsString();
            }
        } catch (JsonSyntaxException | IllegalStateException ignored) {
            // fall through
        }
        return fallback;
    }

    private static String readFully(@Nullable InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void postToClient(Runnable r) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        mc.addScheduledTask(r);
    }
}
