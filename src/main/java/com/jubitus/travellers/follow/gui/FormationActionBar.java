package com.jubitus.travellers.follow.gui;

import com.jubitus.travellers.follow.net.FormationLeaderInfo;
import com.jubitus.travellers.follow.net.MsgGroupCreate;
import com.jubitus.travellers.gui.TravellersGuiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Formation Hub's action bar under the map: the orders that make sense for the current selection,
 * as buttons. It holds no logic of its own; every button calls an order of {@link FormationOrdersMenu}.
 * A button shows the PNG {@code textures/gui/formation_actions/<id>.png} when the resource pack has one,
 * its short text otherwise.
 */
final class FormationActionBar {

    static final int HEIGHT = 16;
    private static final int GAP = 2;
    private static final int ICON_SIZE = 12;

    private enum Id { FOLLOW_ME, HALT, RESUME, FOLLOW, MERGE, CENTER, CREATE_GROUP, JOIN }

    private interface Run {
        void run(int buttonX, int buttonY);
    }

    private static final class Action {
        final Id id;
        @Nullable final String disabledReason;
        final Run run;
        int x, y, w;

        Action(Id id, @Nullable String disabledReason, Run run) {
            this.id = id;
            this.disabledReason = disabledReason;
            this.run = run;
        }
    }

    private final FormationSelection selection;
    private final FormationOrdersMenu orders;
    private final Runnable centerOnGroup;
    private final Map<Id, ResourceLocation> icons = new EnumMap<>(Id.class);
    private final List<Action> actions = new ArrayList<>();
    private int x, y, w;

    FormationActionBar(FormationSelection selection, FormationOrdersMenu orders, Runnable centerOnGroup) {
        this.selection = selection;
        this.orders = orders;
        this.centerOnGroup = centerOnGroup;
    }

    void setBounds(int x, int y, int w) {
        this.x = x;
        this.y = y;
        this.w = w;
    }

    boolean isOver(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + HEIGHT;
    }

    // ── Content ───────────────────────────────────────────────────────────────

    private void rebuild() {
        actions.clear();
        FormationLeaderInfo group = selection.group();
        if (group != null && group.uuid != null) {
            buildForGroup(group);
        } else if (selection.isFollowers() && !selection.followers.isEmpty()) {
            buildForFollowers(new ArrayList<>(selection.followers));
        }
        if (actions.isEmpty()) return;
        int n = actions.size();
        int each = (w - GAP * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            Action a = actions.get(i);
            a.x = x + i * (each + GAP);
            a.y = y;
            a.w = each;
        }
    }

    private void buildForGroup(FormationLeaderInfo group) {
        UUID src = group.uuid;
        String busy = group.busy ? I18n.format("travellers.worker.formation.menu.reason.busy") : null;
        actions.add(new Action(Id.FOLLOW_ME, busy, (bx, by) -> orders.followMe(src)));
        if (group.followActive || group.goActive) {
            actions.add(new Action(Id.HALT, busy, (bx, by) -> orders.setLeaderFollow(group, false)));
        }
        if (!group.followActive) {
            actions.add(new Action(Id.RESUME, busy, (bx, by) -> orders.setLeaderFollow(group, true)));
        }
        actions.add(new Action(Id.FOLLOW, busy, (bx, by) -> orders.openFollowChooser(group, bx, by)));
        actions.add(new Action(Id.MERGE, busy, (bx, by) -> orders.openMergeChooser(group, bx, by)));
        actions.add(new Action(Id.CENTER, null, (bx, by) -> centerOnGroup.run()));
    }

