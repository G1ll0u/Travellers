package com.jubitus.travellers.follow.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.MathHelper;

public class GuiWorkerFormationSlider {

    public interface ValueFormatter {
        String format(double value);
    }

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private final String labelKey;
    private final double minValue;
    private final double maxValue;
    private final double step;
    private final ValueFormatter formatter;

    private double value;
    private boolean dragging = false;
    private boolean changedThisDrag = false;

    public GuiWorkerFormationSlider(int x,
                                    int y,
                                    int width,
                                    int height,
                                    String labelKey,
                                    double minValue,
                                    double maxValue,
                                    double step,
                                    double value,
                                    ValueFormatter formatter) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.labelKey = labelKey;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.step = step;
        this.value = clampStep(value);
        this.formatter = formatter;
    }

    public void setValue(double value) {
        this.value = clampStep(value);
    }

    public double getValue() {
        return value;
    }

    public boolean isDragging() {
        return dragging;
    }

    public boolean mousePressed(int mouseX, int mouseY, int button) {
        if (button != 0 || !isMouseOver(mouseX, mouseY)) {
            return false;
        }

        dragging = true;
        changedThisDrag = false;
        setValueFromMouse(mouseX);
        return true;
    }

    public boolean mouseDragged(int mouseX, int mouseY) {
        if (!dragging) {
            return false;
        }

        double old = value;
        setValueFromMouse(mouseX);
        boolean changed = Math.abs(old - value) > 0.0001D;
        changedThisDrag |= changed;
        return changed;
    }

    public boolean mouseReleased(int button) {
        boolean changed = dragging && changedThisDrag;
        if (button == 0) {
            dragging = false;
            changedThisDrag = false;
        }
        return changed;
    }

    public void draw(Minecraft mc, int mouseX, int mouseY) {
        int labelColor = 0xF0F0F0;
        int valueColor = dragging ? 0xFFE1A8 : 0xFFD8D8D8;

        mc.fontRenderer.drawString(I18n.format(labelKey), x, y, labelColor);
        mc.fontRenderer.drawString(formatter.format(value), x + width - 46, y, valueColor);

        int barX = x;
        int barY = y + 12;
        int barH = 8;

        Gui.drawRect(barX, barY, barX + width, barY + barH, 0xFF121212);
        Gui.drawRect(barX + 1, barY + 1, barX + width - 1, barY + barH - 1, 0xFF2C2C2C);

        int fillW = Math.max(0, Math.min(width, (int) Math.round(getNorm() * (double) width)));
        Gui.drawRect(barX + 1, barY + 1, barX + fillW, barY + barH - 1, 0xFF8E7752);

        int knobX = barX + (int) Math.round(getNorm() * (double) width);
        int knobHalf = 3;
        int knobColor = dragging ? 0xFFFFD287 : 0xFFF5D2A0;

        Gui.drawRect(knobX - knobHalf, barY - 2, knobX + knobHalf + 1, barY + barH + 2, knobColor);
        Gui.drawRect(knobX - knobHalf - 1, barY - 3, knobX + knobHalf + 2, barY - 2, 0xFF000000);
        Gui.drawRect(knobX - knobHalf - 1, barY + barH + 2, knobX + knobHalf + 2, barY + barH + 3, 0xFF000000);
        Gui.drawRect(knobX - knobHalf - 1, barY - 3, knobX - knobHalf, barY + barH + 3, 0xFF000000);
        Gui.drawRect(knobX + knobHalf + 1, barY - 3, knobX + knobHalf + 2, barY + barH + 3, 0xFF000000);
    }

    private void setValueFromMouse(int mouseX) {
        double norm = (mouseX - x) / (double) width;
        norm = MathHelper.clamp(norm, 0.0D, 1.0D);
        setValue(minValue + (maxValue - minValue) * norm);
    }

    private double getNorm() {
        if (maxValue <= minValue) {
            return 0.0D;
        }
        return (value - minValue) / (maxValue - minValue);
    }

    private double clampStep(double v) {
        double clamped = MathHelper.clamp(v, minValue, maxValue);
        double stepped = Math.round((clamped - minValue) / step) * step + minValue;

        if (Math.abs(step - 1.0D) < 0.0001D) {
            stepped = Math.rint(stepped);
        }

        return MathHelper.clamp(stepped, minValue, maxValue);
    }

    private boolean isMouseOver(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y + 8 && mouseY < y + 24;
    }
}