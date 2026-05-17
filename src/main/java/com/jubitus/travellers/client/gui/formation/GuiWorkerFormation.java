package com.jubitus.travellers.client.gui.formation;

import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.entity.entityAI.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.server.net.message.formations.MsgUpdateWorkerFormationSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SideOnly(Side.CLIENT)
public class GuiWorkerFormation extends GuiScreen {

    private final EntityPlayer player;
    private final EntityTravellerWorker worker;
    private final WorkerFormationScopeKey scope;
    private final int followerCount;

    private WorkerFormationSettings settings;

    private static final int X_SIZE = 372;
    private static final int Y_SIZE = 290;

    private int guiLeft;
    private int guiTop;
    private int effectiveXSize;
    private int effectiveYSize;

    private int previewX;
    private int previewY;
    private int previewW;
    private int previewH;

    private GuiFormationPreviewPanel previewPanel;

    // Sliders (right column)
    private GuiWorkerFormationSlider escortsSlider;
    private GuiWorkerFormationSlider followDistanceSlider;
    private GuiWorkerFormationSlider spacingSlider;
    private GuiWorkerFormationSlider widthSlider;
    private GuiWorkerFormationSlider turnSlider;

    // Buttons (right column)
    private GuiButton styleButton;
    private GuiButton caveButton;

    // Preview controls (bottom-left) + navigation (bottom-right)
    private GuiButton zoomInButton;
    private GuiButton zoomOutButton;
    private GuiButton recenterButton;
    private GuiButton backButton;

    private long lastSendMs = 0L;
    private boolean pendingSync = false;
    private static final long SYNC_THROTTLE_MS = 120L;

    public GuiWorkerFormation(EntityPlayer player,
                              EntityTravellerWorker worker,
                              WorkerFormationScopeKey scope,
                              String scopeLabel,
                              int followerCount,
                              WorkerFormationSettings settings) {
        this.player = player;
        this.worker = worker;
        this.scope = scope;
        this.followerCount = Math.max(0, followerCount);
        this.settings = settings == null ? WorkerFormationSettings.defaults() : settings.copy().clamp();
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.clear();

        this.effectiveXSize = Math.min(X_SIZE, this.width - 4);
        this.effectiveYSize = Math.min(Y_SIZE, this.height - 4);
        this.guiLeft = (this.width  - effectiveXSize) / 2;
        this.guiTop  = Math.max(2, (this.height - effectiveYSize) / 2);

        // ── Preview panel (left side) ─────────────────────────────────────────
        this.previewX = guiLeft + 10;
        this.previewY = guiTop  + 20;
        this.previewW = 222;
        this.previewH = 234;   // taller preview thanks to removed header clutter

        this.previewPanel = new GuiFormationPreviewPanel(Minecraft.getMinecraft(), previewW, previewH);
        this.previewPanel.setData(settings, followerCount);

        // ── Right control column ──────────────────────────────────────────────
        int rightX = previewX + previewW + 14;  // guiLeft + 246
        int rightW = 116;
        int rowY   = guiTop + 20;               // aligns with previewY

        this.styleButton = this.addButton(new GuiButton(1, rightX, rowY, rightW, 20, ""));
        rowY += 26;

        this.caveButton = this.addButton(new GuiButton(2, rightX, rowY, rightW, 20, ""));
        rowY += 30;

        this.escortsSlider = new GuiWorkerFormationSlider(
                rightX, rowY, rightW, 20,
                "travellers.worker.formation.escorts",
                WorkerFormationSettings.MIN_ESCORTS,
                WorkerFormationSettings.MAX_ESCORTS,
                1.0D,
                settings.numEscorts,
                v -> Integer.toString((int) Math.round(v))
        );
        rowY += 34;

        this.followDistanceSlider = new GuiWorkerFormationSlider(
                rightX, rowY, rightW, 20,
                "travellers.worker.formation.follow_distance",
                WorkerFormationSettings.MIN_FOLLOW_DISTANCE,
                WorkerFormationSettings.MAX_FOLLOW_DISTANCE,
                0.5D,
                settings.followDistance,
                v -> formatDouble(v)
        );
        rowY += 34;

        this.spacingSlider = new GuiWorkerFormationSlider(
                rightX, rowY, rightW, 20,
                "travellers.worker.formation.spacing",
                WorkerFormationSettings.MIN_SPACING,
                WorkerFormationSettings.MAX_SPACING,
                0.25D,
                settings.spacing,
                v -> formatDouble(v)
        );
        rowY += 34;

        this.widthSlider = new GuiWorkerFormationSlider(
                rightX, rowY, rightW, 20,
                "travellers.worker.formation.width",
                WorkerFormationSettings.MIN_WIDTH,
                WorkerFormationSettings.MAX_WIDTH,
                1.0D,
                settings.width,
                v -> Integer.toString((int) Math.round(v))
        );
        rowY += 34;

        this.turnSlider = new GuiWorkerFormationSlider(
                rightX, rowY, rightW, 20,
                "travellers.worker.formation.turn",
                WorkerFormationSettings.MIN_TURN_SMOOTHNESS,
                WorkerFormationSettings.MAX_TURN_SMOOTHNESS,
                5.0D,
                settings.turnSmoothness,
                v -> Integer.toString((int) Math.round(v))
        );
        // turnSlider ends at rowY + 20; bottomY starts at guiTop + Y_SIZE - 24 = guiTop + 266
        // Gap ≥ 30 px — no overlap.

        // ── Bottom row ────────────────────────────────────────────────────────
        int bottomY = guiTop + effectiveYSize - 24;

        this.zoomOutButton  = this.addButton(new GuiButton(10, previewX,      bottomY, 20, 20, "-"));
        this.zoomInButton   = this.addButton(new GuiButton(11, previewX + 24, bottomY, 20, 20, "+"));
        this.recenterButton = this.addButton(new GuiButton(
                12, previewX + 50, bottomY, 62, 20,
                I18n.format("travellers.worker.formation.preview.reset")
        ));

        this.backButton = this.addButton(new GuiButton(
                99, guiLeft + effectiveXSize - 70, bottomY, 60, 20,
                I18n.format("travellers.gui.back")
        ));

        refreshButtons();
    }

