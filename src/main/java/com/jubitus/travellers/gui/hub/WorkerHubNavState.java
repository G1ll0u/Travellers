package com.jubitus.travellers.gui.hub;

import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Remembers which menu branches were open, so leaving the hub for a sub-screen and coming back
 * lands on the menu exactly as it was left.
 *
 * <p>It cannot simply live on the screen instance: the sub-screens are opened by the server
 * ({@code MsgOpenWorkerSubGui}), so coming back always builds a brand new {@code GuiWorkerHub}.
 *
 * <p>The reset rule is deliberately the simplest one that cannot go stale: the state dies the
 * moment the player is back in the world with no screen open. Navigating between screens keeps
 * it; pressing Escape anywhere drops it, so re-opening the menu from the worker always starts
 * collapsed.
 */
@SideOnly(Side.CLIENT)
public final class WorkerHubNavState {

    private static int workerId = -1;
    private static Set<String> openBranches = Collections.emptySet();

    private WorkerHubNavState() {}

    /** Called by the hub right before it hands over to another screen. */
    public static void store(int worker, Set<String> open) {
        workerId = worker;
        openBranches = new HashSet<>(open);
    }

    /** Returns the remembered branches for this worker, or an empty set. */
    public static Set<String> restore(int worker) {
        return worker == workerId ? new HashSet<>(openBranches) : new HashSet<>();
    }

    public static void clear() {
        workerId = -1;
        openBranches = Collections.emptySet();
    }

    @SubscribeEvent
    public static void onGuiOpen(GuiOpenEvent event) {
        if (event.getGui() == null) clear();
    }
}
