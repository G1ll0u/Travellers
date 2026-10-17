package com.jubitus.travellers.notify;

/**
 * A known notification kind: its severity, whether it goes to the journal, its i18n key and expected argument count.
 * Call sites reference a constant from {@link NotificationDefs} instead of a raw string key,
 * so a key/arg-count mismatch is a compile-time typo instead of a silent runtime issue.
 */
public final class NotificationDef {
    public final NotificationType type;
    /** Journal rule: true for what happens without a player click; answers to a click are toast-only. */
    public final boolean journaled;
    public final String i18nKey;
    public final int argCount;

    private NotificationDef(NotificationType type, boolean journaled, String i18nKey, int argCount) {
        this.type = type;
        this.journaled = journaled;
        this.i18nKey = i18nKey;
        this.argCount = argCount;
    }

    /** Something that happened on its own: toast + journal row. */
    static NotificationDef event(NotificationType type, String i18nKey, int argCount) {
        return new NotificationDef(type, true, i18nKey, argCount);
    }

    /** The answer to a player's click: toast only. */
    static NotificationDef reply(NotificationType type, String i18nKey, int argCount) {
        return new NotificationDef(type, false, i18nKey, argCount);
    }
}
