package com.jubitus.travellers.dialogue.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.dialogue.DialogueContext;
import net.minecraft.client.resources.I18n;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Sits between {@code GuiTravellerDialogue} and {@link LlmClient}: owns the multi-round tool-calling
 * loop (see {@code docs/npc-dialogue-tools-memory-plan.md} Task G3). A normal, tools-disabled
 * conversation passes straight through - {@link DialogueToolCatalog#currentTools()} is empty, no
 * {@code tools} field is sent, {@link LlmClient.ChatCallback#onToolCall} never fires, and this class
 * behaves exactly like a direct {@link LlmClient#chat} call.
 *
 * <p>Tool exchanges are ephemeral: the assistant "I called X" message and the tool's result only live
 * in the working message list for this one player-turn round trip, never in {@link DialogueSession}
 * history - once a round produces its final in-character content, only that content is persisted (a
 * deliberate simplification over the plan's literal G-4 wording, confirmed with the maintainer: the
 * final answer already carries the fact, so there is nothing to gain from resending the raw tool JSON
 * on every later turn, and it strictly reduces the "recurring token cost" the plan itself flags).
 */
@SideOnly(Side.CLIENT)
public final class ToolCallOrchestrator {

    public interface Callback {
        /** Already on the client thread. */
        void onToken(String token);

        /** Already on the client thread. */
        void onDone();

        /** Already on the client thread. */
        void onError(LlmClient.Outcome outcome, String message);
    }

    public interface Handle {
        void cancel();

        /**
         * Stops delivering callbacks into the (about-to-be-gone) GUI, but lets the in-flight Ollama
         * round trip — including any pending tool calls — run to completion in the background,
         * instead of aborting it. The maintainer's own explicit call: cancelling on GUI close was
         * aborting Ollama mid model-load, forcing a full reload from scratch on the next question.
         * Detaching lets a slow cold load finish and keeps the model warm in Ollama either way.
         */
        void detach();
    }

    private ToolCallOrchestrator() {
    }

    public static Handle send(DialogueContext context, DialogueSession session, int entityId,
                               long elapsedTicksSinceLastTurn, Callback callback,
                               @Nullable DialoguePromptDebugDumper debugDumper) {
        List<LlmClient.ChatMessage> messages = new ArrayList<>(
                DialoguePromptBuilder.build(context, session.historySnapshot(), elapsedTicksSinceLastTurn));
        List<LlmClient.ToolDefinition> tools = DialogueToolCatalog.currentTools(context);
        Round round = new Round(entityId, messages, tools, session, callback, debugDumper);
        round.runNext();
        return round;
    }

    /** One in-flight multi-round exchange for a single player turn. Mutable, single-use, cancellable. */
    private static final class Round implements Handle, LlmClient.ChatCallback {
        private final int entityId;
        private final List<LlmClient.ChatMessage> messages;
        private final List<LlmClient.ToolDefinition> tools;
        private final DialogueSession session;
        private final Callback callback;
        @Nullable
        private final DialoguePromptDebugDumper debugDumper;

        private int roundsUsed = 0;
        private boolean producedContentThisRound = false;
        private boolean cancelled = false;
        /** Set by {@link #detach()} — the round keeps running (network calls, tool calls, debug dump),
         * only {@link #callback} delivery is suppressed from here on. */
        private boolean detached = false;
        private final StringBuilder accumulated = new StringBuilder();
        // Per-round debug figures, reset on every send.
        private final StringBuilder thinking = new StringBuilder();
        private long sentAtMs;
        private long firstWordAtMs;
        @Nullable
        private LlmClient.ReplyStats stats;
        @Nullable
        private LlmClient.ChatHandle activeChat;

        Round(int entityId, List<LlmClient.ChatMessage> messages, List<LlmClient.ToolDefinition> tools,
              DialogueSession session, Callback callback, @Nullable DialoguePromptDebugDumper debugDumper) {
            this.entityId = entityId;
            this.messages = messages;
            this.tools = tools;
            this.session = session;
            this.callback = callback;
            this.debugDumper = debugDumper;
        }

        void runNext() {
            if (cancelled) return;
            session.setState(DialogueSession.State.GENERATING);
            if (debugDumper != null) debugDumper.appendPromptSent(messages, tools);
            thinking.setLength(0);
            sentAtMs = System.currentTimeMillis();
            firstWordAtMs = -1;
            stats = null;
            activeChat = LlmClient.chat(DialogueClientSettings.getServerType(),
                    DialogueClientSettings.getHost(), DialogueClientSettings.getPort(), DialogueClientSettings.getModel(),
                    messages, DialogueClientSettings.currentChatOptions(), tools, this);
        }

        @Override
        public void onToken(String token) {
            if (cancelled) return;
            producedContentThisRound = true;
            if (firstWordAtMs < 0) firstWordAtMs = System.currentTimeMillis();
            accumulated.append(token);
            if (!detached) callback.onToken(token);
        }

        @Override
        public void onDone() {
            if (cancelled) return;
            logRoundDebug();
            if (debugDumper != null) debugDumper.appendReplyReceived(accumulated.toString());
            if (!detached) callback.onDone();
        }

        @Override
        public void onError(LlmClient.Outcome outcome, String message) {
            if (cancelled) return;
            logRoundDebug();
            if (debugDumper != null) debugDumper.appendError(outcome, message);
            if (!detached) callback.onError(outcome, message);
        }

        @Override
        public void onToolCall(List<LlmClient.ToolCallRequest> calls) {
            if (cancelled) return;
            logRoundDebug();
            roundsUsed++;
            if (roundsUsed > Math.max(1, TravellersConfig.DIALOGUE.maxToolRoundsPerTurn)) {
                // Hit the cap: end the turn with whatever content exists, or a short fallback line if
                // this round never produced any (the model asked for a tool instead of answering).
                if (debugDumper != null) {
                    debugDumper.appendToolResult("(cap)", "maxToolRoundsPerTurn exceeded, ending turn", true);
                }
                if (!detached) {
                    if (!producedContentThisRound) {
                        callback.onToken(I18n.format("travellers.dialogue.gui.tool_cap_fallback"));
                    }
                    callback.onDone();
                }
                return;
            }
            session.setState(DialogueSession.State.AWAITING_TOOL);
            messages.add(new LlmClient.ChatMessage("assistant", "", calls));
            executeCallsSequentially(calls, 0);
        }

        @Override
        public void onThinking(String fragment) {
            thinking.append(fragment);
        }

        @Override
        public void onStats(LlmClient.ReplyStats stats) {
            this.stats = stats;
        }

        private void logRoundDebug() {
            if (debugDumper == null) return;
            if (thinking.length() > 0) {
                debugDumper.appendThinking(thinking.toString(), DialogueClientSettings.isThink());
            }
            long now = System.currentTimeMillis();
            debugDumper.appendStats(firstWordAtMs < 0 ? -1 : firstWordAtMs - sentAtMs, now - sentAtMs, stats);
        }

        private void executeCallsSequentially(List<LlmClient.ToolCallRequest> calls, int index) {
            if (cancelled) return;
            if (index >= calls.size()) {
                runNext();
                return;
            }
            LlmClient.ToolCallRequest call = calls.get(index);
            if (debugDumper != null) debugDumper.appendToolCall(call.name, call.arguments);
            DialogueToolQueryDispatcher.query(entityId, call.name, argumentsToNbt(call.arguments),
                    (resultText, error) -> {
                        if (debugDumper != null) debugDumper.appendToolResult(call.name, resultText, error);
                        if (cancelled) return;
                        messages.add(LlmClient.ChatMessage.toolResult(call, resultText == null ? "" : resultText));
                        executeCallsSequentially(calls, index + 1);
                    });
        }

        @Override
        public void cancel() {
            cancelled = true;
            if (activeChat != null) {
                activeChat.cancel();
                activeChat = null;
            }
        }

        @Override
        public void detach() {
            detached = true;
        }
    }

    /** v1 tool schemas (village_relation) only ever declare string parameters. */
    private static NBTTagCompound argumentsToNbt(JsonObject arguments) {
        NBTTagCompound tag = new NBTTagCompound();
        if (arguments == null) return tag;
        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive()) {
                tag.setString(entry.getKey(), value.getAsString());
            }
        }
        return tag;
    }
}
