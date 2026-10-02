package com.jubitus.travellers.gui.hub;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns the declarative menu tree into button positions. Two strategies, both built so that no
 * visible button ever moves while the player is using the menu:
 *
 * <ul>
 *   <li><b>Single branch</b> — one column per depth, always centered on the portrait, hosting the
 *       children of whichever branch is currently open. Opening a branch closes its siblings.
 *       Compact, fits every GUI scale.</li>
 *   <li><b>Absolute slots</b> — a tidy tree where every node keeps the position it would have if
 *       everything were expanded, so several branches can be open at once and nothing ever shifts.
 *       Needs a lot more room.</li>
 * </ul>
 *
 * The caller picks the strategy from config and falls back to single-branch when the absolute
 * layout does not fit the screen — see {@link #fitsWithin}.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubLayout {

    /** A node with the place it was assigned. */
    public static final class Placed {
        public final WorkerHubNode node;
        public final int x;
        public final int y;
        public final int depth;
        @Nullable public final WorkerHubNode parent;

        Placed(WorkerHubNode node, int x, int y, int depth, @Nullable WorkerHubNode parent) {
            this.node = node;
            this.x = x;
            this.y = y;
            this.depth = depth;
            this.parent = parent;
        }

        /**
         * A node placed with no parent in the tree at all — used for hub shortcuts, which are not
         * part of the tree shape but still draw and behave like any other placed node (see
         * {@link WorkerHubShortcutLayout}). {@code depth} is meaningless for these and left at 0.
         */
        public static Placed standalone(WorkerHubNode node, int x, int y) {
            return new Placed(node, x, y, 0, null);
        }
    }

    /**
     * Pixel sizes for one layout pass. The config values are what the player asks for; a narrow
     * screen shrinks them via {@link #compactFor}, which is what keeps the deepest column on
     * screen at every GUI scale — the default "Auto" scale on a 1080p screen is 4, which only
     * leaves 480 logical pixels of width for three columns and a portrait.
     */
    public static final class Metrics {
        private static final int MIN_COLUMN_GAP = 4;
        private static final int MIN_PORTRAIT_GAP = 4;
        private static final int MIN_PORTRAIT_SIZE = 32;
        private static final int MIN_BUTTON_WIDTH = 40;
        /** Width below which labels start losing real words; only crossed as a last resort. */
        private static final int COMFORTABLE_BUTTON_WIDTH = 56;

        public int buttonWidth;
        public final int buttonHeight;
        public final int rowGap;
        public int columnGap;
        public int portraitSize;
        public int portraitGap;

        public Metrics() {
            TravellersConfig.WorkerHub c = TravellersConfig.WORKER_HUB;
            this.buttonWidth = c.buttonWidth;
            this.buttonHeight = c.buttonHeight;
            this.rowGap = c.rowGap;
            this.columnGap = c.columnGap;
            this.portraitSize = c.portraitSize;
            this.portraitGap = c.portraitGap;
        }

        /**
         * Shrinks the gaps first, then the buttons, until a column at {@code maxDepth} still fits
         * the half-screen. Gaps go first because losing breathing room reads better than losing
         * label text.
         */
        public void compactFor(int screenWidth, int maxDepth, int margin) {
            int half = screenWidth / 2 - margin;
            // Cheapest losses first: dead space, then a little label room, and only in the last
            // resort the portrait — it is the centrepiece, not padding.
            while (overflows(half, maxDepth) && columnGap > MIN_COLUMN_GAP) columnGap--;
            while (overflows(half, maxDepth) && buttonWidth > COMFORTABLE_BUTTON_WIDTH) buttonWidth--;
            while (overflows(half, maxDepth) && portraitGap > MIN_PORTRAIT_GAP) portraitGap--;
            while (overflows(half, maxDepth) && portraitSize > MIN_PORTRAIT_SIZE) portraitSize -= 2;
            while (overflows(half, maxDepth) && buttonWidth > MIN_BUTTON_WIDTH) buttonWidth--;
        }

        private boolean overflows(int halfScreen, int maxDepth) {
            return portraitSize / 2 + portraitGap + buttonWidth + maxDepth * columnStep() > halfScreen;
        }

        int columnStep() {
            return buttonWidth + columnGap;
        }

        /** Left edge of the button column at the given depth, on the given side. */
        public int columnX(int depth, WorkerHubNode.Column column, int centerX) {
            int inner = portraitSize / 2 + portraitGap;
            return column == WorkerHubNode.Column.LEFT
                    ? centerX - inner - buttonWidth - depth * columnStep()
                    : centerX + inner + depth * columnStep();
        }
    }

    private final WorkerHubContext ctx;
    private final Set<String> openBranches;
    private final Metrics metrics;
    private final int centerX;
    private final int centerY;
    private final List<Placed> out = new ArrayList<>();

    private WorkerHubLayout(WorkerHubContext ctx, Set<String> openBranches, Metrics metrics,
                            int centerX, int centerY) {
        this.ctx = ctx;
        this.openBranches = openBranches;
        this.metrics = metrics;
        this.centerX = centerX;
        this.centerY = centerY;
    }

    public static List<Placed> compute(List<WorkerHubNode> roots, WorkerHubContext ctx,
                                       Set<String> openBranches, Metrics metrics,
                                       int centerX, int centerY, boolean absoluteSlots) {
        WorkerHubLayout layout = new WorkerHubLayout(ctx, openBranches, metrics, centerX, centerY);
        for (WorkerHubNode.Column column : WorkerHubNode.Column.values()) {
            if (absoluteSlots) {
                layout.placeTidy(roots, 0, column, centerY, null);
            } else {
                layout.placeColumn(roots, 0, column, null);
            }
        }
        return layout.out;
    }

    /** True when every placed button sits inside the screen with a small margin. */
    public static boolean fitsWithin(List<Placed> placed, Metrics metrics, int screenW, int screenH, int margin) {
        for (Placed p : placed) {
            if (p.x < margin || p.x + metrics.buttonWidth > screenW - margin) return false;
            if (p.y < margin || p.y + metrics.buttonHeight > screenH - margin) return false;
        }
        return true;
    }

    // ── Strategies ───────────────────────────────────────────────────────────

    /** One column per depth, centered on the portrait; only one branch open per side. */
    private void placeColumn(List<WorkerHubNode> nodes, int depth, WorkerHubNode.Column column,
                             @Nullable WorkerHubNode parent) {
        List<WorkerHubNode> items = visibleOf(nodes, column);
        if (items.isEmpty()) return;

        int total = items.size() * metrics.buttonHeight + (items.size() - 1) * metrics.rowGap;
        int y = centerY - total / 2;
        int x = metrics.columnX(depth, column, centerX);

        WorkerHubNode opened = null;
        for (WorkerHubNode node : items) {
            out.add(new Placed(node, x, y, depth, parent));
            if (opened == null && isOpen(node)) opened = node;
            y += metrics.buttonHeight + metrics.rowGap;
        }

        if (opened != null) placeColumn(opened.children, depth + 1, column, opened);
    }

    /** Tidy tree: each node centered on its parent, room reserved for the fully expanded tree. */
    private void placeTidy(List<WorkerHubNode> nodes, int depth, WorkerHubNode.Column column,
                           int bandCenterY, @Nullable WorkerHubNode parent) {
        List<WorkerHubNode> items = visibleOf(nodes, column);
        if (items.isEmpty()) return;

        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            total += reservedHeight(items.get(i)) + (i > 0 ? metrics.rowGap : 0);
        }

        int x = metrics.columnX(depth, column, centerX);
        int y = bandCenterY - total / 2;

        for (WorkerHubNode node : items) {
            int h = reservedHeight(node);
            int nodeCenterY = y + h / 2;
            out.add(new Placed(node, x, nodeCenterY - metrics.buttonHeight / 2, depth, parent));
            if (isOpen(node)) {
                placeTidy(node.children, depth + 1, column, nodeCenterY, node);
            }
            y += h + metrics.rowGap;
        }
    }

    /** Height a node's slot must reserve so its subtree can expand without pushing anything. */
    private int reservedHeight(WorkerHubNode node) {
        List<WorkerHubNode> children = visibleOf(node.children, node.column);
        if (children.isEmpty()) return metrics.buttonHeight;

        int total = 0;
        for (int i = 0; i < children.size(); i++) {
            total += reservedHeight(children.get(i)) + (i > 0 ? metrics.rowGap : 0);
        }
        return Math.max(metrics.buttonHeight, total);
    }

    private boolean isOpen(WorkerHubNode node) {
        return node.kind == WorkerHubNode.Kind.BRANCH && openBranches.contains(node.id);
    }

    private List<WorkerHubNode> visibleOf(List<WorkerHubNode> nodes, WorkerHubNode.Column column) {
        List<WorkerHubNode> result = new ArrayList<>(nodes.size());
        for (WorkerHubNode node : nodes) {
            if (node.column == column && node.isVisible(ctx)) result.add(node);
        }
        return result;
    }
}
