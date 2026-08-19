package com.jubitus.travellers.client.notify;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.NotificationType;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * One active toast on screen. Mutable, ticked every client tick.
 */
public class ActiveNotification {

    public enum FadeState {
        FADE_IN, HOLD, FADE_OUT
    }

    public final NotificationType type;
    public final List<String> wrappedLines;

    public FadeState state = FadeState.FADE_IN;
    public int ticksInState = 0;

    public ActiveNotification(NotificationType type, String text) {
        this.type = type;
        int width = TravellersConfig.NOTIFICATIONS.widthPixels;
        this.wrappedLines = Minecraft.getMinecraft().fontRenderer.listFormattedStringToWidth(text, width);
    }

    public void tick() {
        ticksInState++;
        switch (state) {
            case FADE_IN:
                if (ticksInState > TravellersConfig.NOTIFICATIONS.fadeInTicks) {
                    state = FadeState.HOLD;
                    ticksInState = 0;
                }
                break;
            case HOLD:
                if (ticksInState > TravellersConfig.NOTIFICATIONS.displayDurationTicks) {
                    state = FadeState.FADE_OUT;
                    ticksInState = 0;
                }
                break;
            case FADE_OUT:
                // stays until ClientNotificationCache removes it once fully faded
                break;
        }
    }

    public float alpha() {
        switch (state) {
            case FADE_IN:
                int fadeIn = TravellersConfig.NOTIFICATIONS.fadeInTicks;
                return fadeIn <= 0 ? 1F : Math.min(1F, ticksInState / (float) fadeIn);
            case FADE_OUT:
                int fadeOut = TravellersConfig.NOTIFICATIONS.fadeOutTicks;
                return fadeOut <= 0 ? 0F : Math.max(0F, 1F - (ticksInState / (float) fadeOut));
            case HOLD:
            default:
                return 1F;
        }
    }
}
