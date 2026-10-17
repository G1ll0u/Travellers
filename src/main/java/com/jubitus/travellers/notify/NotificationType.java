package com.jubitus.travellers.notify;

/** Severity of a notice: its colour now, its sound later. Whether it is journaled is a separate property of its {@link NotificationDef}. */
public enum NotificationType {
    DANGER(0xFFFF5555),
    ALERT(0xFFFFAA33),
    SUCCESS(0xFF66DD66),
    INFO(0xFFF0F0F0),
    REFUSAL(0xFFC8A0A8);

    public final int color;

    NotificationType(int color) {
        this.color = color;
    }

    /** Read from a saved name; saves older than the severities stored an ordinal of INFO, WARNING, EVENT, ERROR, DEBUG. */
    public static NotificationType fromNbt(net.minecraft.nbt.NBTTagCompound tag, String i18nKey) {
        if (tag.hasKey("Severity")) {
            try {
                return valueOf(tag.getString("Severity"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        NotificationDef def = NotificationDefs.byKey(i18nKey);
        if (def != null) return def.type;
        return i18nKey.startsWith("death.") ? DANGER : INFO;
    }
}
