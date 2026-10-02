package com.jubitus.travellers.dialogue.gui;

import com.jubitus.travellers.dialogue.client.DialogueClientSettings;
import com.jubitus.travellers.dialogue.client.DialogueFallbackText;
import com.jubitus.travellers.dialogue.client.DialoguePromptBuilder;
import com.jubitus.travellers.dialogue.client.DialoguePromptDebugDumper;
import com.jubitus.travellers.dialogue.client.DialogueSession;
import com.jubitus.travellers.dialogue.client.DialogueTurn;
import com.jubitus.travellers.dialogue.client.OllamaClient;
import com.jubitus.travellers.dialogue.client.ToolCallOrchestrator;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import com.jubitus.travellers.gui.hub.WorkerPortraitRenderer;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.dialogue.DialogueContext;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.opengl.GL11;

/**
 * The real dialogue GUI: live portrait, scrollable transcript, free-text input, streamed reply.
 * See the dialogue plan's Task C3 / D-9 for the exact fallback contract this implements.
 *
 * <p>Two rendering states share one layout rectangle (see {@link #initGui}): a ready-to-type
 * transcript + input row when {@link DialogueClientSettings#isConfigured()}, or — for that same
 * "not configured" case, and identically for any {@link OllamaClient} failure mid-conversation
 * (unreachable / timeout / mid-stream drop) — the canned fallback line plus a button into {@link
 * GuiDialogueSetup}. Never a raw error, never a frozen "generating..." state.
 */
public class GuiTravellerDialogue extends GuiScreen implements ITravellersGui {

    private static final int ID_SEND = 1;
    private static final int ID_CONFIGURE = 2;

    private final EntityPlayer player;
    @Nullable
    private final EntityTraveller traveller;
    private final DialogueContext context;
    private final DialogueSession session;

    private GuiTextField inputField;
    private GuiTravellersButton sendBtn;
    private GuiTravellersButton configureBtn;

    /** Set once at open (not configured) or the moment an OllamaClient call fails - see the class doc. */
    private boolean showFallback;
    private final String fallbackText;

    /** Streaming buffer for the reply currently being generated; folded into the session once done. */
    private String pendingNpcReply = "";
    @Nullable
    private ToolCallOrchestrator.Handle activeChat;

    /** Null unless {@link DialogueClientSettings#isDebugDumpEnabled()} — see that class's doc. */
    @Nullable
    private final DialoguePromptDebugDumper debugDumper;

    private static final int BAR_WIDTH = 2;
    private static final int TEXT_PAD = 5;
    private static final int SCROLLBAR_W = 3;
    private static final int SCROLLBAR_GAP = 4;
    /** Extra buffer beyond the scrollbar gutter — French accented glyphs (é, è, à...) commonly render
     * a hair wider than the font's own reported advance width, which otherwise reads as text crowding
     * or clipping into the scrollbar on almost every line. */
    private static final int RIGHT_TEXT_MARGIN = 6;
    private static final int LINE_GAP = 1;
    private static final int BLOCK_GAP = 6;
    private static final int SCROLL_STEP_LINES = 3;
    /** Per-frame easing factor for scroll motion (wheel steps, streaming auto-follow) - higher is
     * snappier. Dragging the scrollbar bypasses this entirely for zero-lag tracking. */
    private static final float SCROLL_EASE = 0.35f;
    private static final int NAME_LABEL_GAP = 4;
    /** The transcript box is a fixed, generous reading size, never sized off how long the
     * conversation currently is - a short/empty conversation opening the GUI at a barely-readable
     * minimum height was exactly the "bêtise" this replaces. Overflow is handled by the scrollbar. */
    private static final int PREFERRED_TRANSCRIPT_H = 220;

    private int panelX, panelY, panelW, panelH;
    private int portraitSize;
    private int transcriptX, transcriptY, transcriptW, transcriptH;

