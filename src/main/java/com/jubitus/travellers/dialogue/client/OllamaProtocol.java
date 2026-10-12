package com.jubitus.travellers.dialogue.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;

/** Ollama's native API: {@code /api/tags}, {@code /api/chat} streamed as one JSON object per line. */
@SideOnly(Side.CLIENT)
final class OllamaProtocol implements LlmProtocol {

    static final OllamaProtocol INSTANCE = new OllamaProtocol();

    private static final Gson GSON = new Gson();

    private OllamaProtocol() {
    }

    @Override
    public String modelsPath() {
        return "/api/tags";
    }

    @Override
    public List<String> parseModels(JsonObject root) {
        List<String> names = new ArrayList<>();
        if (!root.has("models")) return names;
        for (JsonElement el : root.getAsJsonArray("models")) {
            JsonObject m = el.getAsJsonObject();
            if (m.has("name")) names.add(m.get("name").getAsString());
        }
        return names;
    }

    @Override
    public String chatPath() {
        return "/api/chat";
    }

    @Override
    public String buildChatBody(String model, List<LlmClient.ChatMessage> messages, LlmClient.ChatOptions options,
                                List<LlmClient.ToolDefinition> tools) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", true);
        root.addProperty("think", options.think);

        JsonArray msgs = new JsonArray();
        for (LlmClient.ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);
            o.addProperty("content", m.content);
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                JsonArray toolCallsArray = new JsonArray();
                for (LlmClient.ToolCallRequest call : m.toolCalls) {
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
            root.add("tools", OpenAiProtocol.toolsArray(tools));
        }

        // Only send what the player actually set, so the model's own defaults apply otherwise.
        JsonObject genOptions = new JsonObject();
        if (options.numCtx != LlmClient.ChatOptions.UNSET_INT) genOptions.addProperty("num_ctx", options.numCtx);
        if (!Double.isNaN(options.temperature)) genOptions.addProperty("temperature", options.temperature);
        if (!Double.isNaN(options.topP)) genOptions.addProperty("top_p", options.topP);
        if (options.topK != LlmClient.ChatOptions.UNSET_INT) genOptions.addProperty("top_k", options.topK);
        if (!Double.isNaN(options.repeatPenalty)) genOptions.addProperty("repeat_penalty", options.repeatPenalty);
        if (options.numPredict != LlmClient.ChatOptions.UNSET_INT) genOptions.addProperty("num_predict", options.numPredict);
        if (genOptions.size() > 0) root.add("options", genOptions);

        return GSON.toJson(root);
    }

    // INVARIANT: a tool call is detected from message.tool_calls on every line, never from done_reason.
    @Override
    public StreamParser newStreamParser() {
        return line -> {
            try {
                JsonObject obj = GSON.fromJson(line, JsonObject.class);
                if (obj == null) return null;
                boolean done = obj.has("done") && obj.get("done").getAsBoolean();
                LlmClient.ReplyStats stats = done ? parseStats(obj) : null;
                JsonObject message = obj.has("message") ? obj.getAsJsonObject("message") : null;
                if (message == null) return new StreamChunk(null, null, null, stats, done);
                String thinking = stringOrNull(message, "thinking");
                if (message.has("tool_calls")) {
                    return new StreamChunk(null, thinking, parseToolCalls(message.getAsJsonArray("tool_calls")), stats, done);
                }
                return new StreamChunk(stringOrNull(message, "content"), thinking, null, stats, done);
            } catch (JsonSyntaxException | IllegalStateException | ClassCastException ex) {
                return null;
            }
        };
    }

    /** Ollama's final line carries counts and durations in nanoseconds. */
    private static LlmClient.ReplyStats parseStats(JsonObject obj) {
        int promptTokens = obj.has("prompt_eval_count") ? obj.get("prompt_eval_count").getAsInt() : -1;
        int replyTokens = obj.has("eval_count") ? obj.get("eval_count").getAsInt() : -1;
        long evalNs = obj.has("eval_duration") ? obj.get("eval_duration").getAsLong() : 0;
        double tokensPerSecond = replyTokens > 0 && evalNs > 0 ? replyTokens * 1e9 / evalNs : Double.NaN;
        long loadMs = obj.has("load_duration") ? obj.get("load_duration").getAsLong() / 1_000_000 : -1;
        return new LlmClient.ReplyStats(stringOrNull(obj, "done_reason"), promptTokens, replyTokens, tokensPerSecond, loadMs);
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /** Ollama sends each call whole, with {@code arguments} already a JSON object. */
    private static List<LlmClient.ToolCallRequest> parseToolCalls(JsonArray array) {
        List<LlmClient.ToolCallRequest> calls = new ArrayList<>();
        for (JsonElement el : array) {
            JsonObject call = el.getAsJsonObject();
            if (!call.has("function")) continue;
            JsonObject function = call.getAsJsonObject("function");
            String name = function.has("name") ? function.get("name").getAsString() : "";
            JsonObject arguments = function.has("arguments") && function.get("arguments").isJsonObject()
                    ? function.getAsJsonObject("arguments") : new JsonObject();
            calls.add(new LlmClient.ToolCallRequest(null, name, arguments));
        }
        return calls;
    }
}
