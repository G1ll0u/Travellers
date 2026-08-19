package com.jubitus.travellers.notify;

import net.minecraft.util.math.BlockPos;

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
    public final BlockPos location;
    public final int dimension;

    public QueuedNotice(NotificationType type, String i18nKey, String[] args, long worldTime,
                         @Nullable BlockPos location, int dimension) {
        this.type = type;
        this.i18nKey = i18nKey;
        this.args = args;
        this.worldTime = worldTime;
        this.location = location;
        this.dimension = dimension;
    }
}