    /** Absolute scroll position from the top of the transcript content, in pixels. Eased toward
     * {@link #scrollTargetTop} every frame ({@link #tickScrollEasing()}) for smooth motion. */
    private float scrollCurrentTop;
    private float scrollTargetTop;
    /** True while nothing has pulled the view away from the latest message - the view stays
     * pinned to the bottom as new content streams in. Cleared the moment the player scrolls or
     * drags away from the bottom; restored the moment they scroll/drag back to it. */
    private boolean autoFollowBottom = true;
    private int contentHeight;

    private boolean draggingScrollbar;
    /** Vertical offset, in pixels, between the mouse and the thumb's top edge at drag start - keeps
     * the thumb from jumping under the cursor on the first drag frame. */
    private int scrollbarDragOffset;

    private final List<LayoutLine> layoutLines = new ArrayList<>();
    private final List<LayoutBar> layoutBars = new ArrayList<>();

    /** One wrapped line of transcript text, positioned at content-relative y (0 = top of the whole transcript). */
    private static final class LayoutLine {
        final int y;
        final String text;
        final int color;

        LayoutLine(int y, String text, int color) {
            this.y = y;
            this.text = text;
            this.color = color;
        }
    }

    /** The accent bar spanning one whole message block (its label line + all wrapped body lines). */
    private static final class LayoutBar {
        final int yTop, yBottom, color;

        LayoutBar(int yTop, int yBottom, int color) {
            this.yTop = yTop;
            this.yBottom = yBottom;
            this.color = color;
        }
    }

    /** Set right before navigating to {@link GuiDialogueSetup} so {@link #onGuiClosed()} can tell that
     * apart from the conversation actually ending (this screen instance is reused - onGuiClosed fires
     * on every such navigation, not just a real close - see the Worker Hub GUI's GuiOpenEvent trap in
     * CLAUDE.md for the same class of lifecycle-hook pitfall). Reset in {@link #initGui()} once back. */
    private boolean navigatingToSubScreen = false;

    public GuiTravellerDialogue(EntityPlayer player, @Nullable EntityTraveller traveller, DialogueContext context) {
        this.player = player;
        this.traveller = traveller;
        this.context = context;
        this.session = new DialogueSession(traveller != null ? traveller.getUniqueID() : null, context);
        this.fallbackText = DialogueFallbackText.forContext(context);
        this.debugDumper = DialoguePromptDebugDumper.openIfEnabled(
                context.travellerDisplayName, traveller != null ? traveller.getUniqueID() : null, context);
        if (debugDumper != null && player != null) {
            player.sendMessage(new net.minecraft.util.text.TextComponentString(
                    net.minecraft.util.text.TextFormatting.GRAY + "[Travellers] Dialogue debug dump: "
                            + debugDumper.getFile().getPath()));
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        // Re-derived every time this screen is (re)displayed, not just once at construction - this
        // is what lets a player fix their settings in GuiDialogueSetup and land back here ready to
        // type, instead of stuck showing the fallback forever (Minecraft re-runs initGui() on the
        // same screen instance every displayGuiScreen call, including "Back" from that sub-screen).
        if (DialogueClientSettings.isConfigured()) {
            showFallback = false;
            session.setState(DialogueSession.State.IDLE);
        } else {
            showFallback = true;
        }
        navigatingToSubScreen = false;

        panelW = 360;
        portraitSize = 64;
        panelX = (this.width - panelW) / 2;

        int contentX = panelX + 8;
        transcriptX = contentX + portraitSize + 8;
        transcriptW = panelX + panelW - 8 - transcriptX;

        // Fixed reading-area height (see PREFERRED_TRANSCRIPT_H), clamped to fit within the screen
        // vertically on very small windows - never spills past its top/bottom edges.
        int fixedChrome = 52; // header(22) + transcript-to-input gap(6) + input row height(16) + bottom margin(8)
        int minTranscriptH = Math.max(portraitSize + NAME_LABEL_GAP + lineHeight(), lineHeight() * 3);

        int screenMargin = 20;
        int maxAllowedByScreen = Math.max(60, this.height - 2 * screenMargin) - fixedChrome;
        int desiredTranscriptH = clamp(PREFERRED_TRANSCRIPT_H, minTranscriptH, Math.max(minTranscriptH, maxAllowedByScreen));
        panelH = desiredTranscriptH + fixedChrome;

        panelY = (this.height - panelH) / 2;

        int contentY = panelY + 22;
        transcriptY = contentY;

        int inputRowY = panelY + panelH - 8 - 16;
        transcriptH = inputRowY - 6 - transcriptY;

        this.buttonList.clear();

        int sendW = 50;
        inputField = new GuiTextField(0, this.fontRenderer, contentX, inputRowY, panelW - 16 - sendW - 4, 16);
        inputField.setMaxStringLength(240);
        inputField.setFocused(true);

        sendBtn = new GuiTravellersButton(ID_SEND, contentX + panelW - 16 - sendW, inputRowY, sendW, 16,
                I18n.format("travellers.dialogue.gui.send"));
        this.buttonList.add(sendBtn);

        configureBtn = new GuiTravellersButton(ID_CONFIGURE, transcriptX, transcriptY + transcriptH / 2 - 8,
                transcriptW, 16, I18n.format("travellers.dialogue.gui.configure")).style(GuiTravellersButton.Style.SCREEN);
        this.buttonList.add(configureBtn);

        rebuildTranscript();
        updateVisibility();
    }

    private void updateVisibility() {
        inputField.setVisible(!showFallback);
        sendBtn.visible = !showFallback;
        configureBtn.visible = showFallback;
    }

    private void enterFallback() {
        showFallback = true;
        if (activeChat != null) {
            activeChat.cancel();
            activeChat = null;
        }
        session.setState(DialogueSession.State.FALLBACK);
        updateVisibility();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == ID_SEND) {
            trySend();
        } else if (button.id == ID_CONFIGURE) {
            navigatingToSubScreen = true;
            this.mc.displayGuiScreen(new GuiDialogueSetup(this));
        }
    }

