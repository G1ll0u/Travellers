package com.jubitus.travellers.gui.hub;

import com.jubitus.travellers.worker.gui.GuiWorkerEnemies;
import com.jubitus.travellers.gui.hub.WorkerHubNode.Column;
import com.jubitus.travellers.gui.hub.WorkerHubNode.Kind;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.choir.net.MsgWorkerChoirToggle;
import com.jubitus.travellers.follow.net.MsgClearEscortLeader;
import com.jubitus.travellers.follow.net.MsgMakeWorkerLeader;
import com.jubitus.travellers.follow.net.MsgToggleWorkerFollow;
import com.jubitus.travellers.follow.net.MsgStartPlayerFollow;
import com.jubitus.travellers.worker.net.MsgHireWorker;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The worker menu, declared once.
 *
 * <p><b>This is the only file to edit to reorganise the menu.</b> Display order is list order,
 * nesting is nesting, and {@link Column} decides which side of the portrait an entry lives on.
 * Children always share their parent's column. Sizes and spacing are not here — they are player
 * settings, in {@code TravellersConfig.WORKER_HUB}.
 *
 * <p>Two entries are deliberately absent: the map and the colour mark. Those are drawn under the
 * portrait by the screen itself, outside the tree.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubMenu {

    private static final List<WorkerHubNode> ROOTS = Collections.unmodifiableList(Arrays.asList(

            WorkerHubNode.branch("orders", Column.LEFT, "travellers.worker.hub.node.orders",

                    WorkerHubNode.leaf("follow", Kind.TOGGLE, Column.LEFT, "travellers.worker.hub.node.follow")
                            .tooltip("travellers.worker.orders.follow.tooltip")
                            .toggledIf(ctx -> ctx.worker.isFollowOrderActive())
                            .onClick(ctx -> ctx.send(new MsgToggleWorkerFollow(ctx.worker.getEntityId()))),

                    WorkerHubNode.leaf("follow_player", Kind.ACTION, Column.LEFT, "travellers.worker.hub.node.follow_player")
                            .tooltip("travellers.worker.orders.follow_player.tooltip")
                            .enabledIf(ctx -> ctx.isOwnedByViewer() && ctx.worker.hasEscortLeader())
                            .onClick(ctx -> ctx.send(new MsgClearEscortLeader(ctx.worker.getEntityId()))),

                    WorkerHubNode.leaf("leader", Kind.ACTION, Column.LEFT, "travellers.worker.hub.node.leader")
                            .tooltip("travellers.worker.orders.make_leader.tooltip")
                            .badge(ctx -> {
                                int count = ctx.worker.getDisplayLeaderFollowerCount();
                                return count > 0 ? String.valueOf(count) : null;
                            })
                            .onClick(ctx -> ctx.send(new MsgMakeWorkerLeader(ctx.worker.getEntityId()))),

                    WorkerHubNode.branch("go", Column.LEFT, "travellers.worker.hub.node.go",

                            WorkerHubNode.leaf("travel", Kind.SCREEN, Column.LEFT, "travellers.worker.hub.node.travel")
                                    .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.GO)),

                            WorkerHubNode.leaf("recruit", Kind.SCREEN, Column.LEFT, "travellers.worker.hub.node.recruit")
                                    .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.RECRUIT)),

                            WorkerHubNode.leaf("transport", Kind.SCREEN, Column.LEFT, "travellers.worker.hub.node.transport")
                                    .tooltip("travellers.worker.hub.transport.tooltip")
                                    .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.TRANSPORT))
                    ),

                    WorkerHubNode.leaf("choir", Kind.TOGGLE, Column.LEFT, "travellers.worker.hub.node.choir")
                            .tooltip("travellers.worker.orders.choir.tooltip")
                            .toggledIf(ctx -> ctx.worker.isWorkerChoirActive())
                            .onClick(ctx -> ctx.send(new MsgWorkerChoirToggle(ctx.worker.getEntityId()))),

                    WorkerHubNode.leaf("unhire", Kind.DANGER, Column.LEFT, "travellers.worker.hub.node.unhire")
                            .label(ctx -> I18n.format(ctx.isAwaitingConfirm("unhire")
                                    ? "travellers.worker.hub.node.unhire_confirm"
                                    : "travellers.worker.hub.node.unhire"))
                            .tooltip("travellers.worker.hub.node.unhire.tooltip")
                            .onClick(ctx -> ctx.send(new MsgHireWorker(ctx.worker.getEntityId())))
            ),

            WorkerHubNode.branch("management", Column.LEFT, "travellers.worker.hub.node.management",

                    WorkerHubNode.branch("relations", Column.LEFT, "travellers.worker.hub.node.relations",

                            WorkerHubNode.leaf("players", Kind.SCREEN, Column.LEFT, "travellers.worker.hub.node.players")
                                    .tooltip("travellers.worker.hub.node.players.tooltip")
                                    .onClick(ctx -> ctx.openClientScreen(new GuiWorkerEnemies(ctx.screen, ctx.player))),

                            WorkerHubNode.leaf("villages", Kind.SCREEN, Column.LEFT, "travellers.worker.hub.node.villages")
                                    .tooltip("travellers.worker.hub.node.villages.tooltip")
                                    .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.VILLAGE_RELATIONS))
                    ),

                    WorkerHubNode.leaf("barracks", Kind.SCREEN, Column.LEFT, "travellers.worker.hub.node.barracks")
                            .tooltip("travellers.worker.hub.barracks.tooltip")
                            .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.HOME))
            ),

            WorkerHubNode.leaf("inventory", Kind.SCREEN, Column.RIGHT, "travellers.worker.hub.node.inventory")
                    .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.INVENTORY)),

            WorkerHubNode.leaf("follow_him", Kind.ACTION, Column.RIGHT, "travellers.worker.hub.node.follow_him")
                    .tooltip("travellers.worker.hub.node.follow_him.tooltip")
                    .onClick(ctx -> {
                        ctx.send(new MsgStartPlayerFollow(ctx.worker.getEntityId()));
                        ctx.openClientScreen(null);
                    }),

            WorkerHubNode.leaf("formation", Kind.SCREEN, Column.RIGHT, "travellers.worker.hub.node.formation")
                    .tooltip("travellers.worker.hub.formation.tooltip")
                    .onClick(ctx -> ctx.openSubGui(MsgOpenWorkerSubGui.Type.FORMATION))
    ));

    private WorkerHubMenu() {}

    public static List<WorkerHubNode> roots() {
        return ROOTS;
    }

    /** Depth-first search for a node anywhere in the tree, for shortcut resolution. */
    @javax.annotation.Nullable
    public static WorkerHubNode findById(String id) {
        return findById(ROOTS, id);
    }

    @javax.annotation.Nullable
    private static WorkerHubNode findById(List<WorkerHubNode> nodes, String id) {
        for (WorkerHubNode node : nodes) {
            if (node.id.equals(id)) return node;
            WorkerHubNode found = findById(node.children, id);
            if (found != null) return found;
        }
        return null;
    }
}
