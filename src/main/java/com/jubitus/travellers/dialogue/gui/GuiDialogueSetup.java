package com.jubitus.travellers.dialogue.gui;

import com.jubitus.travellers.dialogue.client.DialogueClientSettings;
import com.jubitus.travellers.dialogue.client.DialogueClientSettings.ContextDetailLevel;
import com.jubitus.travellers.dialogue.client.OllamaClient;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lets a player point the dialogue feature at their own local Ollama instance: host/port, a
 * "Test connection" round trip that lists locally pulled models, and a Save that commits through
 * {@link DialogueClientSettings} (client-local, never synced — see that class's doc).
 *
 * <p>Deliberately not tied to any entity/container — it is opened standalone (a future settings
 * entry point) and also shown as a fallback screen from inside the live dialogue GUI when the
 * player hasn't configured anything yet (see the dialogue plan's D-9). The optional {@code parent}
 * screen is what "Back" returns to in either case.
 */
public class GuiDialogueSetup extends GuiScreen implements ITravellersGui {

    private static final int ID_TOGGLE_ENABLED = 1;
    private static final int ID_TEST = 2;
    private static final int ID_SAVE = 3;
    private static final int ID_BACK = 4;
    private static final int ID_DETAIL_LEVEL = 5;
    private static final int ID_DEBUG_DUMP = 6;
    private static final int ID_ADVANCED = 7;
    private static final int ID_PRELOAD = 8;
    private static final int ID_MODEL_BASE = 100;
    private static final int MAX_MODELS_SHOWN = 8;

    @Nullable
    private final GuiScreen parent;

    private GuiTextField hostField;
    private GuiTextField portField;

    private GuiTravellersButton enabledBtn;
    private GuiTravellersButton testBtn;
    private GuiTravellersButton detailLevelBtn;
    private GuiTravellersButton debugDumpBtn;
    private GuiTravellersButton preloadBtn;
    private final List<GuiTravellersButton> modelButtons = new ArrayList<>();

    private boolean enabledLocal;
    private ContextDetailLevel detailLevelLocal;
    private String selectedModel;
    private List<String> discoveredModels = new ArrayList<>();

    private boolean testing = false;
    private String statusText = "";
    private int statusColor = TravellersGuiTheme.COL_TEXT_DIM;

    private int panelX, panelY, panelW, panelH;
    /** Y where the (possibly still-empty) model button list starts; fixed at layout time. */
    private int modelAreaY;