    private void trySend() {
        if (showFallback || session.getState() != DialogueSession.State.IDLE) return;
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        inputField.setText("");
        // Read before marking - both this turn's debug-dump preview and the real send below (which
        // rebuilds the same prompt internally) must see the identical elapsed value, so it's captured
        // once here rather than recomputed twice against a baseline the first read would have moved.
        long elapsedTicks = session.ticksSinceLastTurn();
        session.markTurnSent();
        session.addTurn(new DialogueTurn(DialogueTurn.Role.PLAYER, text));
        pendingNpcReply = "";
        session.setState(DialogueSession.State.GENERATING);
        sendBtn.enabled = false;
        rebuildTranscript();

        if (debugDumper != null) {
            List<OllamaClient.ChatMessage> messages =
                    DialoguePromptBuilder.build(context, session.historySnapshot(), elapsedTicks);
            debugDumper.appendPromptSent(DialogueClientSettings.getContextDetailLevel(), messages,
                    com.jubitus.travellers.dialogue.client.DialogueToolCatalog.currentTools(context));
        }
        activeChat = ToolCallOrchestrator.send(context, session,
                traveller != null ? traveller.getEntityId() : -1, elapsedTicks, new ToolCallOrchestrator.Callback() {
                    @Override
                    public void onToken(String token) {
                        pendingNpcReply += token;
                        rebuildTranscript();
                    }

                    @Override
                    public void onDone() {
                        activeChat = null;
                        session.addTurn(new DialogueTurn(DialogueTurn.Role.NPC, pendingNpcReply));
                        pendingNpcReply = "";
                        session.setState(DialogueSession.State.IDLE);
                        sendBtn.enabled = true;
                        rebuildTranscript();
                    }

                    @Override
                    public void onError(OllamaClient.Outcome outcome, String message) {
                        activeChat = null;
                        enterFallback();
                    }
                }, debugDumper);
    }

    private int lineHeight() {
        return this.fontRenderer.FONT_HEIGHT + LINE_GAP;
    }

