package com.jubitus.travellers.dialogue.gui;

import com.jubitus.travellers.dialogue.client.DialogueClientSettings;
import com.jubitus.travellers.dialogue.client.LlmClient.ChatOptions;
import com.jubitus.travellers.dialogue.client.LlmServerType;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.gui.ITravellersGui;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Player-facing tuning for the AI generation call itself (see {@code LlmClient.ChatOptions}):
 * extended thinking, context window size, and the usual sampling knobs. Reached from
 * {@link GuiDialogueSetup}, which stays the simple "connection" screen.
 *
 * <p>Every numeric field is a plain text box left blank by default — blank means "don't send this,
 * let Ollama/the model use its own default" ({@link DialogueClientSettings#UNSET_INT}/{@link
 * DialogueClientSettings#UNSET_DOUBLE}). Unparsable or blank text on Save is stored as that sentinel
 * rather than rejected, so there's no invalid state to get stuck in.
 */
public class GuiDialogueAdvanced extends GuiScreen implements ITravellersGui {

    private static final int ID_THINK = 1;
    private static final int ID_RESET = 2;
    private static final int ID_SAVE = 3;
    private static final int ID_BACK = 4;
    private static final int ID_TOOLS = 5;

    private final GuiScreen parent;
    /** The type picked on the setup screen, possibly not saved yet. */
    private final LlmServerType serverType;

    private GuiTravellersButton thinkBtn;
    private boolean thinkLocal;

    private GuiTravellersButton toolsBtn;
    private boolean toolsEnabledLocal;

    private GuiTextField numCtxField;
    private GuiTextField temperatureField;
    private GuiTextField topPField;
    private GuiTextField topKField;
    private GuiTextField repeatPenaltyField;
    private GuiTextField numPredictField;
    private final List<GuiTextField> allFields = new ArrayList<>();

    private int panelX, panelY, panelW, panelH;
    private int toolsWarningX, toolsWarningY;

    public GuiDialogueAdvanced(GuiScreen parent, LlmServerType serverType) {
        this.parent = parent;
        this.serverType = serverType;
    }

