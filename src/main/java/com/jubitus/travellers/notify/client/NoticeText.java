package com.jubitus.travellers.notify.client;

import com.jubitus.travellers.notify.NoticeLine;
import com.jubitus.travellers.notify.NoticePlace;
import net.minecraft.client.resources.I18n;

import javax.annotation.Nullable;

/** Client-side text of a notification, shared by the toast and the journal: main line, ", where", then the detail line. */
public final class NoticeText {

    private NoticeText() {
    }

    public static String format(String key, String[] args, @Nullable NoticePlace place, @Nullable NoticeLine detail) {
        String text = safeFormat(key, args);
        if (place != null) {
            String suffix = LocationFormat.describe(place.pos, place.dimension);
            if (suffix != null) text = text + ", " + suffix;
        }
        if (detail != null) {
            text = text + "\n" + safeFormat(detail.key, detail.args);
        }
        return text;
    }

    private static String safeFormat(String key, String[] args) {
        try {
            return I18n.format(key, (Object[]) args);
        } catch (Exception ex) {
            return key;
        }
    }
}
