package com.jubitus.travellers.gui;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shows a short I18n description next to a button after it has been hovered for
 * TravellersConfig.GUI_TOOLTIPS.hoverDelayMs, instead of instantly. One instance per GUI screen.
 */
@SideOnly(Side.CLIENT)
public final class ButtonTooltipHelper {

    private final Map<GuiButton, Supplier<String>> descriptions = new IdentityHashMap<>();
    private GuiButton hoveredButton;
    private long hoverStartMs;

    public void set(GuiButton button, String i18nKey) {
        set(button, () -> I18n.format(i18nKey));
    }

    /** Text worked out on each hover (e.g. why a button is greyed); null or empty shows nothing. */
    public void set(GuiButton button, Supplier<String> text) {
        if (button != null) {
            descriptions.put(button, text);
        }
    }

    /** Drops every registration, for screens that rebuild their buttons. */
    public void clear() {
        descriptions.clear();
        hoveredButton = null;
    }

    public void render(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!TravellersConfig.GUI_TOOLTIPS.masterEnabled) {
            hoveredButton = null;
            return;
        }

        GuiButton current = null;
        for (Map.Entry<GuiButton, Supplier<String>> entry : descriptions.entrySet()) {
            GuiButton button = entry.getKey();
            if (button.visible && button.isMouseOver()) {
                current = button;
                break;
            }
        }

        if (current != hoveredButton) {
            hoveredButton = current;
            hoverStartMs = System.currentTimeMillis();
        }

        if (hoveredButton == null
                || System.currentTimeMillis() - hoverStartMs < TravellersConfig.GUI_TOOLTIPS.hoverDelayMs) {
            return;
        }

        String text = descriptions.get(hoveredButton).get();
        if (text == null || text.isEmpty()) return;
        MoneyText.drawTooltip(Minecraft.getMinecraft().fontRenderer, Arrays.asList(text.split("\n")),
                mouseX, mouseY, screenWidth, screenHeight);
    }
}
