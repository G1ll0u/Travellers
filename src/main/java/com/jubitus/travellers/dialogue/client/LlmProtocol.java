package com.jubitus.travellers.dialogue.client;

import com.google.gson.JsonObject;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.List;

/** The wire format of one server type; {@link LlmClient} owns connections, threads and errors. */
@SideOnly(Side.CLIENT)
interface LlmProtocol {

    String modelsPath();

    List<String> parseModels(JsonObject root);

    String chatPath();

    String buildChatBody(String model, List<LlmClient.ChatMessage> messages, LlmClient.ChatOptions options,
                         List<LlmClient.ToolDefinition> tools);

    /** A fresh parser for one streamed reply; it may keep state across lines. */
    StreamParser newStreamParser();

    interface StreamParser {
        /** Reads one non-blank streamed line; null when the line carries nothing useful. */
        @Nullable
        StreamChunk read(String line);
    }

    /** What one streamed line meant: reply text, hidden reasoning, a finished tool-call request, stats, and/or the end. */
    final class StreamChunk {
        @Nullable
        final String token;
        @Nullable
        final String thinking;
        @Nullable
        final List<LlmClient.ToolCallRequest> toolCalls;
        @Nullable
        final LlmClient.ReplyStats stats;
        final boolean done;

        StreamChunk(@Nullable String token, @Nullable String thinking, @Nullable List<LlmClient.ToolCallRequest> toolCalls,
                    @Nullable LlmClient.ReplyStats stats, boolean done) {
            this.token = token;
            this.thinking = thinking;
            this.toolCalls = toolCalls;
            this.stats = stats;
            this.done = done;
        }
    }
}
