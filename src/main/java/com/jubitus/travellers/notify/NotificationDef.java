package com.jubitus.travellers.notify;

/**
 * A known notification kind: its severity type, i18n key and expected argument count.
 * Call sites reference a constant from {@link NotificationDefs} instead of a raw string key,
 * so a key/arg-count mismatch is a compile-time typo instead of a silent runtime issue.
 */
public final class NotificationDef {
    public final NotificationType type;
    public final String i18nKey;
    public final int argCount;

    public NotificationDef(NotificationType type, String i18nKey, int argCount) {
        this.type = type;
        this.i18nKey = i18nKey;
        this.argCount = argCount;
    }
}
