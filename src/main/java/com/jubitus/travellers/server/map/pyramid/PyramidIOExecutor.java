package com.jubitus.travellers.server.map.pyramid;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Single background thread for writing pyramid tile files to disk, so worldmap persistence
 * never blocks the server thread (unlike vanilla's synchronous, gzip-compressing
 * {@code MapStorage.saveAllData()} that {@link MapPyramidStore} deliberately no longer goes
 * through).
 *
 * Lazily (re)created rather than a fixed static singleton: on an integrated server (singleplayer),
 * quitting to title then loading a world again runs a brand new {@code IntegratedServer} in the
 * SAME JVM, but {@link #shutdownAndAwait()} permanently terminates the executor at server stop -
 * without recreation, the next world's very first autosave would submit to an already-terminated
 * executor and throw {@code RejectedExecutionException}. A dedicated server process exits after
 * stopping, so this only ever mattered for the integrated-server case.
 */
final class PyramidIOExecutor {
    private static ExecutorService exec;

    private PyramidIOExecutor() {}

    private static synchronized ExecutorService ensureRunning() {
        if (exec == null || exec.isShutdown()) {
            exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Travellers-MapIO");
                t.setDaemon(true);
                return t;
            });
        }
        return exec;
    }

    static void submit(Runnable task) {
        ensureRunning().submit(() -> {
            try {
                task.run();
            } catch (Exception ex) {
                System.err.println("[Travellers] Worldmap tile write failed: " + ex);
            }
        });
    }

    /** Waits for any in-flight writes to finish — call once, at server shutdown. */
    static synchronized void shutdownAndAwait() {
        if (exec == null) return;
        exec.shutdown();
        try {
            exec.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