    private void buildForFollowers(List<UUID> sel) {
        String needTwo = sel.size() < 2 ? I18n.format("travellers.worker.formation.menu.reason.need_two") : null;
        actions.add(new Action(Id.FOLLOW_ME, null,
                (bx, by) -> orders.sendFollowers(MsgGroupCreate.Action.COME_BACK, sel, null)));
        actions.add(new Action(Id.CREATE_GROUP, needTwo, (bx, by) -> orders.createGroup(sel, null)));
        actions.add(new Action(Id.JOIN, null, (bx, by) -> orders.openJoinChooser(sel, bx, by)));
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    /** A click on the bar: runs the button under it (a greyed one only absorbs the click). */
    void mouseClicked(int mouseX, int mouseY) {
        rebuild();
        Action a = actionAt(mouseX, mouseY);
        if (a != null && a.disabledReason == null) a.run.run(a.x, a.y);
    }

    @Nullable
    private Action actionAt(int mouseX, int mouseY) {
        if (!isOver(mouseX, mouseY)) return null;
        for (Action a : actions) {
            if (mouseX >= a.x && mouseX < a.x + a.w) return a;
        }
        return null;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    void draw(FontRenderer fr, int mouseX, int mouseY, boolean interactive) {
        rebuild();
        Gui.drawRect(x, y - 1, x + w, y, TravellersGuiTheme.COL_BORDER);
        if (actions.isEmpty()) {
            String hint = fr.trimStringToWidth(I18n.format("travellers.worker.formation.bar.empty"), w - 8);
            fr.drawString(hint, x + 4, y + (HEIGHT - fr.FONT_HEIGHT) / 2, TravellersGuiTheme.COL_TEXT_DIM);
            return;
        }
        Action hovered = interactive ? actionAt(mouseX, mouseY) : null;
        for (Action a : actions) {
            boolean off = a.disabledReason != null;
            boolean hover = a == hovered && !off;
            int bg = off ? TravellersGuiTheme.COL_BTN_BG_OFF
                    : hover ? TravellersGuiTheme.COL_BTN_BG_HOVER : TravellersGuiTheme.COL_BTN_BG;
            int border = off ? TravellersGuiTheme.COL_BTN_BORDER_OFF : TravellersGuiTheme.COL_BORDER;
            Gui.drawRect(a.x, a.y + 1, a.x + a.w, a.y + HEIGHT - 1, bg);
            TravellersGuiTheme.drawBorder(a.x, a.y + 1, a.w, HEIGHT - 2, border);
            int textColor = off ? TravellersGuiTheme.COL_BTN_TEXT_OFF
                    : hover ? TravellersGuiTheme.COL_TEXT_HOV : TravellersGuiTheme.COL_TEXT;
            ResourceLocation icon = iconOf(a.id);
            if (icon != null) {
                GlStateManager.enableBlend();
                GlStateManager.color(1f, 1f, 1f, off ? 0.4f : 1f);
                Minecraft.getMinecraft().getTextureManager().bindTexture(icon);
                Gui.drawModalRectWithCustomSizedTexture(a.x + (a.w - ICON_SIZE) / 2, a.y + (HEIGHT - ICON_SIZE) / 2,
                        0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } else {
                String text = fr.trimStringToWidth(label(a.id), a.w - 4);
                fr.drawString(text, a.x + (a.w - fr.getStringWidth(text)) / 2, a.y + (HEIGHT - fr.FONT_HEIGHT) / 2, textColor);
            }
        }
    }

    /** Tooltip lines for the hovered button: its name, then what it does or why it is greyed. */
    @Nullable
    List<String> tooltipAt(int mouseX, int mouseY) {
        Action a = actionAt(mouseX, mouseY);
        if (a == null) return null;
        List<String> lines = new ArrayList<>();
        lines.add(label(a.id));
        lines.add(a.disabledReason != null ? a.disabledReason : I18n.format(key(a.id) + ".tip"));
        return lines;
    }

    private static String key(Id id) {
        return "travellers.worker.formation.bar." + id.name().toLowerCase();
    }

    private static String label(Id id) {
        return I18n.format(key(id));
    }

    @Nullable
    private ResourceLocation iconOf(Id id) {
        if (icons.containsKey(id)) return icons.get(id);
        ResourceLocation loc = new ResourceLocation("travellers", "textures/gui/formation_actions/" + id.name().toLowerCase() + ".png");
        ResourceLocation found = null;
        try (java.io.Closeable r = Minecraft.getMinecraft().getResourceManager().getResource(loc)) {
            found = loc;
        } catch (Exception ignored) {
            // no icon supplied: the text label is used
        }
        icons.put(id, found);
        return found;
    }
}
