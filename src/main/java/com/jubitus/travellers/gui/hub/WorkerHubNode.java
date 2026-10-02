package com.jubitus.travellers.gui.hub;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One entry of the worker menu tree. Purely declarative: it says what the entry looks like and
 * what it does, never where it sits — {@link WorkerHubLayout} decides that from the tree shape.
 *
 * <p>The whole menu is built from these in {@link WorkerHubMenu}; reordering the menu means
 * reordering that list, nothing else.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubNode {

    public enum Kind {
        /** Opens its children in place. */
        BRANCH,
        /** Runs an action and stays on the menu. */
        ACTION,
        /** On/off order; draws its state as a dot. */
        TOGGLE,
        /** Leaves the menu for a separate screen. */
        SCREEN,
        /** Destructive action; asks for a confirmation click. */
        DANGER
    }

    public enum Column { LEFT, RIGHT }

    public final String id;
    public final Kind kind;
    public final Column column;
    public final List<WorkerHubNode> children;

    private String labelKey;
    @Nullable private Function<WorkerHubContext, String> labelFn;
    @Nullable private String tooltipKey;
    @Nullable private Function<WorkerHubContext, String> badgeFn;
    @Nullable private Consumer<WorkerHubContext> action;

    private Predicate<WorkerHubContext> visible = ctx -> true;
    private Predicate<WorkerHubContext> enabled = WorkerHubContext::isOwnedByViewer;
    private Predicate<WorkerHubContext> toggled = ctx -> false;

    private WorkerHubNode(String id, Kind kind, Column column, String labelKey, WorkerHubNode... children) {
        this.id = id;
        this.kind = kind;
        this.column = column;
        this.labelKey = labelKey;
        this.children = children.length == 0
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(Arrays.asList(children)));
    }

    public static WorkerHubNode leaf(String id, Kind kind, Column column, String labelKey) {
        return new WorkerHubNode(id, kind, column, labelKey);
    }

    public static WorkerHubNode branch(String id, Column column, String labelKey, WorkerHubNode... children) {
        return new WorkerHubNode(id, Kind.BRANCH, column, labelKey, children);
    }

    // ── Fluent configuration ─────────────────────────────────────────────────

    public WorkerHubNode label(Function<WorkerHubContext, String> fn) {
        this.labelFn = fn;
        return this;
    }

    public WorkerHubNode tooltip(String key) {
        this.tooltipKey = key;
        return this;
    }

    public WorkerHubNode badge(Function<WorkerHubContext, String> fn) {
        this.badgeFn = fn;
        return this;
    }

    public WorkerHubNode onClick(Consumer<WorkerHubContext> fn) {
        this.action = fn;
        return this;
    }

    public WorkerHubNode visibleIf(Predicate<WorkerHubContext> p) {
        this.visible = p;
        return this;
    }

    public WorkerHubNode enabledIf(Predicate<WorkerHubContext> p) {
        this.enabled = p;
        return this;
    }

    public WorkerHubNode toggledIf(Predicate<WorkerHubContext> p) {
        this.toggled = p;
        return this;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public String resolveLabel(WorkerHubContext ctx) {
        return labelFn != null ? labelFn.apply(ctx) : net.minecraft.client.resources.I18n.format(labelKey);
    }

    @Nullable
    public String resolveBadge(WorkerHubContext ctx) {
        return badgeFn != null ? badgeFn.apply(ctx) : null;
    }

    /**
     * Whether this node can ever show a badge — not whether it currently does. A shortcut sizes
     * itself to fit the worst-case badge width up front using this, since a badge's value (e.g. a
     * follower count) can grow after the shortcut button was already built.
     */
    public boolean hasBadge() {
        return badgeFn != null;
    }

    @Nullable
    public String getTooltipKey() {
        return tooltipKey;
    }

    public boolean isVisible(WorkerHubContext ctx) {
        return visible.test(ctx);
    }

    public boolean isEnabled(WorkerHubContext ctx) {
        return enabled.test(ctx);
    }

    public boolean isToggledOn(WorkerHubContext ctx) {
        return toggled.test(ctx);
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public void run(WorkerHubContext ctx) {
        if (action != null) action.accept(ctx);
    }
}
