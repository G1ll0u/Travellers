package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.formations.WorkerFormationScopeKey;
import com.jubitus.travellers.follow.formations.WorkerFormationSettings;
import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.MsgUpdateFormationByScope;
import com.jubitus.travellers.gui.ButtonTooltipHelper;
import com.jubitus.travellers.gui.GuiTravellersButton;
import com.jubitus.travellers.net.TravellersNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The formation settings of the selected leader row (style, attitude, escorts, distance, spacing, width),
 * sent to the server as they change, throttled while a slider is dragged.
 */
final class FormationSettingsCard {

    private static final long SYNC_THROTTLE_MS = 120L;

    /** Last known settings per scope key; refreshed in place by the hub. */
    private final Map<String, WorkerFormationSettings> settingsByScope;

    @Nullable private WorkerFormationScopeKey scope;
    private WorkerFormationSettings settings;

    private GuiWorkerFormationSlider escortsSlider, followDistSlider, spacingSlider, widthSlider;
    private GuiButton styleButton, attitudeButton;

    private long lastSendMs = 0L;
    private boolean pendingSync = false;

    FormationSettingsCard(Map<String, WorkerFormationSettings> settingsByScope) {
        this.settingsByScope = settingsByScope;
    }

    static WorkerFormationScopeKey scopeOf(FormationLeaderInfo info, UUID playerId) {
        if (info.isPlayer || info.uuid == null)
            return new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.PLAYER, playerId);
        return new WorkerFormationScopeKey(WorkerFormationScopeKey.Type.WORKER, info.uuid);
    }

    /** Style + attitude buttons at (x, y), then the four sliders, spread down to bottom. */
    void init(List<GuiButton> buttonList, ButtonTooltipHelper tooltips, int x, int y, int w, int bottom) {
        styleButton = new GuiTravellersButton(1, x, y, w, 16, "");
        attitudeButton = new GuiTravellersButton(2, x, y + 20, w, 16, "");
        buttonList.add(styleButton);
        buttonList.add(attitudeButton);
        tooltips.set(attitudeButton, "travellers.worker.formation.attitude.tooltip");
        int sliderY = y + 42;
        int pitch = Math.max(24, Math.min(28, (bottom - 20 - sliderY) / 3));
        escortsSlider = new GuiWorkerFormationSlider(x, sliderY, w, 20,
                "travellers.worker.formation.escorts",
                WorkerFormationSettings.MIN_ESCORTS, WorkerFormationSettings.MAX_ESCORTS,
                1.0D, settings != null ? settings.numEscorts : 0,
                v -> Integer.toString((int) Math.round(v)));
        followDistSlider = new GuiWorkerFormationSlider(x, sliderY + pitch, w, 20,
                "travellers.worker.formation.follow_distance",
                WorkerFormationSettings.MIN_FOLLOW_DISTANCE, WorkerFormationSettings.MAX_FOLLOW_DISTANCE,
                0.5D, settings != null ? settings.followDistance : 8.0, FormationSettingsCard::formatDouble);
        spacingSlider = new GuiWorkerFormationSlider(x, sliderY + pitch * 2, w, 20,
                "travellers.worker.formation.spacing",
                WorkerFormationSettings.MIN_SPACING, WorkerFormationSettings.MAX_SPACING,
                0.25D, settings != null ? settings.spacing : 1.0, FormationSettingsCard::formatDouble);
        widthSlider = new GuiWorkerFormationSlider(x, sliderY + pitch * 3, w, 20,
                "travellers.worker.formation.width",
                WorkerFormationSettings.MIN_WIDTH, WorkerFormationSettings.MAX_WIDTH,
                1.0D, settings != null ? settings.width : 4,
                v -> Integer.toString((int) Math.round(v)));
    }

    /** Shows the settings of this scope; null = no leader at all (defaults, nothing to send). */
    void select(@Nullable WorkerFormationScopeKey newScope) {
        scope = newScope;
        if (newScope == null) {
            settings = WorkerFormationSettings.defaults();
            return;
        }
        WorkerFormationSettings s = settingsByScope.get(newScope.asMapKey());
        settings = s != null ? s.copy().clamp() : WorkerFormationSettings.defaults();
        pushToSliders();
    }

    boolean hasSettings() {
        return settings != null;
    }

    /** Shows or hides the buttons and refreshes their labels. */
    void refreshButtons(boolean shown) {
        if (styleButton == null) return;
        styleButton.visible = shown;
        attitudeButton.visible = shown;
        if (!shown) return;
        styleButton.displayString = I18n.format("travellers.worker.formation.style") + ": "
                + I18n.format("travellers.worker.formation.style." + settings.style.name().toLowerCase(Locale.ROOT));
        attitudeButton.displayString = I18n.format("travellers.worker.formation.attitude") + ": "
                + I18n.format("travellers.worker.formation.attitude." + settings.attitude.name().toLowerCase(Locale.ROOT));
    }

    void drawSliders(Minecraft mc, int mouseX, int mouseY) {
        escortsSlider.draw(mc, mouseX, mouseY);
        followDistSlider.draw(mc, mouseX, mouseY);
        spacingSlider.draw(mc, mouseX, mouseY);
        widthSlider.draw(mc, mouseX, mouseY);
    }

    /** Style / attitude buttons; false when the button is not one of these. */
    boolean actionPerformed(GuiButton button) {
        if (settings == null) return false;
        if (button == styleButton) settings.style = settings.style.next();
        else if (button == attitudeButton) settings.attitude = settings.attitude.next();
        else return false;
        onChanged(true);
        return true;
    }

    boolean mousePressed(int mouseX, int mouseY, int mouseButton) {
        boolean consumed = escortsSlider.mousePressed(mouseX, mouseY, mouseButton);
        consumed |= followDistSlider.mousePressed(mouseX, mouseY, mouseButton);
        consumed |= spacingSlider.mousePressed(mouseX, mouseY, mouseButton);
        consumed |= widthSlider.mousePressed(mouseX, mouseY, mouseButton);
        if (consumed) {
            pullSliders();
            onChanged(false);
        }
        return consumed;
    }

    void mouseDragged(int mouseX, int mouseY) {
        boolean changed = escortsSlider.mouseDragged(mouseX, mouseY);
        changed |= followDistSlider.mouseDragged(mouseX, mouseY);
        changed |= spacingSlider.mouseDragged(mouseX, mouseY);
        changed |= widthSlider.mouseDragged(mouseX, mouseY);
        if (changed) {
            pullSliders();
            onChanged(false);
        }
    }

    void mouseReleased(int state) {
        boolean changed = escortsSlider.mouseReleased(state);
        changed |= followDistSlider.mouseReleased(state);
        changed |= spacingSlider.mouseReleased(state);
        changed |= widthSlider.mouseReleased(state);
        if (changed) {
            pullSliders();
            onChanged(false);
            flush(true);
        }
    }

    /** Sends pending changes; unforced, at most once per throttle window. */
    void flush(boolean force) {
        if (!pendingSync || scope == null || settings == null) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastSendMs) < SYNC_THROTTLE_MS) return;
        lastSendMs = now;
        pendingSync = false;
        settingsByScope.put(scope.asMapKey(), settings.copy().clamp());
        TravellersNetwork.CHANNEL.sendToServer(new MsgUpdateFormationByScope(scope, settings.copy().clamp()));
    }

    private void onChanged(boolean immediate) {
        if (settings != null) settings.clamp();
        refreshButtons(styleButton != null && styleButton.visible);
        pendingSync = true;
        if (immediate) flush(true);
    }

    private void pushToSliders() {
        if (settings == null) return;
        if (escortsSlider != null) escortsSlider.setValue(settings.numEscorts);
        if (followDistSlider != null) followDistSlider.setValue(settings.followDistance);
        if (spacingSlider != null) spacingSlider.setValue(settings.spacing);
        if (widthSlider != null) widthSlider.setValue(settings.width);
    }

    private void pullSliders() {
        if (settings == null) return;
        settings.numEscorts = (int) Math.round(escortsSlider.getValue());
        settings.followDistance = followDistSlider.getValue();
        settings.spacing = spacingSlider.getValue();
        settings.width = (int) Math.round(widthSlider.getValue());
        settings.clamp();
    }

    private static String formatDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.001D) return Integer.toString((int) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
