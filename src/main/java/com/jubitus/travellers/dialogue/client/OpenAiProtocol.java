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
import java.util.Map;
import java.util.TreeMap;

/**
 * The OpenAI chat API as served by llama.cpp's llama-server (and LM Studio, koboldcpp...):
 * {@code /v1/models}, {@code /v1/chat/completions} streamed as {@code data: {...}} lines ending in
 * {@code data: [DONE]}.
 */
@SideOnly(Side.CLIENT)
final class OpenAiProtocol implements LlmProtocol {

    static final OpenAiProtocol INSTANCE = new OpenAiProtocol();

    private static final Gson GSON = new Gson();
    private static final String DATA_PREFIX = "data:";

    private OpenAiProtocol() {
    }

    @Override
    public String modelsPath() {
        return "/v1/models";
    }

    @Override
    public List<String> parseModels(JsonObject root) {
        List<String> names = new ArrayList<>();
        if (!root.has("data")) return names;
        for (JsonElement el : root.getAsJsonArray("data")) {
            JsonObject m = el.getAsJsonObject();
            if (m.has("id")) names.add(m.get("id").getAsString());
        }
        return names;
    }

    @Override
    public String chatPath() {
        return "/v1/chat/completions";
    }

    @Override
    public String buildChatBody(String model, List<LlmClient.ChatMessage> messages, LlmClient.ChatOptions options,
                                List<LlmClient.ToolDefinition> tools) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", true);
        // llama.cpp passes this to the model's chat template; without it a reasoning model thinks by default.
        JsonObject templateArgs = new JsonObject();
        templateArgs.addProperty("enable_thinking", options.think);
        root.add("chat_template_kwargs", templateArgs);

