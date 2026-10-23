package com.jubitus.travellers.gui;

import com.jubitus.travellers.village.MillMoney;
import com.jubitus.travellers.gui.hub.WorkerHubContext;
import com.jubitus.travellers.gui.hub.WorkerHubLayout;
import com.jubitus.travellers.gui.hub.WorkerHubMenu;
import com.jubitus.travellers.gui.hub.WorkerHubNavState;
import com.jubitus.travellers.gui.hub.WorkerHubNode;
import com.jubitus.travellers.gui.hub.WorkerHubShortcut;
import com.jubitus.travellers.gui.hub.WorkerHubShortcutLayout;
import com.jubitus.travellers.gui.hub.WorkerHubShortcutStore;
import com.jubitus.travellers.gui.hub.WorkerPortraitRenderer;
import com.jubitus.travellers.marker.gui.GuiColorSwatchButton;
import com.jubitus.travellers.marker.gui.GuiWorkerMarkEditor;
import com.jubitus.travellers.worldmap.gui.GuiPlayerWorldMap;
import com.jubitus.travellers.notify.client.NotificationOverlayRenderer;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.config.TravellersDerivedConfig;
import com.jubitus.travellers.unit.culture.CultureDef;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgHireWorker;
import com.jubitus.travellers.worker.net.MsgRequestServerCosts;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.millenaire.common.utilities.MillCommonUtilities;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single entry point for everything a hired henchman can do: a portrait at the center, its
 * menu tree fanned out to the left and to the right, and the two account-wide shortcuts (colour
 * mark and map) pinned under the portrait outside the tree.
 *
 * <p>Nothing here decides what the menu contains — see {@link WorkerHubMenu} — nor how big it is
 * — see {@code TravellersConfig.WORKER_HUB}. This class only wires the two together, draws them
 * and routes clicks.
 */
@SideOnly(Side.CLIENT)
public class GuiWorkerHub extends GuiScreen implements ITravellersGui {

    /** How long a destructive entry waits for its confirmation click. */
    private static final long CONFIRM_WINDOW_MS = 3000L;

    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_MARGIN = 4;

    private final EntityPlayer player;
    private final int workerId;

    private EntityTravellerWorker worker;
    private WorkerHubContext ctx;
    private WorkerHubLayout.Metrics metrics;

    private final Set<String> openBranches = new HashSet<>();
    private final List<NodeButton> nodeButtons = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final ButtonTooltipHelper tooltips = new ButtonTooltipHelper();

    private GuiColorSwatchButton markButton;
    private GuiTravellersButton mapButton;
    private GuiTravellersButton hireButton;

    // ── Shortcuts (see HubShortcutDrag) ─────────────────────────────────────
    private final List<NodeButton> shortcutButtons = new ArrayList<>();
    private final HubShortcutDrag drag = new HubShortcutDrag();

    private int centerX;
    private int centerY;

    @Nullable private String pendingConfirmId;
    private long pendingConfirmAt;

    /** Snapshot of what the layout was built from, so it is only rebuilt when it actually changed. */
    private String layoutSignature = "";

    private List<String> infoLines = java.util.Collections.emptyList();
    @Nullable private String homeVillageName;