    /**
     * Rebuilds the transcript layout (one accent-barred block per turn) from the session history plus
     * any in-flight reply. Stays pinned to the bottom for new content only if the player hadn't scrolled
     * away from it ({@link #autoFollowBottom}) - otherwise a scroll made mid-generation would get
     * yanked back on every streamed token. When not pinned, the scroll target is an absolute position
     * from the top of the content, so it never drifts as new content is appended below the current
     * view - only the max clamp moves, the anchor itself doesn't.
     */
    private void rebuildTranscript() {
        layoutLines.clear();
        layoutBars.clear();
        if (this.fontRenderer == null || transcriptW <= 0) return;

        int lineH = lineHeight();
        int wrapWidth = Math.max(1, transcriptW - BAR_WIDTH - TEXT_PAD - SCROLLBAR_W - SCROLLBAR_GAP - RIGHT_TEXT_MARGIN);
        int y = 0;

        for (DialogueTurn turn : session.historySnapshot()) {
            boolean isPlayer = turn.role == DialogueTurn.Role.PLAYER;
            String label = isPlayer ? I18n.format("travellers.dialogue.gui.you") : displayName();
            int accent = isPlayer ? TravellersGuiTheme.COL_DIALOGUE_PLAYER : TravellersGuiTheme.COL_DIALOGUE_NPC;
            y = appendBlock(label, turn.text, accent, wrapWidth, lineH, y);
        }
        if (session.getState() == DialogueSession.State.GENERATING
                || session.getState() == DialogueSession.State.AWAITING_TOOL) {
            String placeholderKey = session.getState() == DialogueSession.State.AWAITING_TOOL
                    ? "travellers.dialogue.gui.looking_up" : "travellers.dialogue.gui.generating";
            String body = pendingNpcReply.isEmpty() ? I18n.format(placeholderKey) : pendingNpcReply;
            y = appendBlock(displayName(), body, TravellersGuiTheme.COL_DIALOGUE_NPC, wrapWidth, lineH, y);
        }
        contentHeight = Math.max(0, y - BLOCK_GAP);

        int maxScroll = Math.max(0, contentHeight - transcriptH);
        if (autoFollowBottom) {
            scrollTargetTop = maxScroll;
        } else {
            scrollTargetTop = clampF(scrollTargetTop, 0, maxScroll);
        }
        scrollCurrentTop = clampF(scrollCurrentTop, 0, maxScroll);
    }

    /** Eases {@link #scrollCurrentTop} toward {@link #scrollTargetTop} - called once per drawn
     * frame so wheel steps and the auto-follow catch-up glide instead of snapping. */
    private void tickScrollEasing() {
        float diff = scrollTargetTop - scrollCurrentTop;
        if (Math.abs(diff) < 0.5f) {
            scrollCurrentTop = scrollTargetTop;
        } else {
            scrollCurrentTop += diff * SCROLL_EASE;
        }
    }

    /** Appends one message block (label line + wrapped body) starting at content-y {@code y}, returns the next y. */
    private int appendBlock(String label, String body, int accent, int wrapWidth, int lineH, int y) {
        int blockTop = y;
        layoutLines.add(new LayoutLine(y, label, accent));
        y += lineH;
        for (String bodyLine : this.fontRenderer.listFormattedStringToWidth(body, wrapWidth)) {
            layoutLines.add(new LayoutLine(y, bodyLine, TravellersGuiTheme.COL_TEXT));
            y += lineH;
        }
        layoutBars.add(new LayoutBar(blockTop, y, accent));
        return y + BLOCK_GAP;
    }

    private String displayName() {
        return context.travellerDisplayName == null || context.travellerDisplayName.isEmpty()
                ? "..." : context.travellerDisplayName;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (!showFallback) {
            inputField.mouseClicked(mouseX, mouseY, mouseButton);
            if (mouseButton == 0) tryStartScrollbarDrag(mouseX, mouseY);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (!draggingScrollbar || clickedMouseButton != 0) return;
        ScrollbarGeometry g = computeScrollbarGeometry();
        if (g != null) dragScrollTo(mouseY, g);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0) draggingScrollbar = false;
    }

