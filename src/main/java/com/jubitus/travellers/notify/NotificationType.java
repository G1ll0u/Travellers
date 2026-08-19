package com.jubitus.travellers.notify;

public enum NotificationType {
    INFO(0xFFFFFFFF),
    WARNING(0xFFFFFF55),
    EVENT(0xFF55AAFF),
    ERROR(0xFFFF5555),
    DEBUG(0xFF55FF55);

    public final int color;

    NotificationType(int color) {
        this.color = color;
    }
}