        JsonArray msgs = new JsonArray();
        for (LlmClient.ChatMessage m : messages) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);
            o.addProperty("content", m.content);
            if (m.toolCallId != null) o.addProperty("tool_call_id", m.toolCallId);
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                JsonArray toolCallsArray = new JsonArray();
                for (LlmClient.ToolCallRequest call : m.toolCalls) {
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name);
                    function.addProperty("arguments", GSON.toJson(call.arguments));
                    JsonObject wrapper = new JsonObject();
                    if (call.id != null) wrapper.addProperty("id", call.id);
                    wrapper.addProperty("type", "function");
                    wrapper.add("function", function);
                    toolCallsArray.add(wrapper);
                }
                o.add("tool_calls", toolCallsArray);
            }
            msgs.add(o);
        }
        root.add("messages", msgs);

        if (tools != null && !tools.isEmpty()) {
            root.add("tools", toolsArray(tools));
        }

        // Context size is fixed when the server starts, so numCtx is never sent.
        if (!Double.isNaN(options.temperature)) root.addProperty("temperature", options.temperature);
        if (!Double.isNaN(options.topP)) root.addProperty("top_p", options.topP);
        if (options.topK != LlmClient.ChatOptions.UNSET_INT) root.addProperty("top_k", options.topK);
        if (!Double.isNaN(options.repeatPenalty)) root.addProperty("repeat_penalty", options.repeatPenalty);
        if (options.numPredict > 0) root.addProperty("max_tokens", options.numPredict);

        return GSON.toJson(root);
    }

    /** The {@code tools} array; Ollama accepts the same shape. */
    static JsonArray toolsArray(List<LlmClient.ToolDefinition> tools) {
        JsonArray toolsArray = new JsonArray();
        for (LlmClient.ToolDefinition tool : tools) {
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name);
            function.addProperty("description", tool.description);
            function.add("parameters", tool.parameters);
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            wrapper.add("function", function);
            toolsArray.add(wrapper);
        }
        return toolsArray;
    }

    @Override
    public StreamParser newStreamParser() {
        return new ToolCallAssembler();
    }

    /** Tool calls arrive in fragments (id and name first, then the arguments text piece by piece), keyed by index. */
    private static final class ToolCallAssembler implements StreamParser {
        private final Map<Integer, PartialCall> partials = new TreeMap<>();

        @Override
        public StreamChunk read(String line) {
            if (!line.startsWith(DATA_PREFIX)) return null;
            String payload = line.substring(DATA_PREFIX.length()).trim();
            if (payload.equals("[DONE]")) return finish(null, null, null);
            try {
                JsonObject obj = GSON.fromJson(payload, JsonObject.class);
                if (obj == null || !obj.has("choices") || obj.getAsJsonArray("choices").size() == 0) return null;
                JsonObject choice = obj.getAsJsonArray("choices").get(0).getAsJsonObject();
                String token = null;
                String thinking = null;
                if (choice.has("delta") && choice.get("delta").isJsonObject()) {
                    JsonObject delta = choice.getAsJsonObject("delta");
                    token = stringOrNull(delta, "content");
                    // llama.cpp names it reasoning_content; some other servers use reasoning.
                    thinking = stringOrNull(delta, "reasoning_content");
                    if (thinking == null) thinking = stringOrNull(delta, "reasoning");
                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        accumulate(delta.getAsJsonArray("tool_calls"));
                    }
                }
                String finishReason = stringOrNull(choice, "finish_reason");
                if (finishReason != null) return finish(token, thinking, parseStats(obj, finishReason));
                return new StreamChunk(token, thinking, null, null, false);
            } catch (JsonSyntaxException | IllegalStateException | ClassCastException ex) {
                return null;
            }
        }

        /** llama.cpp adds a "timings" object to the last chunk; "usage" is the standard OpenAI fallback. */
        private static LlmClient.ReplyStats parseStats(JsonObject obj, String finishReason) {
            int promptTokens = -1;
            int replyTokens = -1;
            double tokensPerSecond = Double.NaN;
            if (obj.has("timings") && obj.get("timings").isJsonObject()) {
                JsonObject t = obj.getAsJsonObject("timings");
                // prompt_n only counts tokens not already cached from the previous turn.
                if (t.has("prompt_n")) promptTokens = t.get("prompt_n").getAsInt() + (t.has("cache_n") ? t.get("cache_n").getAsInt() : 0);
                if (t.has("predicted_n")) replyTokens = t.get("predicted_n").getAsInt();
                if (t.has("predicted_per_second")) tokensPerSecond = t.get("predicted_per_second").getAsDouble();
            } else if (obj.has("usage") && obj.get("usage").isJsonObject()) {
                JsonObject u = obj.getAsJsonObject("usage");
                if (u.has("prompt_tokens")) promptTokens = u.get("prompt_tokens").getAsInt();
                if (u.has("completion_tokens")) replyTokens = u.get("completion_tokens").getAsInt();
            }
            return new LlmClient.ReplyStats(finishReason, promptTokens, replyTokens, tokensPerSecond, -1);
        }

        private void accumulate(JsonArray fragments) {
            for (JsonElement el : fragments) {
                JsonObject fragment = el.getAsJsonObject();
                int index = fragment.has("index") ? fragment.get("index").getAsInt() : 0;
                PartialCall partial = partials.computeIfAbsent(index, k -> new PartialCall());
                if (fragment.has("id") && !fragment.get("id").isJsonNull()) partial.id = fragment.get("id").getAsString();
                if (!fragment.has("function")) continue;
                JsonObject function = fragment.getAsJsonObject("function");
                if (function.has("name") && !function.get("name").isJsonNull()) partial.name = function.get("name").getAsString();
                if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                    partial.arguments.append(function.get("arguments").getAsString());
                }
            }
        }

        private StreamChunk finish(String token, String thinking, LlmClient.ReplyStats stats) {
            if (partials.isEmpty()) return new StreamChunk(token, thinking, null, stats, true);
            List<LlmClient.ToolCallRequest> calls = new ArrayList<>();
            for (Map.Entry<Integer, PartialCall> entry : partials.entrySet()) {
                PartialCall p = entry.getValue();
                String id = p.id != null ? p.id : "call_" + entry.getKey();
                calls.add(new LlmClient.ToolCallRequest(id, p.name, parseArguments(p.arguments.toString())));
            }
            partials.clear();
            return new StreamChunk(token, thinking, calls, stats, true);
        }

        private static JsonObject parseArguments(String text) {
            try {
                JsonObject args = GSON.fromJson(text, JsonObject.class);
                return args != null ? args : new JsonObject();
            } catch (JsonSyntaxException | IllegalStateException | ClassCastException ex) {
                return new JsonObject();
            }
        }
    }

    private static String stringOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static final class PartialCall {
        String id;
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }
}
