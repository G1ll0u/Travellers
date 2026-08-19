package com.jubitus.travellers.client.gui;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.client.config.GuiUtils;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Shows a short I18n description next to a button after it has been hovered for
 * TravellersConfig.GUI_TOOLTIPS.hoverDelayMs, instead of instantly. One instance per GUI screen.
 */
@SideOnly(Side.CLIENT)
public final class ButtonTooltipHelper {

    private final Map<GuiButton, String> descriptionKeys = new IdentityHashMap<>();
    private GuiButton hoveredButton;
    private long hoverStartMs;

    public void set(GuiButton button, String i18nKey) {
        if (button != null) {
            descriptionKeys.put(button, i18nKey);
        }
    }

    /** Drops every registration, for screens that rebuild their buttons. */
    public void clear() {
        descriptionKeys.clear();
        hoveredButton = null;
    }

    public void render(int mouseX, int mouseY, int screenWidth, int screenHeight) {
        if (!TravellersConfig.GUI_TOOLTIPS.masterEnabled) {
            hoveredButton = null;
            return;
        }

        GuiButton current = null;
        for (Map.Entry<GuiButton, String> entry : descriptionKeys.entrySet()) {
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

        String text = I18n.format(descriptionKeys.get(hoveredButton));
        GuiUtils.drawHoveringText(
                Arrays.asList(text.split("\n")),
                mouseX, mouseY, screenWidth, screenHeight, -1,
                Minecraft.getMinecraft().fontRenderer
        );
    }
}
