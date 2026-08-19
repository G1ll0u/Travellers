package com.jubitus.travellers.client.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.NotificationType;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the toasts currently on screen. Client thread only (packet handler
 * hops via addScheduledTask, tick/render both run on the client thread too).
 */
public final class ClientNotificationCache {

    // Newest notifications are appended at the end of the list.
    private static final List<ActiveNotification> ACTIVE = new ArrayList<>();

    private ClientNotificationCache() {}

    public static void push(NotificationType type, String text, long worldTime) {
        if (!TravellersConfig.NOTIFICATIONS.masterEnabled) return;

        switch (type) {
            case INFO:
                if (!TravellersConfig.NOTIFICATIONS.showInfo) return;
                break;
            case WARNING:
                if (!TravellersConfig.NOTIFICATIONS.showWarning) return;
                break;
            case EVENT:
                if (!TravellersConfig.NOTIFICATIONS.showEvent) return;
                break;
            case ERROR:
                if (!TravellersConfig.NOTIFICATIONS.showError) return;
                break;
            default:
                return;
        }

        ACTIVE.add(new ActiveNotification(type, text));

        int maxVisible = TravellersConfig.NOTIFICATIONS.maxVisible;
        while (ACTIVE.size() > maxVisible) {
            ACTIVE.remove(0);
        }
    }

    public static void tick() {
        for (ActiveNotification n : ACTIVE) {
            n.tick();
        }
        ACTIVE.removeIf(n -> n.state == ActiveNotification.FadeState.FADE_OUT
                && n.ticksInState > TravellersConfig.NOTIFICATIONS.fadeOutTicks);
    }

    public static List<ActiveNotification> visible() {
        return new ArrayList<>(ACTIVE);
    }
}
