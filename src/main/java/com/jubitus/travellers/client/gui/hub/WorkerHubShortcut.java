package com.jubitus.travellers.client.gui.hub;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * A user-placed duplicate of one {@link WorkerHubNode}, sitting anywhere the player dropped it
 * instead of behind its usual navigation. One per node id (enforced by {@link
 * WorkerHubShortcutStore}, which keys its map on it), shared by every worker's hub — see the menu
 * tree comment: the tree itself is identical for all of them, so there is nothing to key this on
 * per worker.
 *
 * <p>The position is stored as a fraction of the half-screen, relative to the portrait center
 * (each axis in roughly {@code -1..1}, clamped on screen at placement and again at draw time) —
 * not raw pixels — so it rescales for free with GUI scale/resolution instead of drifting or
 * ending up off-screen. See {@link WorkerHubShortcutLayout} for the conversion and the
 * best-effort "don't land on something else" placement rule.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubShortcut {

    public final String nodeId;
    public float fracX;
    public float fracY;

    public WorkerHubShortcut(String nodeId, float fracX, float fracY) {
        this.nodeId = nodeId;
        this.fracX = fracX;
        this.fracY = fracY;
    }
}