    @Override
    public void initGui() {
        super.initGui();
        org.lwjgl.input.Keyboard.enableRepeatEvents(true);

        thinkLocal = DialogueClientSettings.isThink();
        toolsEnabledLocal = DialogueClientSettings.isToolsEnabled();

        panelW = 220;
        int headerH = 22;
        int introH = 14;
        int thinkRowH = 20;
        int toolsRowH = 20;
        int toolsWarningH = 20;
        int fieldRowH = 26; // label + field + gap
        int fieldCount = 6;
        int gapBeforeBottom = 10;
        int resetRowH = 16;
        int bottomRowH = 16;
        int bottomMargin = 8;
        panelH = headerH + introH + thinkRowH + toolsRowH + toolsWarningH + fieldCount * fieldRowH
                + gapBeforeBottom + resetRowH + 6 + bottomRowH + bottomMargin;

        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        this.buttonList.clear();
        allFields.clear();
        int bx = panelX + 8;
        int bw = panelW - 16;
        int cursor = panelY + headerH + introH;

        thinkBtn = addTravellersButton(ID_THINK, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.advanced.think"))
                .style(GuiTravellersButton.Style.TOGGLE)
                .toggled(thinkLocal);
        cursor += thinkRowH;

        toolsBtn = addTravellersButton(ID_TOOLS, bx, cursor, bw, 16,
                I18n.format("travellers.dialogue.setup.advanced.tools"))
                .style(GuiTravellersButton.Style.TOGGLE)
                .toggled(toolsEnabledLocal);
        cursor += toolsRowH;
        toolsWarningX = bx;
        toolsWarningY = cursor;
        cursor += toolsWarningH;

        numCtxField = addNumberField(bx, cursor, bw, formatInt(DialogueClientSettings.getNumCtx()));
        numCtxField.setEnabled(serverType.supportsContextSize());
        cursor += fieldRowH;
        temperatureField = addNumberField(bx, cursor, bw, formatDouble(DialogueClientSettings.getTemperature()));
        cursor += fieldRowH;
        topPField = addNumberField(bx, cursor, bw, formatDouble(DialogueClientSettings.getTopP()));
        cursor += fieldRowH;
        topKField = addNumberField(bx, cursor, bw, formatInt(DialogueClientSettings.getTopK()));
        cursor += fieldRowH;
        repeatPenaltyField = addNumberField(bx, cursor, bw, formatDouble(DialogueClientSettings.getRepeatPenalty()));
        cursor += fieldRowH;
        numPredictField = addNumberField(bx, cursor, bw, formatInt(DialogueClientSettings.getNumPredict()));
        cursor += fieldRowH;

        cursor += gapBeforeBottom - 6;
        addTravellersButton(ID_RESET, bx, cursor, bw, 16, I18n.format("travellers.dialogue.setup.advanced.reset"))
                .style(GuiTravellersButton.Style.DANGER);

        int bottom = panelY + panelH - bottomMargin - bottomRowH;
        addTravellersButton(ID_SAVE, bx, bottom, (bw - 6) / 2, 16, I18n.format("travellers.gui.save"))
                .style(GuiTravellersButton.Style.SCREEN);
        addTravellersButton(ID_BACK, bx + (bw - 6) / 2 + 6, bottom, (bw - 6) / 2, 16, I18n.format("travellers.gui.cancel"));
    }

    private GuiTextField addNumberField(int x, int y, int w, String initialText) {
        // Label is drawn above the field at y-10 (see drawScreen); the field itself sits at y+10.
        GuiTextField field = new GuiTextField(allFields.size(), this.fontRenderer, x, y + 10, w, 14);
        field.setMaxStringLength(16);
        field.setText(initialText);
        allFields.add(field);
        return field;
    }

    private GuiTravellersButton addTravellersButton(int id, int x, int y, int w, int h, String text) {
        GuiTravellersButton btn = new GuiTravellersButton(id, x, y, w, h, text);
        this.buttonList.add(btn);
        return btn;
    }

    @Override
    protected void actionPerformed(net.minecraft.client.gui.GuiButton button) {
        if (button.id == ID_THINK) {
            thinkLocal = !thinkLocal;
            thinkBtn.toggled(thinkLocal);
            return;
        }
        if (button.id == ID_TOOLS) {
            toolsEnabledLocal = !toolsEnabledLocal;
            toolsBtn.toggled(toolsEnabledLocal);
            return;
        }
        if (button.id == ID_RESET) {
            thinkLocal = false;
            thinkBtn.toggled(false);
            toolsEnabledLocal = false;
            toolsBtn.toggled(false);
            numCtxField.setText("");
            temperatureField.setText("");
            topPField.setText("");
            topKField.setText("");
            repeatPenaltyField.setText("");
            numPredictField.setText("");
            return;
        }
        if (button.id == ID_SAVE) {
            DialogueClientSettings.saveLlmTuning(
                    thinkLocal,
                    parseInt(numCtxField.getText()),
                    parseDouble(temperatureField.getText()),
                    parseDouble(topPField.getText()),
                    parseInt(topKField.getText()),
                    parseDouble(repeatPenaltyField.getText()),
                    parseInt(numPredictField.getText()),
                    toolsEnabledLocal);
            goBack();
            return;
        }
        if (button.id == ID_BACK) {
            goBack();
        }
    }

    private void goBack() {
        this.mc.displayGuiScreen(parent);
    }

    private static String formatInt(int value) {
        return value == ChatOptions.UNSET_INT ? "" : String.valueOf(value);
    }

    private static String formatDouble(double value) {
        return Double.isNaN(value) ? "" : String.valueOf(value);
    }

    private static int parseInt(String text) {
        if (text == null || text.trim().isEmpty()) return ChatOptions.UNSET_INT;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return ChatOptions.UNSET_INT;
        }
    }

    private static double parseDouble(String text) {
        if (text == null || text.trim().isEmpty()) return ChatOptions.UNSET_DOUBLE;
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException ex) {
            return ChatOptions.UNSET_DOUBLE;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        for (GuiTextField field : allFields) {
            field.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            goBack();
            return;
        }
        for (GuiTextField field : allFields) {
            if (field.textboxKeyTyped(typedChar, keyCode)) return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField field : allFields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();

        TravellersGuiTheme.drawPanel(panelX, panelY, panelW, panelH);
        TravellersGuiTheme.drawTitle(this.fontRenderer,
                I18n.format("travellers.dialogue.setup.advanced.title"), panelX + 8, panelY + 8);
        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.advanced.intro"),
                panelX + 8, panelY + 22, TravellersGuiTheme.COL_TEXT_DIM);

        this.fontRenderer.drawString(I18n.format("travellers.dialogue.setup.advanced.tools_warning"),
                toolsWarningX, toolsWarningY + 3, TravellersGuiTheme.COL_TEXT_DIM);

        drawLabeledField(numCtxField, serverType.supportsContextSize()
                ? "travellers.dialogue.setup.advanced.num_ctx" : "travellers.dialogue.setup.advanced.num_ctx_fixed");
        drawLabeledField(temperatureField, "travellers.dialogue.setup.advanced.temperature");
        drawLabeledField(topPField, "travellers.dialogue.setup.advanced.top_p");
        drawLabeledField(topKField, "travellers.dialogue.setup.advanced.top_k");
        drawLabeledField(repeatPenaltyField, "travellers.dialogue.setup.advanced.repeat_penalty");
        drawLabeledField(numPredictField, "travellers.dialogue.setup.advanced.num_predict");

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawLabeledField(GuiTextField field, String labelKey) {
        this.fontRenderer.drawString(I18n.format(labelKey), field.x, field.y - 10, TravellersGuiTheme.COL_TEXT_DIM);
        field.drawTextBox();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
