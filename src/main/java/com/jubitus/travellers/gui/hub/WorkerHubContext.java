package com.jubitus.travellers.gui.hub;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.net.TravellerFollowNet;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.UUID;

/**
 * What a menu entry gets to look at and act on. Passed to every predicate and action of a
 * {@link WorkerHubNode}, so the tree definition stays free of screen plumbing.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubContext {

    public final EntityPlayer player;
    public final EntityTravellerWorker worker;
    public final GuiScreen screen;

    /** Id of the destructive entry currently waiting for a second click, if any. */
    @javax.annotation.Nullable
    public String pendingConfirmId;

    public WorkerHubContext(EntityPlayer player, EntityTravellerWorker worker, GuiScreen screen) {
        this.player = player;
        this.worker = worker;
        this.screen = screen;
    }

    /** True when this worker is hired by the player looking at the menu. */
    public boolean isOwnedByViewer() {
        if (worker == null || !worker.isHiredFollower()) return false;
        UUID owner = worker.getFollowOwnerId();
        return owner != null && owner.equals(player.getUniqueID());
    }

    public boolean isHiredBySomeoneElse() {
        if (worker == null || !worker.isHiredFollower()) return false;
        UUID owner = worker.getFollowOwnerId();
        return owner != null && !owner.equals(player.getUniqueID());
    }

    public boolean isFree() {
        return worker != null && !worker.isHiredFollower();
    }

    public boolean isAwaitingConfirm(String nodeId) {
        return nodeId.equals(pendingConfirmId);
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    public void send(IMessage message) {
        TravellerFollowNet.CHANNEL.sendToServer(message);
    }

    /**
     * Asks the server to open one of the worker sub-screens. The hub saves its open branches
     * before running any action, so no bookkeeping is needed here.
     */
    public void openSubGui(MsgOpenWorkerSubGui.Type type) {
        send(new MsgOpenWorkerSubGui(worker.getEntityId(), type));
    }

    /** Opens a purely client-side screen. */
    public void openClientScreen(GuiScreen target) {
        Minecraft.getMinecraft().displayGuiScreen(target);
    }
}