    public GuiWorkerHub(EntityPlayer player, EntityTravellerWorker worker) {
        this.player = player;
        this.worker = worker;
        this.workerId = worker.getEntityId();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void initGui() {
        super.initGui();
        this.openBranches.clear();
        this.openBranches.addAll(WorkerHubNavState.restore(workerId));
        this.layoutSignature = "";
        refreshInfoLines();
        rebuild();
        TravellersNetwork.CHANNEL.sendToServer(new MsgRequestServerCosts());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();

        if (pendingConfirmId != null && System.currentTimeMillis() - pendingConfirmAt > CONFIRM_WINDOW_MS) {
            pendingConfirmId = null;
        }

        // The world keeps running behind the menu: the worker can die, unload or change owner.
        Entity entity = mc.world != null ? mc.world.getEntityByID(workerId) : null;
        if (!(entity instanceof EntityTravellerWorker)) {
            mc.displayGuiScreen(null);
            return;
        }
        this.worker = (EntityTravellerWorker) entity;

        refreshInfoLines();
        rebuild();
        refreshStates();
    }

    // No state is saved on close: every navigation saves it explicitly beforehand. Forge fires
    // GuiOpenEvent before onGuiClosed, so saving here would restore what Escape just wiped.

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ── Building ─────────────────────────────────────────────────────────────

    /** Rebuilds the buttons only when the tree shape, the metrics or the ownership changed. */
    private void rebuild() {
        this.ctx = new WorkerHubContext(player, worker, this);
        this.ctx.pendingConfirmId = pendingConfirmId;
        this.metrics = new WorkerHubLayout.Metrics();
        this.metrics.compactFor(this.width, maxTreeDepth(), 4);

        String signature = openBranches.toString() + '|' + width + 'x' + height
                + '|' + metrics.buttonWidth + ',' + metrics.buttonHeight + ',' + metrics.rowGap
                + ',' + metrics.columnGap + ',' + metrics.portraitSize + ',' + metrics.portraitGap
                + '|' + TravellersConfig.WORKER_HUB.multiBranchExpand
                + '|' + ctx.isOwnedByViewer() + ctx.isFree();
        if (signature.equals(layoutSignature)) return;
        this.layoutSignature = signature;

        this.centerX = this.width / 2;
        this.centerY = this.height / 2;

        this.buttonList.clear();
        this.nodeButtons.clear();
        this.links.clear();
        this.shortcutButtons.clear();
        this.tooltips.clear();
        this.markButton = null;
        this.mapButton = null;
        this.hireButton = null;

        if (ctx.isOwnedByViewer()) {
            buildTree();
        }
        buildPortraitButtons();
        if (ctx.isOwnedByViewer()) {
            // After the portrait row: it needs the mark/map button rects as obstacles too.
            buildShortcuts();
        }
        refreshStates();
    }

    private void buildTree() {
        boolean absolute = TravellersConfig.WORKER_HUB.multiBranchExpand;

        // Absolute slots reserve room for the fully expanded tree even while branches are closed,
        // so the fit test has to be run against that worst case — not against what is on screen
        // right now, which would pass and then overflow on the first click.
        if (absolute) {
            List<WorkerHubLayout.Placed> worstCase = WorkerHubLayout.compute(
                    WorkerHubMenu.roots(), ctx, allBranchIds(), metrics, centerX, centerY, true);
            if (!WorkerHubLayout.fitsWithin(worstCase, metrics, width, height, 2)) {
                closeAllButFirstOpenBranch();
                absolute = false;
            }
        }

        List<WorkerHubLayout.Placed> placed = layout(absolute);

        int id = 0;
        for (WorkerHubLayout.Placed p : placed) {
            NodeButton button = new NodeButton(id++, p.x, p.y, metrics.buttonWidth, metrics.buttonHeight, p);
            button.style(styleOf(p.node));
            nodeButtons.add(button);
            addButton(button);
            if (p.node.getTooltipKey() != null) {
                tooltips.set(button, p.node.getTooltipKey());
            }
        }
        buildLinks();
    }

    private List<WorkerHubLayout.Placed> layout(boolean absoluteSlots) {
        return WorkerHubLayout.compute(WorkerHubMenu.roots(), ctx, openBranches, metrics,
                centerX, centerY, absoluteSlots);
    }

    /** Deepest column index the tree can ever reach, so the metrics can be sized for it. */
    private static int maxTreeDepth() {
        return depthOf(WorkerHubMenu.roots()) - 1;
    }

    private static int depthOf(List<WorkerHubNode> nodes) {
        int deepest = 0;
        for (WorkerHubNode node : nodes) {
            if (node.hasChildren()) deepest = Math.max(deepest, depthOf(node.children));
        }
        return deepest + 1;
    }

    private Set<String> allBranchIds() {
        Set<String> ids = new HashSet<>();
        collectBranchIds(WorkerHubMenu.roots(), ids);
        return ids;
    }

    private void collectBranchIds(List<WorkerHubNode> nodes, Set<String> out) {
        for (WorkerHubNode node : nodes) {
            if (node.kind == WorkerHubNode.Kind.BRANCH) out.add(node.id);
            collectBranchIds(node.children, out);
        }
    }

    /** Absolute-slot layouts that do not fit degrade to the single-branch rule. */
    private void closeAllButFirstOpenBranch() {
        String keep = null;
        for (WorkerHubNode root : WorkerHubMenu.roots()) {
            if (openBranches.contains(root.id)) {
                keep = root.id;
                break;
            }
        }
        Set<String> kept = new HashSet<>(openBranches);
        openBranches.clear();
        if (keep != null) {
            openBranches.add(keep);
            // A sub-branch of the kept branch stays open; siblings of other roots do not.
            for (WorkerHubNode child : findNode(keep).children) {
                if (kept.contains(child.id)) openBranches.add(child.id);
            }
        }
    }

    /**
     * Places a themed duplicate of each stored shortcut, wherever the player dropped it. The
     * button behaves exactly like its tree counterpart — same {@link NodeButton} class, same
     * {@code actionPerformed} routing — so clicking, toggling and the danger-confirm dance all
     * come for free.
     *
     * <p>Placement is free-form: nothing is reserved in advance. Each shortcut is only nudged away
     * from whatever is <em>currently</em> visible (portrait, identity text, open tree buttons,
     * shortcuts already placed this pass) if it actually lands on top of it — see {@link
     * WorkerHubShortcutLayout}. The nudge is display-only and never rewrites the stored anchor, so
     * a shortcut pushed aside because a branch happened to be open returns to its real spot the
     * moment that branch closes again.
     */
    private void buildShortcuts() {
        if (!TravellersConfig.WORKER_HUB.enableShortcuts) return;

        List<WorkerHubShortcutLayout.Rect> obstacles = collectObstacleRects();

        int id = 2000;
        for (WorkerHubShortcut s : WorkerHubShortcutStore.all()) {
            // Hidden at its old spot while it is the one being dragged, so the ghost following the
            // cursor doesn't read as a second copy.
            if (drag.active && s.nodeId.equals(drag.nodeId)) continue;

            WorkerHubNode node = WorkerHubMenu.findById(s.nodeId);
            if (node == null || node.kind == WorkerHubNode.Kind.BRANCH || !node.isVisible(ctx)) continue;

            int[] size = shortcutSize(node);
            int bw = size[0];
            int bh = size[1];
            int cx = WorkerHubShortcutLayout.pixelCenterX(s.fracX, centerX, width);
            int cy = WorkerHubShortcutLayout.pixelCenterY(s.fracY, centerY, height);
            int[] pos = WorkerHubShortcutLayout.findNearbyFree(cx, cy, bw, bh, obstacles, width, height);

            NodeButton button = new NodeButton(id++, pos[0], pos[1], bw, bh,
                    WorkerHubLayout.Placed.standalone(node, pos[0], pos[1]));
            button.style(styleOf(node));
            // Already reads as "opens something else" from being a compact shortcut; the arrow the
            // tree uses to say the same thing would just be clutter here.
            button.hideScreenArrow(true);
            shortcutButtons.add(button);
            addButton(button);
            if (node.getTooltipKey() != null) {
                tooltips.set(button, node.getTooltipKey());
            }

            // Two shortcuts must not land on each other either.
            obstacles.add(new WorkerHubShortcutLayout.Rect(pos[0], pos[1], bw, bh));
        }
    }

    /** Widest badge this shortcut will ever need to fit, so it never has to grow later. */
    private static final String BADGE_WORST_CASE = "999";

    /**
     * A shortcut is sized to its own label instead of borrowing the tree's fixed button width —
     * both so it visibly reads as a shortcut rather than a tree button that wandered off, and so a
     * screenful of them doesn't eat more room than the words on them need. Never wider than a tree
     * button (a shortcut is meant to be the compact form), and a bit shorter than one.
     *
     * <p>A badge is sized for its worst case ({@link #BADGE_WORST_CASE}), not its value right now:
     * the button is only rebuilt when the tree shape changes (see {@code rebuild}'s signature), so
     * a value that keeps climbing after that — a follower count, say — would otherwise outgrow a
     * width picked for whatever it happened to be at build time and start clipping. The trailing
     * "&gt;" a tree {@code SCREEN} button reserves room for is skipped entirely: shortcuts never
     * draw it (see {@code hideScreenArrow}).
     */
    private int[] shortcutSize(WorkerHubNode node) {
        int textW = fontRenderer.getStringWidth(node.resolveLabel(ctx));
        int extra = 0;
        if (node.kind == WorkerHubNode.Kind.TOGGLE) extra += 6; // state dot
        if (node.hasBadge()) extra += fontRenderer.getStringWidth(BADGE_WORST_CASE) + 4;

        int w = MathHelper.clamp(textW + extra + 10, 32, metrics.buttonWidth);
        int h = Math.max(12, metrics.buttonHeight - 6);
        return new int[]{w, h};
    }

    /** Everything currently on screen a new shortcut should avoid landing on top of. */
    private List<WorkerHubShortcutLayout.Rect> collectObstacleRects() {
        List<WorkerHubShortcutLayout.Rect> rects = new ArrayList<>();

        int half = metrics.portraitSize / 2 + 3;
        rects.add(new WorkerHubShortcutLayout.Rect(centerX - half, centerY - half, half * 2, half * 2));

        // Name + info lines: real drawn text, not buttons, so measured rather than guessed.
        int textTop = centerY + metrics.portraitSize / 2 + 4;
        int textBottom = centerY + metrics.portraitSize / 2 + 6 + LINE_HEIGHT * (1 + infoLines().size());
        int textW = measureIdentityWidth() + 8;
        rects.add(new WorkerHubShortcutLayout.Rect(centerX - textW / 2, textTop, textW, textBottom - textTop));

        for (NodeButton button : nodeButtons) if (button.visible) rects.add(rectOf(button));
        if (markButton != null) rects.add(rectOf(markButton));
        if (mapButton != null) rects.add(rectOf(mapButton));
        if (hireButton != null) rects.add(rectOf(hireButton));
        return rects;
    }

    private static WorkerHubShortcutLayout.Rect rectOf(GuiButton button) {
        return new WorkerHubShortcutLayout.Rect(button.x, button.y, button.width, button.height);
    }

    /** Widest line of the name/culture/health/village block, so the reserved rect isn't a guess. */
    private int measureIdentityWidth() {
        int w = fontRenderer.getStringWidth(worker.getName());
        for (String line : infoLines()) {
            w = Math.max(w, fontRenderer.getStringWidth(line));
        }
        return w;
    }

    private void buildPortraitButtons() {
        int smallH = Math.max(12, metrics.buttonHeight - 6);
        int mapW = Math.max(30, metrics.buttonWidth / 2);
        int total = ctx.isOwnedByViewer() ? smallH + 4 + mapW : metrics.buttonWidth;
        int rowY = pushBelowColumns(detachedRowY(), total, smallH);

        if (ctx.isOwnedByViewer()) {
            int left = centerX - total / 2;

            this.markButton = new GuiColorSwatchButton(900, left, rowY, smallH, smallH);
            addButton(this.markButton);

            this.mapButton = new GuiTravellersButton(901, left + smallH + 4, rowY, mapW, smallH,
                    net.minecraft.client.resources.I18n.format("travellers.worker.hub.map"));
            addButton(this.mapButton);

            tooltips.set(this.markButton, "travellers.worker.hub.mark_button.desc");
        } else {
            int w = metrics.buttonWidth;
            this.hireButton = new GuiTravellersButton(902, centerX - w / 2, rowY, w, metrics.buttonHeight, "");
            addButton(this.hireButton);
        }
    }

    /** Y of the row under the portrait, below the name and the info lines. */
    private int detachedRowY() {
        return centerY + metrics.portraitSize / 2 + 6 + LINE_HEIGHT * (1 + infoLines().size()) + 4;
    }

    /**
     * Moves the row down until it has the horizontal room it needs. The columns reach further down
     * than the portrait does, so on a tall tree the natural spot would sit right between two menu
     * buttons.
     */
    private int pushBelowColumns(int y, int neededWidth, int rowHeight) {
        int limit = this.height - rowHeight - 4;
        while (y < limit && freeWidthAt(y, rowHeight) < neededWidth) {
            y += rowHeight;
        }
        return Math.min(y, limit);
    }

    private WorkerHubNode findNode(String id) {
        for (WorkerHubNode root : WorkerHubMenu.roots()) {
            if (root.id.equals(id)) return root;
        }
        return WorkerHubMenu.roots().get(0);
    }

    private static GuiTravellersButton.Style styleOf(WorkerHubNode node) {
        switch (node.kind) {
            case BRANCH: return GuiTravellersButton.Style.BRANCH;
            case TOGGLE: return GuiTravellersButton.Style.TOGGLE;
            case SCREEN: return GuiTravellersButton.Style.SCREEN;
            case DANGER: return GuiTravellersButton.Style.DANGER;
            default:     return GuiTravellersButton.Style.NORMAL;
        }
    }

    // ── Per-tick state ───────────────────────────────────────────────────────

    private void refreshStates() {
        ctx.pendingConfirmId = pendingConfirmId;

        for (NodeButton button : nodeButtons) {
            refreshNodeButton(button);
            button.expanded(openBranches.contains(button.placed.node.id));
        }
        for (NodeButton button : shortcutButtons) {
            refreshNodeButton(button);
        }

        if (markButton != null) {
            markButton.setColor(worker.getOwnerMarkColor());
        }

        if (hireButton != null) {
            if (ctx.isFree()) {
                int cost = TravellersDerivedConfig.workerHireCostDeniersServerCache;
                int count = worker.clientHireGroupSize;
                String price = cost >= 0 ? MillMoney.format(cost * count) : "?";
                hireButton.displayString = count > 1
                        ? net.minecraft.client.resources.I18n.format("travellers.worker.orders.hire_group", count, price)
                        : net.minecraft.client.resources.I18n.format("travellers.worker.orders.hire", price);
                hireButton.enabled = true;
            } else {
                hireButton.displayString = net.minecraft.client.resources.I18n.format("travellers.worker.orders.hired_other");
                hireButton.enabled = false;
            }
        }
    }

    /** Shared per-tick refresh for a tree button or a shortcut duplicate of the same node. */
    private void refreshNodeButton(NodeButton button) {
        WorkerHubNode node = button.placed.node;
        button.displayString = node.resolveLabel(ctx);
        button.enabled = node.isEnabled(ctx);
        button.toggled(node.isToggledOn(ctx));
        button.badge(node.resolveBadge(ctx));
    }

    // ── Input ────────────────────────────────────────────────────────────────

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == mapButton) {
            WorkerHubNavState.store(workerId, openBranches);
            mc.displayGuiScreen(new GuiPlayerWorldMap(player, workerId));
            return;
        }
        if (button == markButton) {
            WorkerHubNavState.store(workerId, openBranches);
            mc.displayGuiScreen(new GuiWorkerMarkEditor(this, player, worker));
            return;
        }
        if (button == hireButton) {
            TravellersNetwork.CHANNEL.sendToServer(new MsgHireWorker(worker));
            return;
        }
        if (button instanceof NodeButton) {
            onNodeClicked(((NodeButton) button).placed.node);
            return;
        }
        super.actionPerformed(button);
    }

    private void onNodeClicked(WorkerHubNode node) {
        if (node.kind == WorkerHubNode.Kind.BRANCH) {
            toggleBranch(node);
            return;
        }

        if (node.kind == WorkerHubNode.Kind.DANGER && !node.id.equals(pendingConfirmId)) {
            pendingConfirmId = node.id;
            pendingConfirmAt = System.currentTimeMillis();
            refreshStates();
            return;
        }
        pendingConfirmId = null;

        // Saved before the action, because opening a sub-screen replaces this screen immediately.
        WorkerHubNavState.store(workerId, openBranches);
        node.run(ctx);
    }

    private void toggleBranch(WorkerHubNode node) {
        if (openBranches.remove(node.id)) {
            closeDescendants(node);
        } else {
            if (!TravellersConfig.WORKER_HUB.multiBranchExpand) {
                for (WorkerHubNode sibling : siblingsOf(node)) {
                    if (openBranches.remove(sibling.id)) closeDescendants(sibling);
                }
            }
            openBranches.add(node.id);
        }
        rebuild();
    }

    private void closeDescendants(WorkerHubNode node) {
        for (WorkerHubNode child : node.children) {
            openBranches.remove(child.id);
            closeDescendants(child);
        }
    }

    private List<WorkerHubNode> siblingsOf(WorkerHubNode node) {
        for (NodeButton button : nodeButtons) {
            if (button.placed.node == node) {
                WorkerHubNode parent = button.placed.parent;
                return parent != null ? parent.children : WorkerHubMenu.roots();
            }
        }
        return WorkerHubMenu.roots();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 1 && TravellersConfig.WORKER_HUB.enableShortcuts
                && ctx != null && ctx.isOwnedByViewer() && tryStartShortcutDrag(mouseX, mouseY)) {
            return;
        }

        // Clicking the portrait collapses everything — a predictable way back to the root.
        int half = metrics.portraitSize / 2;
        if (mouseButton == 0
                && mouseX >= centerX - half && mouseX <= centerX + half
                && mouseY >= centerY - half && mouseY <= centerY + half) {
            if (!openBranches.isEmpty()) {
                openBranches.clear();
                rebuild();
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (drag.active && clickedMouseButton == 1) {
            drag.trackMovement(mouseX, mouseY);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (drag.active && state == 1) {
            finishShortcutDrag(mouseX, mouseY);
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    // ── Shortcut drag & drop ────────────────────────────────────────────────

    /**
     * Right-press on a tree leaf starts creating a shortcut for it (relocating it if one already
     * exists); right-press on an existing shortcut starts moving it. Branches are refused outright
     * — they only ever mean "open my children", which has nothing to duplicate. Returns whether the
     * press was ours to handle, so the caller knows not to fall through to normal click handling.
     */
    private boolean tryStartShortcutDrag(int mouseX, int mouseY) {
        NodeButton hit = hitTest(shortcutButtons, mouseX, mouseY);
        if (hit != null) {
            float[] grabFrac = grabFracOf(hit, mouseX, mouseY);
            drag.start(hit.placed.node.id, true, mouseX, mouseY, grabFrac[0], grabFrac[1]);
            forceRebuild(); // hides the shortcut at its old spot for the duration of the drag
            return true;
        }
        hit = hitTest(nodeButtons, mouseX, mouseY);
        if (hit == null) return false;
        if (hit.placed.node.kind != WorkerHubNode.Kind.BRANCH) {
            String nodeId = hit.placed.node.id;
            float[] grabFrac = grabFracOf(hit, mouseX, mouseY);
            drag.start(nodeId, false, mouseX, mouseY, grabFrac[0], grabFrac[1]);
            // Dragging a tree button that already has a shortcut just relocates that shortcut —
            // hide it at its old spot too, exactly like starting the drag from the shortcut itself.
            if (WorkerHubShortcutStore.has(nodeId)) forceRebuild();
        }
        return true; // swallow the press either way: it landed on a menu button, not blank space
    }

    /** Where inside {@code button} the press landed, as a 0..1 fraction of its width/height. */
    private static float[] grabFracOf(NodeButton button, int mouseX, int mouseY) {
        return new float[]{
                (mouseX - button.x) / (float) button.width,
                (mouseY - button.y) / (float) button.height
        };
    }

    private void finishShortcutDrag(int mouseX, int mouseY) {
        String nodeId = drag.nodeId;
        boolean startedOnShortcut = drag.startedOnShortcut;
        boolean moved = drag.moved;
        float grabFracX = drag.grabFracX;
        float grabFracY = drag.grabFracY;
        drag.reset();

        if (!moved) {
            // A plain right-click, no drag: deletes an existing shortcut, does nothing on a tree
            // button (creation needs an actual "somewhere else" to drop onto).
            if (startedOnShortcut) WorkerHubShortcutStore.remove(nodeId);
        } else {
            WorkerHubNode node = WorkerHubMenu.findById(nodeId);
            if (node != null) {
                // The dragged shortcut is excluded from buildShortcuts() while dragging, so
                // obstacles here already reflect everything except it.
                List<WorkerHubShortcutLayout.Rect> obstacles = collectObstacleRects();
                for (NodeButton button : shortcutButtons) obstacles.add(rectOf(button));

                int[] size = shortcutSize(node);
                int bw = size[0];
                int bh = size[1];
                // Same corner of the button that was grabbed at press time, not its center.
                int dropX = mouseX - Math.round(grabFracX * bw);
                int dropY = mouseY - Math.round(grabFracY * bh);
                int[] pos = WorkerHubShortcutLayout.findNearbyFree(
                        dropX + bw / 2, dropY + bh / 2, bw, bh, obstacles, width, height);
                float fracX = WorkerHubShortcutLayout.fracXOf(pos[0] + bw / 2, centerX, width);
                float fracY = WorkerHubShortcutLayout.fracYOf(pos[1] + bh / 2, centerY, height);
                WorkerHubShortcutStore.put(nodeId, fracX, fracY);
            }
        }

        // Always rebuild: this both picks up any store change above and, on a cancelled drag,
        // brings the shortcut that was hidden for the duration of it back into view.
        forceRebuild();
    }

    /** Invalidates the cached layout signature so the next {@link #rebuild()} actually rebuilds. */
    private void forceRebuild() {
        this.layoutSignature = "";
        rebuild();
    }

    @Nullable
    private static NodeButton hitTest(List<NodeButton> buttons, int mouseX, int mouseY) {
        for (NodeButton button : buttons) {
            if (button.visible && mouseX >= button.x && mouseX < button.x + button.width
                    && mouseY >= button.y && mouseY < button.y + button.height) {
                return button;
            }
        }
        return null;
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        if (TravellersConfig.WORKER_HUB.showLinks) {
            drawLinks(mouseX, mouseY);
        }

        WorkerPortraitRenderer.draw(worker,
                centerX - metrics.portraitSize / 2,
                centerY - metrics.portraitSize / 2,
                metrics.portraitSize, mouseX, mouseY);

        drawIdentity();

        super.drawScreen(mouseX, mouseY, partialTicks);

        if (drag.active) drawDragGhost(mouseX, mouseY);

        tooltips.render(mouseX, mouseY, this.width, this.height);
    }

    /** Name and info lines, trimmed to whatever horizontal room the columns leave them. */
    private void drawIdentity() {
        int y = centerY + metrics.portraitSize / 2 + 6;

        String name = worker.getName();
        int room = freeWidthAt(y);
        TravellersGuiTheme.drawCenteredText(fontRenderer,
                TravellersGuiTheme.trimLabel(fontRenderer, name, room),
                centerX, y, TravellersGuiTheme.COL_TEXT);
        y += LINE_HEIGHT;

        for (String line : infoLines()) {
            room = freeWidthAt(y);
            TravellersGuiTheme.drawCenteredText(fontRenderer,
                    TravellersGuiTheme.trimLabel(fontRenderer, line, room),
                    centerX, y, TravellersGuiTheme.COL_TEXT_DIM);
            y += LINE_HEIGHT;
        }
    }

    private List<String> infoLines() {
        return infoLines;
    }

    /**
     * Rebuilt once per tick, never per frame: resolving the home village walks Millenaire's whole
     * village list, which has no business running at frame rate.
     */
    private void refreshInfoLines() {
        if (!TravellersConfig.WORKER_HUB.showInfoLines) {
            infoLines = java.util.Collections.emptyList();
            return;
        }

        List<String> lines = new ArrayList<>(3);

        CultureDef culture = worker.getCulture();
        if (culture != null && culture.displayName != null) {
            lines.add(culture.displayName);
        }

        lines.add(net.minecraft.client.resources.I18n.format("travellers.worker.hub.info.health",
                (int) Math.ceil(worker.getHealth()), (int) worker.getMaxHealth()));

        if (homeVillageName == null) homeVillageName = resolveHomeVillageName();
        if (!homeVillageName.isEmpty()) {
            lines.add(net.minecraft.client.resources.I18n.format("travellers.worker.hub.info.village", homeVillageName));
        }
        infoLines = lines;
    }

    /** Empty string means "resolved, and there is none" — so it is only looked up once. */
    private String resolveHomeVillageName() {
        return worker.clientHomeVillageName != null ? worker.clientHomeVillageName : "";
    }

    /**
     * Horizontal room left for centered text on the given line: the gap between the innermost
     * button of each column that overlaps this line. Village and villager names vary a lot in
     * length, so the text is trimmed to the actual free space rather than to a guessed constant.
     */
    private int freeWidthAt(int lineY) {
        return freeWidthAt(lineY, LINE_HEIGHT);
    }

    private int freeWidthAt(int lineY, int lineHeight) {
        int leftLimit = 0;
        int rightLimit = this.width;

        for (NodeButton button : nodeButtons) {
            leftLimit = Math.max(leftLimit, buttonEncroachLeft(button, lineY, lineHeight, leftLimit));
            rightLimit = Math.min(rightLimit, buttonEncroachRight(button, lineY, lineHeight, rightLimit));
        }
        for (NodeButton button : shortcutButtons) {
            leftLimit = Math.max(leftLimit, buttonEncroachLeft(button, lineY, lineHeight, leftLimit));
            rightLimit = Math.min(rightLimit, buttonEncroachRight(button, lineY, lineHeight, rightLimit));
        }

        // The vertical run of an elbow counts too: with several branches open at once, a link can
        // come down the corridor right beside the portrait, exactly where a long name reaches.
        for (Link link : links) {
            leftLimit = Math.max(leftLimit, linkEncroachLeft(link, lineY, lineHeight, leftLimit));
            rightLimit = Math.min(rightLimit, linkEncroachRight(link, lineY, lineHeight, rightLimit));
        }

        int half = Math.min(centerX - leftLimit, rightLimit - centerX) - TEXT_MARGIN;
        return Math.max(20, half * 2);
    }

    private int buttonEncroachLeft(NodeButton button, int lineY, int lineHeight, int current) {
        if (!button.visible) return current;
        boolean overlaps = lineY + lineHeight > button.y && lineY < button.y + button.height;
        int buttonRight = button.x + button.width;
        return overlaps && buttonRight <= centerX ? Math.max(current, buttonRight) : current;
    }

    private int buttonEncroachRight(NodeButton button, int lineY, int lineHeight, int current) {
        if (!button.visible) return current;
        boolean overlaps = lineY + lineHeight > button.y && lineY < button.y + button.height;
        return overlaps && button.x >= centerX ? Math.min(current, button.x) : current;
    }

    private int linkEncroachLeft(Link link, int lineY, int lineHeight, int current) {
        boolean overlaps = lineY + lineHeight > Math.min(link.childY, link.parentY)
                && lineY < Math.max(link.childY, link.parentY);
        return overlaps && link.midX <= centerX ? Math.max(current, link.midX) : current;
    }

    private int linkEncroachRight(Link link, int lineY, int lineHeight, int current) {
        boolean overlaps = lineY + lineHeight > Math.min(link.childY, link.parentY)
                && lineY < Math.max(link.childY, link.parentY);
        return overlaps && link.midX > centerX ? Math.min(current, link.midX) : current;
    }

    /** One elbow per parent-child pair, so the eye can follow which button opened which. */
    private void buildLinks() {
        links.clear();
        for (NodeButton button : nodeButtons) {
            WorkerHubLayout.Placed p = button.placed;
            boolean left = p.node.column == WorkerHubNode.Column.LEFT;

            int childX = left ? p.x + metrics.buttonWidth : p.x;
            int childY = p.y + metrics.buttonHeight / 2;

            int parentX;
            int parentY;
            if (p.parent != null) {
                NodeButton parentButton = buttonFor(p.parent);
                if (parentButton == null) continue;
                parentX = left ? parentButton.x : parentButton.x + parentButton.width;
                parentY = parentButton.y + parentButton.height / 2;
            } else {
                parentX = centerX + (left ? -metrics.portraitSize / 2 : metrics.portraitSize / 2);
                parentY = centerY;
            }
            links.add(new Link(p.node, childX, childY, (childX + parentX) / 2, parentX, parentY));
        }
    }

    /**
     * Drawn in two passes, dim first. Every child of the same parent shares the elbow segment that
     * reaches that parent, so a single pass lets whichever link happens to be drawn last paint over
     * a highlighted one — which is why the segment touching an open branch used to stay dim.
     */
    private void drawLinks(int mouseX, int mouseY) {
        for (Link link : links) {
            if (!isLinkLit(link, mouseX, mouseY)) drawLink(link, TravellersGuiTheme.COL_LINK);
        }
        for (Link link : links) {
            if (isLinkLit(link, mouseX, mouseY)) drawLink(link, TravellersGuiTheme.COL_LINK_OPEN);
        }
    }

    /** Lit when the entry it leads to is open, or hovered right now. */
    private boolean isLinkLit(Link link, int mouseX, int mouseY) {
        if (openBranches.contains(link.node.id)) return true;
        NodeButton button = buttonFor(link.node);
        return button != null && button.visible && button.enabled
                && mouseX >= button.x && mouseX < button.x + button.width
                && mouseY >= button.y && mouseY < button.y + button.height;
    }

    /**
     * A translucent copy of the dragged button following the cursor exactly — free placement, no
     * grid to snap a preview to. Tinted green when releasing here would land clean, orange when
     * the drop point is currently on top of something and would get nudged aside on release.
     */
    private void drawDragGhost(int mouseX, int mouseY) {
        WorkerHubNode node = WorkerHubMenu.findById(drag.nodeId);
        if (node == null) return;

        int[] size = shortcutSize(node);
        int bw = size[0];
        int bh = size[1];
        int gx = mouseX - Math.round(drag.grabFracX * bw);
        int gy = mouseY - Math.round(drag.grabFracY * bh);

        List<WorkerHubShortcutLayout.Rect> obstacles = collectObstacleRects();
        for (NodeButton button : shortcutButtons) obstacles.add(rectOf(button));
        boolean clear = !WorkerHubShortcutLayout.overlapsAny(gx, gy, bw, bh, obstacles);
        int color = clear ? TravellersGuiTheme.COL_ACTIVE : TravellersGuiTheme.COL_TEXT_HOV;

        TravellersGuiTheme.drawButtonBox(gx, gy, bw, bh, 0x992A2219, color);
        String label = TravellersGuiTheme.trimLabel(fontRenderer, node.resolveLabel(ctx), bw - 8);
        TravellersGuiTheme.drawCenteredText(fontRenderer, label,
                gx + bw / 2, gy + (bh - 8) / 2, TravellersGuiTheme.COL_TEXT);
    }

    private void drawLink(Link link, int color) {
        TravellersGuiTheme.drawHLine(link.childX, link.midX, link.childY, color);
        // Gui.drawRect excludes its far edge, so the elbow corner pixel at (midX, childY) is only
        // ever covered by one of the two segments meeting there, depending on which way the link
        // runs. Extending the vertical run one pixel past its far end guarantees it always covers
        // that corner instead of leaving a hole on links running one particular direction.
        int vFar = Math.max(link.childY, link.parentY) + 1;
        int vNear = Math.min(link.childY, link.parentY);
        TravellersGuiTheme.drawVLine(link.midX, vNear, vFar, color);
        TravellersGuiTheme.drawHLine(link.midX, link.parentX, link.parentY, color);
    }

    /** The elbow joining a menu entry to the one that opened it. */
    private static final class Link {
        final WorkerHubNode node;
        final int childX;
        final int childY;
        final int midX;
        final int parentX;
        final int parentY;

        Link(WorkerHubNode node, int childX, int childY, int midX, int parentX, int parentY) {
            this.node = node;
            this.childX = childX;
            this.childY = childY;
            this.midX = midX;
            this.parentX = parentX;
            this.parentY = parentY;
        }
    }

    @Nullable
    private NodeButton buttonFor(WorkerHubNode node) {
        for (NodeButton button : nodeButtons) {
            if (button.placed.node == node) return button;
        }
        return null;
    }

    /**
     * Right-click-drag state for a shortcut being created, moved or deleted. Deliberately doesn't
     * track a live cursor position — {@link #drawDragGhost} and {@link #finishShortcutDrag} both
     * already receive it fresh from {@code drawScreen}/{@code mouseReleased} each frame, so storing
     * a second copy here would just be one more place for it to go stale.
     */
    private static final class HubShortcutDrag {

        boolean active;
        String nodeId;
        /** Press landed on an existing shortcut rather than its tree button. */
        boolean startedOnShortcut;
        boolean moved;
        /**
         * Where inside the source button the press landed, as a fraction of its width/height —
         * not a raw pixel offset, since the source button (a tree button, full-width) and the
         * resulting shortcut (sized to its own label) can be different sizes. Grabbing a corner
         * keeps grabbing that same corner of the ghost either way.
         */
        float grabFracX, grabFracY;
        private int pressX, pressY;

        void start(String nodeId, boolean startedOnShortcut, int x, int y, float grabFracX, float grabFracY) {
            this.active = true;
            this.nodeId = nodeId;
            this.startedOnShortcut = startedOnShortcut;
            this.moved = false;
            this.grabFracX = grabFracX;
            this.grabFracY = grabFracY;
            this.pressX = x;
            this.pressY = y;
        }

        /**
         * No pixel threshold: {@code mouseClickMove} is only ever invoked by vanilla when LWJGL
         * reports an actual mouse-move event, which a plain, stationary click never generates in
         * the first place — a fixed threshold instead swallowed small deliberate nudges as a
         * "click", deleting the shortcut being nudged instead of moving it.
         */
        void trackMovement(int x, int y) {
            if (!moved && (x != pressX || y != pressY)) moved = true;
        }

        void reset() {
            active = false;
            nodeId = null;
        }
    }

    /** A themed button that knows which menu entry it stands for. */
    private static final class NodeButton extends GuiTravellersButton {
        final WorkerHubLayout.Placed placed;

        NodeButton(int id, int x, int y, int w, int h, WorkerHubLayout.Placed placed) {
            super(id, x, y, w, h, "");
            this.placed = placed;
        }
    }
}
