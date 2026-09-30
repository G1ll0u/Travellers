package com.jubitus.travellers.client.dialogue;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Optional, player-opted-in warm-up of the configured Ollama model, fired once as early as possible
 * during client startup ({@code FMLLoadCompleteEvent}, before the main menu even shows) — see
 * {@code Travellers.loadComplete}. Purely a convenience: without it, the first real dialogue of a
 * session pays the model's full cold-load time (the same load {@link OllamaClient#CHAT_READ_TIMEOUT_MS}
 * is sized to tolerate) instead of hitting an already-warm model.
 */
@SideOnly(Side.CLIENT)
public final class DialogueModelPreloader {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Dialogue");

    private DialogueModelPreloader() {
    }

    public static void preloadIfEnabled() {
        if (!DialogueClientSettings.isConfigured() || !DialogueClientSettings.isPreloadOnStartup()) return;

        String host = DialogueClientSettings.getHost();
        int port = DialogueClientSettings.getPort();
        String model = DialogueClientSettings.getModel();
        LOGGER.info("Preloading Ollama model '{}' at {}:{}...", model, host, port);
        OllamaClient.preload(host, port, model, new OllamaClient.PreloadCallback() {
            @Override
            public void onLoaded() {
                LOGGER.info("Ollama model '{}' preloaded.", model);
            }

            @Override
            public void onError(OllamaClient.Outcome outcome, String message) {
                // No GUI exists yet to report to - this is a best-effort convenience, never surfaced.
                LOGGER.warn("Could not preload Ollama model '{}': {}", model, message);
            }
        });
    }
}
