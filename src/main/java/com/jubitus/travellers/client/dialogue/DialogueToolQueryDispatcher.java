package com.jubitus.travellers.client.dialogue;

import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.dialogue.MsgDialogueToolQuery;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side half of the dialogue tool-call round trip (see the tools/memory plan's Task G4):
 * correlates a {@link MsgDialogueToolQuery} with its eventual {@code MsgDialogueToolResult} by a
 * locally-minted request id, and times out a request that never gets a reply (server hiccup, or the
 * GUI closed mid-flight - the pending callback is just dropped, never invoked with stale state, since
 * {@code ToolCallOrchestrator} guards each callback with its own "still relevant" check).
 */
@SideOnly(Side.CLIENT)
public final class DialogueToolQueryDispatcher {

    private static final long TIMEOUT_MS = 10_000; // server-side work here is cheap/local, no cold-model wait involved

    public interface ResultCallback {
        /** Already on the client thread. */
        void onResult(String resultText, boolean error);
    }

    private static final AtomicInteger REQUEST_COUNTER = new AtomicInteger();
    private static final Map<Integer, ResultCallback> PENDING = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Travellers-DialogueToolTimeout");
            t.setDaemon(true);
            return t;
        }
    });

    private DialogueToolQueryDispatcher() {
    }

    public static void query(int entityId, String toolName, NBTTagCompound args, ResultCallback callback) {
        int requestId = REQUEST_COUNTER.incrementAndGet();
        PENDING.put(requestId, callback);
        TravellerFollowNet.CHANNEL.sendToServer(new MsgDialogueToolQuery(requestId, entityId, toolName, args));
        TIMEOUT_EXECUTOR.schedule(() -> {
            ResultCallback pending = PENDING.remove(requestId);
            if (pending == null) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) mc.addScheduledTask(() -> pending.onResult("(no answer)", true));
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Called from {@code MsgDialogueToolResult.Handler}, already on the client thread. */
    public static void onResult(int requestId, String resultText, boolean error) {
        ResultCallback callback = PENDING.remove(requestId);
        if (callback != null) callback.onResult(resultText, error);
    }
}