    /** Starts dragging the scrollbar if the click landed on its track. A click on the bare track
     * (not directly on the thumb) recenters the thumb under the cursor immediately, the standard
     * "page here" scrollbar behaviour, instead of requiring a precise grab on the thumb itself. */
    private void tryStartScrollbarDrag(int mouseX, int mouseY) {
        ScrollbarGeometry g = computeScrollbarGeometry();
        if (g == null) return;
        if (mouseX < g.trackX - 2 || mouseX > g.trackX + SCROLLBAR_W + 2
                || mouseY < g.trackY || mouseY > g.trackY + g.trackH) {
            return;
        }
        draggingScrollbar = true;
        scrollbarDragOffset = (mouseY >= g.thumbY && mouseY <= g.thumbY + g.thumbH)
                ? mouseY - g.thumbY : g.thumbH / 2;
        dragScrollTo(mouseY, g);
    }

    /** Drives the transcript's scroll position directly off the current drag position - no easing,
     * so the thumb (and the text behind it) track the mouse 1:1 while held. */
    private void dragScrollTo(int mouseY, ScrollbarGeometry g) {
        int trackSpan = g.trackH - g.thumbH;
        float proportion = trackSpan <= 0
                ? 0f : clampF((mouseY - scrollbarDragOffset - g.trackY) / (float) trackSpan, 0f, 1f);
        scrollTargetTop = proportion * g.maxScroll;
        scrollCurrentTop = scrollTargetTop;
        autoFollowBottom = scrollTargetTop >= g.maxScroll - 0.5f;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0 || showFallback) return;
        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        if (mouseX < transcriptX || mouseX > transcriptX + transcriptW
                || mouseY < transcriptY || mouseY > transcriptY + transcriptH) return;

