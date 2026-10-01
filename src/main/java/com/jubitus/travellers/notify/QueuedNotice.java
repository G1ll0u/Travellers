package com.jubitus.travellers.notify;

import javax.annotation.Nullable;

/**
 * A notification waiting to be delivered to an offline player on next login.
 */
public final class QueuedNotice {
    public final NotificationType type;
    public final String i18nKey;
    public final String[] args;
    public final long worldTime;
    @Nullable
    public final NoticePlace place;
    @Nullable
    public final NoticeLine detail;

    public QueuedNotice(NotificationType type, String i18nKey, String[] args, long worldTime,
                        @Nullable NoticePlace place, @Nullable NoticeLine detail) {
        this.type = type;
        this.i18nKey = i18nKey;
        this.args = args;
        this.worldTime = worldTime;
        this.place = place;
        this.detail = detail;
    }
}