    // ── Event handlers ────────────────────────────────────────────────────────

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (worker == null || worker.isDead) {
            mc.player.closeScreen();
            return;
        }
        flushPendingSync(false);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == styleButton) {
            settings.style = settings.style.next();
            onSettingsChanged(true);
            return;
        }
        if (button == caveButton) {
            settings.adaptiveCave = !settings.adaptiveCave;
            onSettingsChanged(true);
            return;
        }
        if (button == zoomInButton)   { previewPanel.zoomIn();    return; }
        if (button == zoomOutButton)  { previewPanel.zoomOut();   return; }
        if (button == recenterButton) { previewPanel.resetView(); return; }
        if (button == backButton) {
            flushPendingSync(true);
            TravellerFollowNet.CHANNEL.sendToServer(
                    new MsgOpenWorkerSubGui(worker.getEntityId(), MsgOpenWorkerSubGui.Type.HUB)
            );
            return;
        }
        super.actionPerformed(button);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        boolean consumed = false;
        if (escortsSlider.mousePressed(mouseX, mouseY, mouseButton))       consumed = true;
        if (followDistanceSlider.mousePressed(mouseX, mouseY, mouseButton)) consumed = true;
        if (spacingSlider.mousePressed(mouseX, mouseY, mouseButton))       consumed = true;
        if (widthSlider.mousePressed(mouseX, mouseY, mouseButton))         consumed = true;
        if (turnSlider.mousePressed(mouseX, mouseY, mouseButton))          consumed = true;

        if (consumed) {
            pullSliderValues();
            onSettingsChanged(false);
            return;
        }
        if (previewPanel.mousePressed(mouseX, mouseY, mouseButton, previewX, previewY)) return;
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);

        boolean changed = false;
        changed |= escortsSlider.mouseDragged(mouseX, mouseY);
        changed |= followDistanceSlider.mouseDragged(mouseX, mouseY);
        changed |= spacingSlider.mouseDragged(mouseX, mouseY);
        changed |= widthSlider.mouseDragged(mouseX, mouseY);
        changed |= turnSlider.mouseDragged(mouseX, mouseY);

        if (changed) {
            pullSliderValues();
            onSettingsChanged(false);
        }
        previewPanel.mouseDragged(mouseX, mouseY);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);

        boolean changed = false;
        changed |= escortsSlider.mouseReleased(state);
        changed |= followDistanceSlider.mouseReleased(state);
        changed |= spacingSlider.mouseReleased(state);
        changed |= widthSlider.mouseReleased(state);
        changed |= turnSlider.mouseReleased(state);

        previewPanel.mouseReleased(state);

        if (changed) {
            pullSliderValues();
            onSettingsChanged(false);
            flushPendingSync(true);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mouseX = org.lwjgl.input.Mouse.getEventX() * this.width  / this.mc.displayWidth;
        int mouseY = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        if (previewPanel.isMouseOver(mouseX, mouseY, previewX, previewY)) {
            previewPanel.handleWheel(wheel);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        flushPendingSync(true);
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        // Background frame
        drawRect(guiLeft, guiTop, guiLeft + effectiveXSize, guiTop + effectiveYSize, 0xFF2A2219);
        drawRect(guiLeft + 1, guiTop + 1, guiLeft + effectiveXSize - 1, guiTop + effectiveYSize - 1, 0xFF685747);

        // Title + follower count on the same line
        String title = TextFormatting.BOLD + I18n.format("travellers.worker.formation.title");
        drawCenteredString(fontRenderer, title, guiLeft + effectiveXSize / 2, guiTop + 7, 0xF2D2B9);

        String followerStr = I18n.format("travellers.worker.formation.followers") + ": " + followerCount;
        fontRenderer.drawString(followerStr, guiLeft + effectiveXSize - 10 - fontRenderer.getStringWidth(followerStr), guiTop + 7, 0xD0D0D0);

        // Preview panel (clips itself via GL scissor)
        previewPanel.draw(previewX, previewY, mouseX, mouseY);

        // Super draws buttons
        super.drawScreen(mouseX, mouseY, partialTicks);

        // Sliders on top
        escortsSlider.draw(mc, mouseX, mouseY);
        followDistanceSlider.draw(mc, mouseX, mouseY);
        spacingSlider.draw(mc, mouseX, mouseY);
        widthSlider.draw(mc, mouseX, mouseY);
        turnSlider.draw(mc, mouseX, mouseY);

        // Hover tooltips from preview
        List<String> hover = previewPanel.getHoverLines(mouseX, mouseY, previewX, previewY);
        if (!hover.isEmpty()) {
            drawHoveringText(hover, mouseX, mouseY);
        }
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private void refreshButtons() {
        settings.clamp();

        if (styleButton != null) {
            styleButton.displayString =
                    I18n.format("travellers.worker.formation.style") + ": "
                            + I18n.format("travellers.worker.formation.style."
                            + settings.style.name().toLowerCase(Locale.ROOT));
        }
        if (caveButton != null) {
            caveButton.displayString = settings.adaptiveCave
                    ? I18n.format("travellers.worker.formation.cave.on")
                    : I18n.format("travellers.worker.formation.cave.off");
        }

        if (escortsSlider      != null) escortsSlider.setValue(settings.numEscorts);
        if (followDistanceSlider != null) followDistanceSlider.setValue(settings.followDistance);
        if (spacingSlider      != null) spacingSlider.setValue(settings.spacing);
        if (widthSlider        != null) widthSlider.setValue(settings.width);
        if (turnSlider         != null) turnSlider.setValue(settings.turnSmoothness);

        if (previewPanel != null) previewPanel.setData(settings, followerCount);
    }

    private void pullSliderValues() {
        settings.numEscorts    = (int) Math.round(escortsSlider.getValue());
        settings.followDistance = followDistanceSlider.getValue();
        settings.spacing        = spacingSlider.getValue();
        settings.width          = (int) Math.round(widthSlider.getValue());
        settings.turnSmoothness = (int) Math.round(turnSlider.getValue());
        settings.clamp();
        refreshButtons();
    }

    private void onSettingsChanged(boolean immediate) {
        settings.clamp();
        refreshButtons();
        pendingSync = true;
        if (immediate) flushPendingSync(true);
    }

    private void flushPendingSync(boolean force) {
        if (!pendingSync) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastSendMs) < SYNC_THROTTLE_MS) return;
        lastSendMs = now;
        pendingSync = false;
        TravellerFollowNet.CHANNEL.sendToServer(
                new MsgUpdateWorkerFormationSettings(worker.getEntityId(), scope, settings.copy().clamp())
        );
    }

    private String formatDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001D) return Integer.toString((int) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
