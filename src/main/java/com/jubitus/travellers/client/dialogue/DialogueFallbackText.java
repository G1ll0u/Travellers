package com.jubitus.travellers.client.dialogue;

import com.jubitus.travellers.server.dialogue.DialogueContext;
import net.minecraft.client.resources.I18n;

/**
 * The one canned line shown whenever a real conversation isn't happening — either the player hasn't
 * configured Ollama yet, or a configured call just failed (see the dialogue plan's D-9 / Task C3:
 * both paths degrade to the same graceful fallback rather than a raw error). Phrases the exact same
 * facts {@code EntityTraveller.processInteract()} used to print directly, but purely from the
 * already-received {@link DialogueContext} — no second network round trip needed, the client has
 * everything already.
 */
public final class DialogueFallbackText {

    private DialogueFallbackText() {
    }

    public static String forContext(DialogueContext context) {
        switch (context.activity) {
            case FOLLOWING_SOMEONE:
                return I18n.format("travellers.dialogue.travelling_with_someone");
            case IDLING_IN_VILLAGE:
                return context.activityVillageName != null
                        ? I18n.format("travellers.dialogue.staying_in_village", context.activityVillageName)
                        : I18n.format("travellers.dialogue.no_destination");
            case TRAVELLING_TO_VILLAGE:
                return context.activityVillageName != null
                        ? I18n.format("travellers.dialogue.gui_fallback_heading_to", context.activityVillageName)
                        : I18n.format("travellers.dialogue.no_destination");
            case ROAMING:
            default:
                return I18n.format("travellers.dialogue.no_destination");
        }
    }
}
