package com.jubitus.travellers.client.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.server.dialogue.DialogueContext;
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
 * Sits between {@code GuiTravellerDialogue} and {@link OllamaClient}: owns the multi-round tool-calling
 * loop (see {@code docs/npc-dialogue-tools-memory-plan.md} Task G3). A normal, tools-disabled
 * conversation passes straight through - {@link DialogueToolCatalog#currentTools()} is empty, no
 * {@code tools} field is sent, {@link OllamaClient.ChatCallback#onToolCall} never fires, and this class
 * behaves exactly like a direct {@link OllamaClient#chat} call.
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
        void onError(OllamaClient.Outcome outcome, String message);
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
        List<OllamaClient.ChatMessage> messages = new ArrayList<>(
                DialoguePromptBuilder.build(context, session.historySnapshot(), elapsedTicksSinceLastTurn));
        List<OllamaClient.ToolDefinition> tools = DialogueToolCatalog.currentTools(context);
        Round round = new Round(entityId, messages, tools, session, callback, debugDumper);
        round.runNext();
        return round;
    }

    /** One in-flight multi-round exchange for a single player turn. Mutable, single-use, cancellable. */
    private static final class Round implements Handle, OllamaClient.ChatCallback {
        private final int entityId;
        private final List<OllamaClient.ChatMessage> messages;
        private final List<OllamaClient.ToolDefinition> tools;
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
        private boolean firstSend = true;
        private final StringBuilder accumulated = new StringBuilder();
        @Nullable
        private OllamaClient.ChatHandle activeChat;

        Round(int entityId, List<OllamaClient.ChatMessage> messages, List<OllamaClient.ToolDefinition> tools,
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
            // The GUI's own dump of the *first* send already happened before this Round existed
            // (it needs the tools list too, see GuiTravellerDialogue) - only log the follow-up
            // round(s) here, i.e. the ones that include a tool result, or nothing tool-related would
            // ever show up in the log at all.
            if (!firstSend && debugDumper != null) {
                debugDumper.appendPromptSent(DialogueClientSettings.getContextDetailLevel(), messages, tools);
            }
            firstSend = false;
            activeChat = OllamaClient.chat(
                    DialogueClientSettings.getHost(), DialogueClientSettings.getPort(), DialogueClientSettings.getModel(),
                    messages, DialogueClientSettings.currentChatOptions(), tools, this);
        }

        @Override
        public void onToken(String token) {
            if (cancelled) return;
            producedContentThisRound = true;
            accumulated.append(token);
            if (!detached) callback.onToken(token);
        }

        @Override
        public void onDone() {
            if (cancelled) return;
            if (debugDumper != null) debugDumper.appendReplyReceived(accumulated.toString());
            if (!detached) callback.onDone();
        }

        @Override
        public void onError(OllamaClient.Outcome outcome, String message) {
            if (cancelled) return;
            if (debugDumper != null) debugDumper.appendError(outcome, message);
            if (!detached) callback.onError(outcome, message);
        }

        @Override
        public void onToolCall(List<OllamaClient.ToolCallRequest> calls) {
            if (cancelled) return;
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
            messages.add(new OllamaClient.ChatMessage("assistant", "", calls));
            executeCallsSequentially(calls, 0);
        }

        private void executeCallsSequentially(List<OllamaClient.ToolCallRequest> calls, int index) {
            if (cancelled) return;
            if (index >= calls.size()) {
                runNext();
                return;
            }
            OllamaClient.ToolCallRequest call = calls.get(index);
            if (debugDumper != null) debugDumper.appendToolCall(call.name, call.arguments);
            DialogueToolQueryDispatcher.query(entityId, call.name, argumentsToNbt(call.arguments),
                    (resultText, error) -> {
                        if (debugDumper != null) debugDumper.appendToolResult(call.name, resultText, error);
                        if (cancelled) return;
                        messages.add(new OllamaClient.ChatMessage("tool", resultText == null ? "" : resultText));
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

    /** v1 tool schemas (village_relation, recall_memory) only ever declare string parameters. */
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
