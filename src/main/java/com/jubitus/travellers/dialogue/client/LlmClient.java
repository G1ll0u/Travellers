package com.jubitus.travellers.dialogue.client;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Talks to the player's local AI server (Ollama or an OpenAI-compatible one such as llama.cpp) over
 * plain {@link HttpURLConnection}. The wire format lives in {@link LlmProtocol}; this class owns
 * connections, threads and error reporting. Every network call runs on {@link #EXECUTOR}; every
 * callback is marshalled back onto the client thread.
 *
 * <p><b>Reasoning models: {@code think} defaults to {@code false}</b> (player-configurable, see
 * {@code GuiDialogueAdvanced}). With thinking on, the in-character reply only starts once the model's
 * hidden reasoning finishes, which on a large local model reads as a frozen GUI.
 *
 * <p><b>Timeouts are sized for a real local model.</b> A cold model can take over a minute to load
 * before its first token, so {@link #CHAT_READ_TIMEOUT_MS} is generous; a slow connect, by contrast,
 * really does mean "unreachable".
 */
@SideOnly(Side.CLIENT)
public final class LlmClient {

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
            Thread t = new Thread(r, "Travellers-LLM-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private LlmClient() {
    }

    /** Why a call failed — callers branch on this to decide what to show the player. */
    public enum Outcome {
        /** Couldn't even open a connection: nothing listening at host:port. */
        UNREACHABLE,
        /** Connected, but no data arrived within the read timeout (cold model load taking too long, etc.). */
        TIMEOUT,
        /** The server answered but with an error (bad model name, malformed request, a 5xx...). */
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
         * The model asked to call one or more tools instead of finishing with content. Already on the
         * client thread. Fires instead of {@link #onDone()}; the caller executes the tools and starts a
         * new {@link #chat} call with the results appended to the message history.
         */
        void onToolCall(List<ToolCallRequest> calls);

        /** Already on the client thread. */
        void onError(Outcome outcome, String message);

        /** A piece of the model's hidden reasoning, never shown to the player. Already on the client thread. */
        default void onThinking(String fragment) {
        }

        /** What the server reported about the finished reply; fires before onDone/onToolCall when available. */
        default void onStats(ReplyStats stats) {
        }
    }

    /** Server-reported figures about one reply, for the debug log; unknown values are -1 / NaN / null. */
    public static final class ReplyStats {
        /** "stop", "length" (cut by the length limit), "tool_calls"... */
        @Nullable
        public final String finishReason;
        public final int promptTokens;
        public final int replyTokens;
        public final double tokensPerSecond;
        /** Time the server spent loading the model first (Ollama only). */
        public final long loadMs;

        ReplyStats(@Nullable String finishReason, int promptTokens, int replyTokens, double tokensPerSecond, long loadMs) {
            this.finishReason = finishReason;
            this.promptTokens = promptTokens;
            this.replyTokens = replyTokens;
            this.tokensPerSecond = tokensPerSecond;
            this.loadMs = loadMs;
        }
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
         * persisted {@code DialogueSession} history. */
        @Nullable
        public final List<ToolCallRequest> toolCalls;
        /** Set only on a "tool" result message: the id of the call it answers (OpenAI format needs it). */
        @Nullable
        public final String toolCallId;

        public ChatMessage(String role, String content) {
            this(role, content, null, null);
        }

        public ChatMessage(String role, String content, @Nullable List<ToolCallRequest> toolCalls) {
            this(role, content, toolCalls, null);
        }

        private ChatMessage(String role, String content, @Nullable List<ToolCallRequest> toolCalls,
                            @Nullable String toolCallId) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
        }

        public static ChatMessage toolResult(ToolCallRequest call, String content) {
            return new ChatMessage("tool", content, null, call.id);
        }
    }

    /**
     * One tool the model may call (OpenAI-style function calling, which both formats share).
     * {@code parameters} is a JSON-schema object built by the caller with Gson.
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

    /** One tool invocation the model requested, with its arguments already parsed. */
    public static final class ToolCallRequest {
        /** Null on Ollama, which does not number its calls. */
        @Nullable
        public final String id;
        public final String name;
        public final JsonObject arguments;

        public ToolCallRequest(@Nullable String id, String name, JsonObject arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    /**
     * Player-tunable generation parameters (see {@code GuiDialogueAdvanced}). Every numeric field
     * defaults to its {@code UNSET_*} sentinel, meaning "don't send it, let the server/model decide".
     */
    public static final class ChatOptions {
        /** Sentinel for "not set" on an int field — distinct from every real value, including num_predict's own -1. */
        public static final int UNSET_INT = Integer.MIN_VALUE;
        /** Sentinel for "not set" on a double field. */
        public static final double UNSET_DOUBLE = Double.NaN;

        public final boolean think;
        /** Context window size in tokens; only Ollama takes it per request. */
        public final int numCtx;
        public final double temperature;
        public final double topP;
        public final int topK;
        public final double repeatPenalty;
        /** Max tokens to generate; -1 means "no limit", which is a real value here, not UNSET_INT. */
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

    /** Models the server offers. Used by the setup screen's model picker and as its connection test. */
    public static void listModels(LlmServerType type, String host, int port, TagsCallback callback) {
        EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(host, port, type.protocol.modelsPath(), TAGS_READ_TIMEOUT_MS);
                conn.setRequestMethod("GET");
                int status = conn.getResponseCode();
                String body = readFully(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
                if (status < 200 || status >= 300) {
                    String msg = extractErrorMessage(body, "HTTP " + status);
                    postToClient(() -> callback.onError(Outcome.SERVER_ERROR, msg));
                    return;
                }
                JsonObject root = GSON.fromJson(body, JsonObject.class);
                List<String> names = root == null ? Collections.emptyList() : type.protocol.parseModels(root);
                postToClient(() -> callback.onModels(names));
            } catch (SocketTimeoutException ex) {
                postToClient(() -> callback.onError(Outcome.TIMEOUT, "No response from " + type.displayName + " in time."));
            } catch (IOException ex) {
                postToClient(() -> callback.onError(Outcome.UNREACHABLE, "Could not reach " + type.displayName + ": " + ex.getMessage()));
            } catch (RuntimeException ex) {
                LOGGER.warn("Unexpected error listing models", ex);
                postToClient(() -> callback.onError(Outcome.SERVER_ERROR, "Unexpected error: " + ex.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Ollama only: {@code POST /api/chat} with no messages loads a model without generating anything,
     * so the player's first real question doesn't pay the cold-load cost (see {@code DialogueModelPreloader}).
     */
    public static void preload(String host, int port, String model, PreloadCallback callback) {
        EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(host, port, "/api/chat", CHAT_READ_TIMEOUT_MS);
                JsonObject root = new JsonObject();
                root.addProperty("model", model);
                root.addProperty("stream", false);
                root.add("messages", new com.google.gson.JsonArray());
                post(conn, GSON.toJson(root));
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

    /** Streamed chat without tools; see {@link #chat(LlmServerType, String, int, String, List, ChatOptions, List, ChatCallback)}. */
    public static ChatHandle chat(LlmServerType type, String host, int port, String model, List<ChatMessage> messages,
                                   ChatOptions options, ChatCallback callback) {
        return chat(type, host, port, model, messages, options, Collections.emptyList(), callback);
    }

    /**
     * Streamed chat: one {@link ChatCallback#onToken} per content fragment, then {@link ChatCallback#onDone}
     * (or {@link ChatCallback#onToolCall}). Returns a handle the caller can use to abort the request.
     */
    public static ChatHandle chat(LlmServerType type, String host, int port, String model, List<ChatMessage> messages,
                                   ChatOptions options, List<ToolDefinition> tools, ChatCallback callback) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<HttpURLConnection> connRef = new AtomicReference<>();
        EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(host, port, type.protocol.chatPath(), CHAT_READ_TIMEOUT_MS);
                connRef.set(conn);
                if (cancelled.get()) {
                    conn.disconnect();
                    return;
                }
                post(conn, type.protocol.buildChatBody(model, messages, options, tools));

                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    String msg = extractErrorMessage(readFully(conn.getErrorStream()), "HTTP " + status);
                    postToClient(() -> callback.onError(Outcome.SERVER_ERROR, msg));
                    return;
                }

                LlmProtocol.StreamParser parser = type.protocol.newStreamParser();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (!cancelled.get() && (line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        LlmProtocol.StreamChunk chunk = parser.read(line);
                        if (chunk == null) continue;
                        if (chunk.thinking != null && !chunk.thinking.isEmpty()) {
                            String thinking = chunk.thinking;
                            postToClient(() -> callback.onThinking(thinking));
                        }
                        if (chunk.stats != null) {
                            ReplyStats stats = chunk.stats;
                            postToClient(() -> callback.onStats(stats));
                        }
                        if (chunk.toolCalls != null && !chunk.toolCalls.isEmpty()) {
                            List<ToolCallRequest> calls = chunk.toolCalls;
                            postToClient(() -> callback.onToolCall(calls));
                            return;
                        }
                        if (chunk.token != null && !chunk.token.isEmpty()) {
                            String token = chunk.token;
                            postToClient(() -> callback.onToken(token));
                        }
                        if (chunk.done) {
                            postToClient(callback::onDone);
                            return;
                        }
                    }
                    if (!cancelled.get()) {
                        // Stream closed without an explicit done marker - treat what we got as the full reply.
                        postToClient(callback::onDone);
                    }
                }
            } catch (SocketTimeoutException ex) {
                if (!cancelled.get()) {
                    postToClient(() -> callback.onError(Outcome.TIMEOUT, type.displayName + " took too long to answer."));
                }
            } catch (IOException ex) {
                // A cancel() disconnect also lands here - only report real failures.
                if (!cancelled.get()) {
                    postToClient(() -> callback.onError(Outcome.UNREACHABLE, "Could not reach " + type.displayName + ": " + ex.getMessage()));
                }
            } catch (RuntimeException ex) {
                if (!cancelled.get()) {
                    LOGGER.warn("Unexpected error during chat", ex);
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

    private static HttpURLConnection open(String host, int port, String path, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeoutMs);
        return conn;
    }

    private static void post(HttpURLConnection conn, String json) throws IOException {
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Ollama sends {@code {"error":"..."}}, OpenAI-compatible servers {@code {"error":{"message":"..."}}}. */
    private static String extractErrorMessage(@Nullable String body, String fallback) {
        if (body == null || body.trim().isEmpty()) return fallback;
        try {
            JsonObject obj = GSON.fromJson(body, JsonObject.class);
            if (obj == null || !obj.has("error")) return fallback;
            JsonElement error = obj.get("error");
            if (error.isJsonPrimitive()) return error.getAsString();
            if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                return error.getAsJsonObject().get("message").getAsString();
            }
        } catch (JsonSyntaxException | IllegalStateException ignored) {
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
