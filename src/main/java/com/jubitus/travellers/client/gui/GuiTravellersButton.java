package com.jubitus.travellers.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;

/**
 * The mod's standard button. Drop-in replacement for {@link GuiButton}: constructed the same
 * way, drawn entirely from {@link TravellersGuiTheme} instead of the vanilla texture.
 *
 * Four states are distinguished visually — normal, hovered, disabled and active — so a toggle
 * no longer has to rename itself ("Follow: ON") to say what it is doing. Optional markers
 * (branch chevron, sub-screen arrow, count badge) are drawn inside the button box, never
 * outside it, so they can't collide with a neighbour.
 */
@SideOnly(Side.CLIENT)
public class GuiTravellersButton extends GuiButton {

    public enum Style {
        /** Plain button, label centered. */
        NORMAL,
        /** Opens sub-entries in place; shows a chevron. */
        BRANCH,
        /** Opens a separate screen; shows a trailing arrow. */
        SCREEN,
        /** On/off order; shows a state dot. */
        TOGGLE,
        /** Destructive action; drawn in warning colors. */
        DANGER
    }

    private Style style = Style.NORMAL;
    private boolean toggledOn;
    private boolean expanded;
    @Nullable private String badge;
    private boolean hideScreenArrow;

    public GuiTravellersButton(int id, int x, int y, int w, int h, String text) {
        super(id, x, y, w, h, text);
    }

    public GuiTravellersButton style(Style s) {
        this.style = s;
        return this;
    }

    public GuiTravellersButton toggled(boolean on) {
        this.toggledOn = on;
        return this;
    }

    public GuiTravellersButton expanded(boolean open) {
        this.expanded = open;
        return this;
    }

    /** Short text pinned to the right edge, e.g. a follower count. Null to clear. */
    public GuiTravellersButton badge(@Nullable String text) {
        this.badge = text;
        return this;
    }

    /**
     * Suppresses the trailing "&gt;" that {@link Style#SCREEN} normally draws. Used by hub
     * shortcuts, which are already sized to their own label and read cluttered with a marker whose
     * only job was to say "this opens another screen" — obvious enough from being a shortcut.
     */
    public GuiTravellersButton hideScreenArrow(boolean hide) {
        this.hideScreenArrow = hide;
        return this;
    }

    public Style getStyle() {
        return this.style;
    }

    public boolean isExpanded() {
        return this.expanded;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) return;

        this.hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;

        int fill = this.enabled
                ? (this.hovered ? TravellersGuiTheme.COL_BTN_BG_HOVER : TravellersGuiTheme.COL_BTN_BG)
                : TravellersGuiTheme.COL_BTN_BG_OFF;

        int border = resolveBorder();
        int textColor = resolveTextColor();

        TravellersGuiTheme.drawButtonBox(this.x, this.y, this.width, this.height, fill, border);

        int pad = 4;
        int left = this.x + pad;
        int right = this.x + this.width - pad;
        int textY = this.y + (this.height - 8) / 2;

        // Leading marker
        if (style == Style.BRANCH) {
            String chevron = this.expanded ? "-" : "+";
            mc.fontRenderer.drawString(chevron, left, textY,
                    this.expanded ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT_DIM);
            left += mc.fontRenderer.getStringWidth(chevron) + 3;
        } else if (style == Style.TOGGLE) {
            int dot = !this.enabled ? TravellersGuiTheme.COL_BTN_BORDER_OFF
                    : (this.toggledOn ? TravellersGuiTheme.COL_ACTIVE : TravellersGuiTheme.COL_BTN_BORDER_OFF);
            int dy = this.y + this.height / 2 - 1;
            drawRect(left, dy, left + 3, dy + 3, dot);
            left += 6;
        }

        // Trailing markers
        if (badge != null && !badge.isEmpty()) {
            int bw = mc.fontRenderer.getStringWidth(badge);
            mc.fontRenderer.drawString(badge, right - bw, textY,
                    this.enabled ? TravellersGuiTheme.COL_TEXT_DIM : TravellersGuiTheme.COL_BTN_TEXT_OFF);
            right -= bw + 4;
        } else if (style == Style.SCREEN && !hideScreenArrow) {
            mc.fontRenderer.drawString(">", right - mc.fontRenderer.getStringWidth(">"), textY,
                    this.enabled ? TravellersGuiTheme.COL_TEXT_DIM : TravellersGuiTheme.COL_BTN_TEXT_OFF);
            right -= 6;
        }

        int room = Math.max(0, right - left);
        String label = TravellersGuiTheme.trimLabel(mc.fontRenderer, this.displayString, room);

        // Centered on the button itself, so a column of buttons reads as one block — but never
        // allowed to run under the leading marker, which stays pinned to the left edge.
        int labelX = this.x + (this.width - mc.fontRenderer.getStringWidth(label)) / 2;
        mc.fontRenderer.drawString(label, Math.max(labelX, left), textY, textColor);
    }

    private int resolveBorder() {
        if (!this.enabled) return TravellersGuiTheme.COL_BTN_BORDER_OFF;
        if (style == Style.DANGER) {
            return this.hovered ? TravellersGuiTheme.COL_DANGER_BORDER_HOV : TravellersGuiTheme.COL_DANGER_BORDER;
        }
        if (style == Style.TOGGLE && this.toggledOn) return TravellersGuiTheme.COL_ACTIVE;
        if (this.expanded || this.hovered) return TravellersGuiTheme.COL_TEXT_HOV;
        return TravellersGuiTheme.COL_BORDER;
    }

    private int resolveTextColor() {
        if (!this.enabled) return TravellersGuiTheme.COL_BTN_TEXT_OFF;
        if (style == Style.DANGER) {
            return this.hovered ? TravellersGuiTheme.COL_DANGER_TEXT_HOV : TravellersGuiTheme.COL_DANGER_TEXT;
        }
        return this.hovered ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
    }
}