    public GuiDialogueSetup(@Nullable GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        enabledLocal = DialogueClientSettings.isEnabled();
        detailLevelLocal = DialogueClientSettings.getContextDetailLevel();
        selectedModel = DialogueClientSettings.getModel();
        // Seed the list with the already-saved model (if any) so the screen isn't empty before the
        // player ever hits Test - they can Save immediately if nothing changed.
        discoveredModels = new ArrayList<>();
        if (selectedModel != null && !selectedModel.isEmpty()) {
            discoveredModels.add(selectedModel);
        }

        panelW = 220;
        // Content height is fully derived from the same step sizes used to place every element
        // below, reserving room for the worst case (MAX_MODELS_SHOWN buttons) up front - so the
        // panel never needs to resize once the model list actually populates after a Test. Fixed
        // offsets from a corner are exactly what caused a real overlap bug elsewhere in this mod
        // (see GuiPlayerZonePrompt's class doc); this mirrors its "cursor, never a fixed Y" rule.
        int headerH = 22;
        int hostRowH = 18;
        int portRowH = 20;
        int enabledRowH = 20;
        int preloadRowH = 16 + 12; // button + help line room
        int detailLevelRowH = 16 + 12; // button + help line room
        int debugDumpRowH = 16 + 12; // button + help line room
        int advancedRowH = 20;
        int testRowH = 16 + 16; // button + status line room
        int modelAreaH = MAX_MODELS_SHOWN * 16;
        int gapBeforeBottom = 10;
        int bottomRowH = 16;
        int bottomMargin = 8;
        panelH = headerH + hostRowH + portRowH + enabledRowH + preloadRowH + detailLevelRowH + debugDumpRowH
                + advancedRowH + testRowH + modelAreaH + gapBeforeBottom + bottomRowH + bottomMargin;

        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        this.buttonList.clear();
        int bx = panelX + 8;
        int bw = panelW - 16;
        int cursor = panelY + headerH;

        hostField = new GuiTextField(0, this.fontRenderer, bx, cursor, bw - 60, 14);
        hostField.setMaxStringLength(64);
        hostField.setText(DialogueClientSettings.getHost());
        cursor += hostRowH;

        portField = new GuiTextField(1, this.fontRenderer, bx, cursor, 60, 14);
        portField.setMaxStringLength(5);
        portField.setText(String.valueOf(DialogueClientSettings.getPort()));
        cursor += portRowH;

        enabledBtn = addTravellersButton(ID_TOGGLE_ENABLED, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.enabled"))
                .style(GuiTravellersButton.Style.TOGGLE)
                .toggled(enabledLocal);
        cursor += enabledRowH;

        preloadBtn = addTravellersButton(ID_PRELOAD, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.preload"))
                .style(GuiTravellersButton.Style.TOGGLE)
                .toggled(DialogueClientSettings.isPreloadOnStartup());
        cursor += preloadRowH;

        detailLevelBtn = addTravellersButton(ID_DETAIL_LEVEL, bx, cursor, bw, 16, detailLevelLabel());
        cursor += detailLevelRowH;

        debugDumpBtn = addTravellersButton(ID_DEBUG_DUMP, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.debug_dump"))
                .style(GuiTravellersButton.Style.TOGGLE)
                .toggled(DialogueClientSettings.isDebugDumpEnabled());
        cursor += debugDumpRowH;

        addTravellersButton(ID_ADVANCED, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.advanced"))
                .style(GuiTravellersButton.Style.SCREEN);
        cursor += advancedRowH;

        testBtn = addTravellersButton(ID_TEST, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.test_connection"));
        cursor += testRowH;

        modelAreaY = cursor;
        rebuildModelButtons(bx, modelAreaY, bw);

        int bottom = panelY + panelH - bottomMargin - bottomRowH;
        addTravellersButton(ID_SAVE, bx, bottom, (bw - 6) / 2, 16, I18n.format("travellers.gui.save"))
                .style(GuiTravellersButton.Style.SCREEN);
        addTravellersButton(ID_BACK, bx + (bw - 6) / 2 + 6, bottom, (bw - 6) / 2, 16, I18n.format("travellers.gui.cancel"));
    }

    private GuiTravellersButton addTravellersButton(int id, int x, int y, int w, int h, String text) {
        GuiTravellersButton btn = new GuiTravellersButton(id, x, y, w, h, text);
        this.buttonList.add(btn);
        return btn;
    }

    /**
     * (Re)lays out one small button per known model, inside the area reserved for up to
     * {@link #MAX_MODELS_SHOWN} of them at {@link #modelAreaY} — the reserved area is fixed at
     * layout time (see {@link #initGui}), so calling this again after a Test never resizes the panel.
     */
    private void rebuildModelButtons(int bx, int startY, int bw) {
        this.buttonList.removeAll(modelButtons);
        modelButtons.clear();

        int cursor = startY;
        int shown = Math.min(discoveredModels.size(), MAX_MODELS_SHOWN);
        for (int i = 0; i < shown; i++) {
            String name = discoveredModels.get(i);
            GuiTravellersButton btn = addTravellersButton(ID_MODEL_BASE + i, bx, cursor, bw, 14, name)
                    .style(GuiTravellersButton.Style.TOGGLE)
                    .toggled(name.equals(selectedModel));
            modelButtons.add(btn);
            cursor += 16;
        }
    }

    @Override
    protected void actionPerformed(net.minecraft.client.gui.GuiButton button) {
        if (button.id == ID_TOGGLE_ENABLED) {
            enabledLocal = !enabledLocal;
            enabledBtn.toggled(enabledLocal);
            return;
        }
        if (button.id == ID_PRELOAD) {
            boolean newValue = !DialogueClientSettings.isPreloadOnStartup();
            DialogueClientSettings.setPreloadOnStartup(newValue);
            preloadBtn.toggled(newValue);
            return;
        }
        if (button.id == ID_DETAIL_LEVEL) {
            detailLevelLocal = detailLevelLocal.next();
            detailLevelBtn.displayString = detailLevelLabel();
            return;
        }
        if (button.id == ID_DEBUG_DUMP) {
            boolean newValue = !DialogueClientSettings.isDebugDumpEnabled();
            DialogueClientSettings.setDebugDumpPrompt(newValue);
            debugDumpBtn.toggled(newValue);
            return;
        }
        if (button.id == ID_ADVANCED) {
            this.mc.displayGuiScreen(new GuiDialogueAdvanced(this));
            return;
        }
        if (button.id == ID_TEST) {
            startTest();
            return;
        }
        if (button.id == ID_SAVE) {
            int port = parsePort(portField.getText());
            DialogueClientSettings.save(hostField.getText(), port, selectedModel, enabledLocal, detailLevelLocal);
            goBack();
            return;
        }
        if (button.id == ID_BACK) {
            goBack();
            return;
        }
        if (button.id >= ID_MODEL_BASE && button.id < ID_MODEL_BASE + modelButtons.size()) {
            selectedModel = discoveredModels.get(button.id - ID_MODEL_BASE);
            for (int i = 0; i < modelButtons.size(); i++) {
                modelButtons.get(i).toggled(discoveredModels.get(i).equals(selectedModel));
            }
        }
    }

    private void startTest() {
        if (testing) return;
        int port = parsePort(portField.getText());
        String host = hostField.getText();
        testing = true;
        statusText = I18n.format("travellers.dialogue.setup.testing");
        statusColor = TravellersGuiTheme.COL_TEXT_DIM;

        // OllamaClient.TagsCallback has two abstract methods, so this can't be a lambda.
        OllamaClient.listModels(host, port, new OllamaClient.TagsCallback() {
            @Override
            public void onModels(List<String> modelNames) {
                testing = false;
                discoveredModels = modelNames;
                if (modelNames.isEmpty()) {
                    statusText = I18n.format("travellers.dialogue.setup.test_no_models");
                    statusColor = TravellersGuiTheme.COL_CANNOT;
                } else {
                    statusText = I18n.format("travellers.dialogue.setup.test_ok", modelNames.size());
                    statusColor = TravellersGuiTheme.COL_AFFORD;
                    if (selectedModel == null || !modelNames.contains(selectedModel)) {
                        selectedModel = modelNames.get(0);
                    }
                }
                rebuildModelButtons(panelX + 8, modelAreaY, panelW - 16);
            }

            @Override
            public void onError(OllamaClient.Outcome outcome, String message) {
                testing = false;
                statusText = I18n.format("travellers.dialogue.setup.test_failed", message);
                statusColor = TravellersGuiTheme.COL_CANNOT;
            }
        });
    }

    private void goBack() {
        this.mc.displayGuiScreen(parent);
    }

    private String detailLevelLabel() {
        String levelName;
        switch (detailLevelLocal) {
            case MINIMAL:
                levelName = I18n.format("travellers.dialogue.setup.detail_level.minimal");
                break;
            case RICH:
                levelName = I18n.format("travellers.dialogue.setup.detail_level.rich");
                break;
            case STANDARD:
            default:
                levelName = I18n.format("travellers.dialogue.setup.detail_level.standard");
                break;
        }
        return I18n.format("travellers.dialogue.setup.detail_level", levelName);
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return 11434;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        hostField.mouseClicked(mouseX, mouseY, mouseButton);
        portField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            goBack();
            return;
        }
        if (hostField.textboxKeyTyped(typedChar, keyCode)) return;
        if (portField.textboxKeyTyped(typedChar, keyCode)) return;
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        hostField.updateCursorCounter();
        portField.updateCursorCounter();
        testBtn.enabled = !testing;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        TravellersGuiTheme.drawPanel(panelX, panelY, panelW, panelH);
        TravellersGuiTheme.drawTitle(this.fontRenderer,
                I18n.format("travellers.dialogue.setup.title"), panelX + 8, panelY + 8);

        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.host"),
                hostField.x, hostField.y - 10, TravellersGuiTheme.COL_TEXT_DIM);
        hostField.drawTextBox();
        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.port"),
                portField.x, portField.y - 10, TravellersGuiTheme.COL_TEXT_DIM);
        portField.drawTextBox();

        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.preload_help"),
                preloadBtn.x, preloadBtn.y + preloadBtn.height + 2, TravellersGuiTheme.COL_TEXT_DIM);

        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.detail_level_help"),
                detailLevelBtn.x, detailLevelBtn.y + detailLevelBtn.height + 2, TravellersGuiTheme.COL_TEXT_DIM);

        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.debug_dump_help"),
                debugDumpBtn.x, debugDumpBtn.y + debugDumpBtn.height + 2, TravellersGuiTheme.COL_TEXT_DIM);

        if (!statusText.isEmpty()) {
            this.fontRenderer.drawString(statusText, panelX + 8, testBtn.y + testBtn.height + 4, statusColor);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
