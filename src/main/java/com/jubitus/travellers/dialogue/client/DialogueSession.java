package com.jubitus.travellers.dialogue.client;

import com.jubitus.travellers.dialogue.DialogueContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * One open conversation with one NPC. Lives only in client memory (D-3/D-6 — nothing here is
 * written to disk or synced anywhere) and is discarded the moment the dialogue GUI closes, so a
 * reopened conversation always starts fresh.
 *
 * <p>The context snapshot is fetched once at GUI open and never refreshed mid-session, even if the
 * NPC's real situation changes while the player is chatting — simple and predictable for Phase 1.
 */
public final class DialogueSession {

    /** Oldest turns are dropped first once this is exceeded, to protect a small local model's context window. */
    private static final int MAX_HISTORY_TURNS = 10;

    public enum State {
        IDLE,
        GENERATING,
        /** Waiting on a {@code MsgDialogueToolResult} round trip mid-turn (Phase 3 tool calling) - distinct from {@link #GENERATING} so the GUI can show something different. */
        AWAITING_TOOL,
        ERROR,
        FALLBACK
    }

    public final UUID targetEntity;
    public final DialogueContext contextSnapshot;

    private final Deque<DialogueTurn> history = new ArrayDeque<>();
    private State state = State.IDLE;

    /** World tick of the previous outgoing player turn (or of GUI-open, before the first one) — the
     * baseline {@link #ticksSinceLastTurn()} measures from. In-game ticks, not real time, but the two
     * move 1:1 at normal game speed, which is exactly the "pause in an ongoing exchange" this measures. */
    private long lastTurnWorldTick;

    public DialogueSession(UUID targetEntity, DialogueContext contextSnapshot) {
        this.targetEntity = targetEntity;
        this.contextSnapshot = contextSnapshot;
        this.lastTurnWorldTick = currentWorldTick();
    }

    /** Elapsed ticks since the previous {@link #markTurnSent()} (or since this session was opened, for the first turn). */
    public long ticksSinceLastTurn() {
        return currentWorldTick() - lastTurnWorldTick;
    }

    /** Resets the {@link #ticksSinceLastTurn()} baseline to now — call once per outgoing player turn. */
    public void markTurnSent() {
        lastTurnWorldTick = currentWorldTick();
    }

    private static long currentWorldTick() {
        net.minecraft.world.World w = net.minecraft.client.Minecraft.getMinecraft().world;
        return w != null ? w.getTotalWorldTime() : 0L;
    }

    public void addTurn(DialogueTurn turn) {
        history.addLast(turn);
        while (history.size() > MAX_HISTORY_TURNS) {
            history.removeFirst();
        }
    }

    public List<DialogueTurn> historySnapshot() {
        return new ArrayList<>(history);
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}
