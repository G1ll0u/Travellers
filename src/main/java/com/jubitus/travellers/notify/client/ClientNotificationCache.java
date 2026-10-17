package com.jubitus.travellers.notify.client;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.NotificationType;
import net.minecraft.client.resources.I18n;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Holds the toasts on screen and those waiting for their turn. New toasts appear at a steady pace
 * (dangers first) so a burst never floods the screen - or, later, the speakers; a story toast is
 * updated in place. Client thread only (packet handler hops via addScheduledTask).
 */
public final class ClientNotificationCache {

    private static final int TICKS_BETWEEN_TOASTS = 8;
    // Beyond this many waiting, the oldest non-danger ones fold into a single "+N more" toast.
    private static final int MAX_WAITING = 4;
    private static final String OVERFLOW_KEY = "travellers:overflow";

    private static final class Waiting {
        NotificationType type;
        String text;
        @Nullable final String storyKey;

        Waiting(NotificationType type, String text, @Nullable String storyKey) {
            this.type = type;
            this.text = text;
            this.storyKey = storyKey;
        }
    }

    // Newest notifications are appended at the end of the list.
    private static final List<ActiveNotification> ACTIVE = new ArrayList<>();
    private static final LinkedList<Waiting> WAITING = new LinkedList<>();
    private static int cooldown = 0;
    private static int folded = 0;

    private ClientNotificationCache() {}

    public static void push(NotificationType type, String text, @Nullable String storyKey) {
        if (!TravellersConfig.NOTIFICATIONS.masterEnabled || !isShown(type)) return;

        if (storyKey != null) {
            ActiveNotification shown = findActive(storyKey);
            if (shown != null) {
                shown.update(type, text);
                return;
            }
            for (Waiting w : WAITING) {
                if (storyKey.equals(w.storyKey)) {
                    w.type = type;
                    w.text = text;
                    return;
                }
            }
        }

        Waiting w = new Waiting(type, text, storyKey);
        if (type == NotificationType.DANGER) {
            int i = 0;
            while (i < WAITING.size() && WAITING.get(i).type == NotificationType.DANGER) i++;
            WAITING.add(i, w);
        } else {
            WAITING.addLast(w);
        }
        foldOverflow();
    }

    private static void foldOverflow() {
        while (WAITING.size() > MAX_WAITING) {
            Waiting dropped = null;
            for (Waiting w : WAITING) {
                if (w.type != NotificationType.DANGER && w.storyKey == null) {
                    dropped = w;
                    break;
                }
            }
            if (dropped == null) return;
            WAITING.remove(dropped);
            folded++;
        }
        if (folded > 0) {
            String text = I18n.format("travellers.notify.overflow", folded);
            ActiveNotification shown = findActive(OVERFLOW_KEY);
            if (shown != null) {
                shown.update(NotificationType.INFO, text);
            } else {
                show(new ActiveNotification(NotificationType.INFO, text, OVERFLOW_KEY));
            }
        }
    }

    @Nullable
    private static ActiveNotification findActive(String storyKey) {
        for (ActiveNotification n : ACTIVE) {
            if (storyKey.equals(n.storyKey)) return n;
        }
        return null;
    }

    private static void show(ActiveNotification n) {
        ACTIVE.add(n);
        int maxVisible = TravellersConfig.NOTIFICATIONS.maxVisible;
        while (ACTIVE.size() > maxVisible) {
            ACTIVE.remove(0);
        }
    }

    private static boolean isShown(NotificationType type) {
        switch (type) {
            case DANGER:
                return TravellersConfig.NOTIFICATIONS.showDanger;
            case ALERT:
                return TravellersConfig.NOTIFICATIONS.showAlert;
            case SUCCESS:
                return TravellersConfig.NOTIFICATIONS.showSuccess;
            case INFO:
                return TravellersConfig.NOTIFICATIONS.showInfo;
            case REFUSAL:
                return TravellersConfig.NOTIFICATIONS.showRefusal;
            default:
                return true;
        }
    }

    public static void tick() {
        if (cooldown > 0) cooldown--;
        if (cooldown <= 0 && !WAITING.isEmpty()) {
            Waiting w = WAITING.removeFirst();
            show(new ActiveNotification(w.type, w.text, w.storyKey));
            cooldown = TICKS_BETWEEN_TOASTS;
        }
        for (ActiveNotification n : ACTIVE) {
            n.tick();
        }
        ACTIVE.removeIf(n -> n.state == ActiveNotification.FadeState.FADE_OUT
                && n.ticksInState > TravellersConfig.NOTIFICATIONS.fadeOutTicks);
        if (folded > 0 && findActive(OVERFLOW_KEY) == null) folded = 0;
    }

    public static List<ActiveNotification> visible() {
        return new ArrayList<>(ACTIVE);
    }
}