        // scrollTargetTop measures distance from the top, so scrolling up (wheel > 0) to reveal
        // older messages must decrease it. Only the target moves here - tickScrollEasing() glides
        // scrollCurrentTop toward it every drawn frame, which is what actually makes this smooth.
        int maxScroll = Math.max(0, contentHeight - transcriptH);
        int step = lineHeight() * SCROLL_STEP_LINES;
        scrollTargetTop = clampF(scrollTargetTop - Integer.signum(wheel) * step, 0, maxScroll);
        autoFollowBottom = scrollTargetTop >= maxScroll;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    private static float clampF(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            this.mc.player.closeScreen();
            return;
        }
        if (!showFallback) {
            if ((keyCode == org.lwjgl.input.Keyboard.KEY_RETURN || keyCode == org.lwjgl.input.Keyboard.KEY_NUMPADENTER)
                    && sendBtn.enabled) {
                trySend();
                return;
            }
            if (inputField.textboxKeyTyped(typedChar, keyCode)) return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!showFallback) inputField.updateCursorCounter();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // Detach, don't cancel: closing this screen mid-reply used to abort the Ollama call outright,
        // which aborts a still-loading model too - the next question then pays the full cold-load
        // cost again instead of hitting an already-warm model. Detaching lets it finish in the
        // background (including any pending tool calls); the reply itself is simply discarded, since
        // this GUI instance and its session are gone either way (maintainer's own call - see the
        // per-player memory system's dialogue plan doc for the same "no GUI left to report to" pattern
        // already used by extractMemoryOnClose below).
        if (activeChat != null) {
            activeChat.detach();
            activeChat = null;
        }
        if (!navigatingToSubScreen) {
            extractMemoryOnClose();
        }
    }

    /**
     * One extra, non-visible Ollama call fired when the conversation actually ends, distilling it into
     * a short global fact list (identity-memory plan, Part M - facts now name the interacting player by
     * name, since memory is global and "Player said..." is meaningless to a second player later) plus,
     * when enabled, a rewritten rolling life summary (M-3) - both from the same call, never a second
     * round trip. Runs regardless of {@link DialogueClientSettings#isToolsEnabled()}, gated only on
     * {@link DialogueClientSettings#isConfigured()} (needs Ollama at all) and the server-authoritative
     * {@link DialogueContext#memoryEnabled}. No GUI exists any more by the time this resolves, so
     * failures are logged and dropped, never surfaced, never retried.
     */
    private void extractMemoryOnClose() {
        if (!context.memoryEnabled || !DialogueClientSettings.isConfigured() || traveller == null) return;
        List<DialogueTurn> history = session.historySnapshot();
        boolean hadRealExchange = false;
        for (DialogueTurn turn : history) {
            if (turn.role == DialogueTurn.Role.NPC && !turn.text.isEmpty()) {
                hadRealExchange = true;
                break;
            }
        }
        if (!hadRealExchange) return;

        int maxFacts = Math.max(1, com.jubitus.travellers.config.TravellersConfig.DIALOGUE.memoryFactsPerSession);
        boolean summaryEnabled = com.jubitus.travellers.config.TravellersConfig.DIALOGUE.memorySummaryEnabled;
        String playerName = player.getName();
        String currentSummary = context.lifeSummaryPhrase == null ? "" : context.lifeSummaryPhrase;

        // Deliberately only the player's own turns, never the NPC's - a weak local model kept
        // attributing the traveller's own in-character lines (self-description, a comedic-wrong-on-
        // purpose personality's own claims...) to the player despite an explicit instruction telling it
        // not to (confirmed twice against real transcripts). Making the misattribution structurally
        // impossible - there is nothing else in the input to misattribute - beats relying on a small
        // model to correctly apply a role-based rule.
        StringBuilder transcript = new StringBuilder();
        for (DialogueTurn turn : history) {
            if (turn.role == DialogueTurn.Role.PLAYER && !turn.text.isEmpty()) {
                transcript.append(turn.text).append('\n');
            }
        }
        if (transcript.length() == 0) return;

        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("Below are the exact messages a person named ").append(playerName)
                .append(" sent you during a conversation you just had, one per line, in the order they "
                        + "were said - nothing you said back is included, only their own words. Extract "
                        + "up to ").append(maxFacts).append(" short, concrete fact(s) about ").append(playerName)
                .append(" from what they actually wrote, one per line, written in the third person using "
                        + "their real name (for example: \"").append(playerName)
                .append(" said they are looking for iron\") - never the generic word \"player\", since "
                        + "other people may read this later. If there is nothing worth remembering in "
                        + "their messages, extract nothing. Keep each fact general rather than exact "
                        + "numbers or coordinates.");
        if (summaryEnabled) {
            systemPrompt.append(" Here is what you currently remember about your life so far: \"")
                    .append(currentSummary.isEmpty() ? "(nothing yet)" : currentSummary)
                    .append("\". Considering what they said below, also write an updated version of it - a "
                            + "short paragraph, still capturing everything important from before unless it's no "
                            + "longer relevant.");
        }
        systemPrompt.append(" Reply using exactly this format: a line \"FACTS:\" followed by one fact per line "
                + "(leave it blank if there is nothing worth remembering)");
        if (summaryEnabled) {
            systemPrompt.append(", then a line \"SUMMARY:\" followed by the updated paragraph");
        }
        systemPrompt.append(". No other text.");

        List<OllamaClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new OllamaClient.ChatMessage("system", systemPrompt.toString()));
        messages.add(new OllamaClient.ChatMessage("user", transcript.toString()));

        int entityId = traveller.getEntityId();
        StringBuilder accumulated = new StringBuilder();
        OllamaClient.chat(DialogueClientSettings.getHost(), DialogueClientSettings.getPort(),
                DialogueClientSettings.getModel(), messages, DialogueClientSettings.currentChatOptions(),
                new OllamaClient.ChatCallback() {
                    @Override
                    public void onToken(String token) {
                        accumulated.append(token);
                    }

                    @Override
                    public void onDone() {
                        ParsedMemory parsed = parseFactsAndSummary(accumulated.toString(), maxFacts, summaryEnabled);
                        if (!parsed.facts.isEmpty() || !parsed.summary.isEmpty()) {
                            com.jubitus.travellers.net.TravellerFollowNet.CHANNEL.sendToServer(
                                    new com.jubitus.travellers.dialogue.net.MsgSubmitDialogueMemory(
                                            entityId, parsed.facts, parsed.summary));
                        }
                    }

                    @Override
                    public void onToolCall(List<OllamaClient.ToolCallRequest> calls) {
                        // Extraction never offers tools - nothing sensible to do if a model calls one anyway.
                    }

                    @Override
                    public void onError(OllamaClient.Outcome outcome, String message) {
                        // No GUI left to report to (this is the whole point) - fail silently, never retry.
                    }
                });
    }

    private static final class ParsedMemory {
        final List<String> facts;
        /** Empty means "no update" (marker missing / feature off) - never an explicit "clear it" signal,
         * so a malformed reply can never wipe a previously-accumulated summary. */
        final String summary;

        ParsedMemory(List<String> facts, String summary) {
            this.facts = facts;
            this.summary = summary;
        }
    }

    /**
     * Splits the model's reply on its "FACTS:"/"SUMMARY:" markers (case-insensitive). Simple and
     * robust by design (Task M3): a missing "SUMMARY:" marker - a malformed reply, or the feature
     * simply off - leaves {@link ParsedMemory#summary} empty, which the server-side handler treats as
     * "keep the old summary unchanged", never wiping previously-accumulated narrative.
     */
    private static ParsedMemory parseFactsAndSummary(String raw, int maxFacts, boolean summaryEnabled) {
        if (raw == null) return new ParsedMemory(new ArrayList<>(), "");

        String factsSection = raw;
        String summarySection = "";
        if (summaryEnabled) {
            int idx = indexOfMarker(raw, "SUMMARY:");
            if (idx >= 0) {
                factsSection = raw.substring(0, idx);
                summarySection = raw.substring(idx + "SUMMARY:".length()).trim();
            }
        }

        int factsIdx = indexOfMarker(factsSection, "FACTS:");
        if (factsIdx >= 0) {
            factsSection = factsSection.substring(factsIdx + "FACTS:".length());
        }
        return new ParsedMemory(parseFacts(factsSection, maxFacts), summarySection);
    }

    private static int indexOfMarker(String text, String marker) {
        return text.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
    }

    /** Strips common list markers a model might add despite being told not to ("- ", "1. "). */
    private static List<String> parseFacts(String raw, int maxFacts) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String trimmed = line.trim().replaceFirst("^[-*]\\s+", "").replaceFirst("^\\d+[.)]\\s+", "");
            if (!trimmed.isEmpty()) out.add(trimmed);
            if (out.size() >= maxFacts) break;
        }
        return out;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        TravellersGuiTheme.drawPanel(panelX, panelY, panelW, panelH);
        TravellersGuiTheme.drawTitle(this.fontRenderer, I18n.format("travellers.dialogue.gui.title"), panelX + 8, panelY + 8);

        int contentX = panelX + 8;
        int contentY = panelY + 22;
        WorkerPortraitRenderer.draw(traveller, contentX, contentY, portraitSize, mouseX, mouseY);
        String shownName = this.fontRenderer.trimStringToWidth(displayName(), portraitSize);
        TravellersGuiTheme.drawCenteredText(this.fontRenderer, shownName,
                contentX + portraitSize / 2, contentY + portraitSize + NAME_LABEL_GAP, TravellersGuiTheme.COL_TEXT);

        TravellersGuiTheme.drawInnerPanel(transcriptX - 2, transcriptY - 2, transcriptW + 4, transcriptH + 4);

        if (showFallback) {
            List<String> lines = this.fontRenderer.listFormattedStringToWidth(fallbackText, transcriptW);
            int ty = transcriptY + 4;
            for (String line : lines) {
                this.fontRenderer.drawString(line, transcriptX, ty, TravellersGuiTheme.COL_TEXT);
                ty += this.fontRenderer.FONT_HEIGHT + 1;
            }
            // Same canned line either way (D-9), but the note underneath it tells the player which
            // case they're in - "never set up" reads as misleading if a properly configured call
            // just failed mid-conversation.
            String note = DialogueClientSettings.isConfigured()
                    ? I18n.format("travellers.dialogue.gui.error_note")
                    : I18n.format("travellers.dialogue.gui.not_configured");
            this.fontRenderer.drawString(note, transcriptX, configureBtn.y - 12, TravellersGuiTheme.COL_TEXT_DIM);
        } else {
            drawTranscript();
            // GuiTextField is not a GuiButton - vanilla's buttonList loop below never draws it,
            // it has to be told explicitly, every frame, like every other text field in this mod.
            inputField.drawTextBox();
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawTranscript() {
        tickScrollEasing();
        beginClip(transcriptX, transcriptY, transcriptW, transcriptH);

        int viewTop = clamp(Math.round(scrollCurrentTop), 0, Math.max(0, contentHeight - transcriptH));
        int viewBottom = viewTop + transcriptH;
        int lineH = lineHeight();

        for (LayoutBar bar : layoutBars) {
            if (bar.yBottom <= viewTop || bar.yTop >= viewBottom) continue;
            int ry = transcriptY + (bar.yTop - viewTop);
            Gui.drawRect(transcriptX, ry, transcriptX + BAR_WIDTH, ry + (bar.yBottom - bar.yTop), bar.color);
        }

        int textX = transcriptX + BAR_WIDTH + TEXT_PAD;
        for (LayoutLine line : layoutLines) {
            if (line.y + lineH <= viewTop || line.y >= viewBottom) continue;
            this.fontRenderer.drawString(line.text, textX, transcriptY + (line.y - viewTop), line.color);
        }

        drawScrollbar();

        endClip();
    }

    /** The scrollbar track+thumb rectangle, shared by drawing and click/drag hit-testing so the two
     * can never disagree about where the thumb actually is. {@code null} when content fits without
     * scrolling (no thumb to draw or drag). Uses {@link #scrollCurrentTop} (the eased, visually-drawn
     * position), so a click always grabs the thumb exactly where the player sees it. */
    @Nullable
    private ScrollbarGeometry computeScrollbarGeometry() {
        int maxScroll = Math.max(0, contentHeight - transcriptH);
        if (maxScroll <= 0) return null;
        int thumbH = Math.min(transcriptH, Math.max(8, transcriptH * transcriptH / contentHeight));
        int trackSpan = transcriptH - thumbH;
        int thumbY = transcriptY + (trackSpan <= 0 ? 0 : Math.round(scrollCurrentTop * trackSpan / (float) maxScroll));
        return new ScrollbarGeometry(transcriptX + transcriptW - SCROLLBAR_W, transcriptY, transcriptH,
                thumbY, thumbH, maxScroll);
    }

    /** A slim scrollbar track+thumb along the transcript's right edge, only when content overflows it. */
    private void drawScrollbar() {
        ScrollbarGeometry g = computeScrollbarGeometry();
        if (g == null) return;

        Gui.drawRect(g.trackX, g.trackY, g.trackX + SCROLLBAR_W, g.trackY + g.trackH, TravellersGuiTheme.COL_PANEL);
        int thumbColor = draggingScrollbar ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM;
        Gui.drawRect(g.trackX, g.thumbY, g.trackX + SCROLLBAR_W, g.thumbY + g.thumbH, thumbColor);
    }

    /** Snapshot of the scrollbar's on-screen geometry for one frame. */
    private static final class ScrollbarGeometry {
        final int trackX, trackY, trackH, thumbY, thumbH, maxScroll;

        ScrollbarGeometry(int trackX, int trackY, int trackH, int thumbY, int thumbH, int maxScroll) {
            this.trackX = trackX;
            this.trackY = trackY;
            this.trackH = trackH;
            this.thumbY = thumbY;
            this.thumbH = thumbH;
            this.maxScroll = maxScroll;
        }
    }

    /** Clips to a GUI-space rectangle. Mirrors {@code WorkerPortraitRenderer.beginClip}. */
    private void beginClip(int x, int y, int w, int h) {
        ScaledResolution res = new ScaledResolution(this.mc);
        int f = res.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, (res.getScaledHeight() - (y + h)) * f, w * f, h * f);
    }

    private void endClip() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
