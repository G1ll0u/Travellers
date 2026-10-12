package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.mixins.MixinCultureLoadedLanguagesAccessor;
import net.minecraft.entity.player.EntityPlayer;
import org.millenaire.common.culture.Culture;
import org.millenaire.common.culture.CultureLanguage;

/**
 * Millénaire itself ships one lore paragraph per culture ({@code travelbook.culture.desc}, the same
 * text shown in its own in-game travel book) — reused here instead of hand-authoring a culture blurb
 * ourselves, so every Millénaire culture (base or a {@code millenaire-custom} pack) gets one for free.
 * 2026-09-13 maintainer feedback: travellers should "stick to their culture" more in conversation.
 *
 * <p><b>Trap:</b> {@code Culture.getCultureString} picks whatever language Millénaire's own
 * {@code effective_language}/{@code main_language} server config says, not English — on a
 * French-configured server it silently returned French text into our all-English prompt. Always read
 * the {@code "en"} entry directly via {@link MixinCultureLoadedLanguagesAccessor} instead; Millénaire
 * eagerly loads every language subdirectory it finds at culture-load time, so "en" is already
 * populated by the time any player interacts, regardless of the server's configured language.
 *
 * <p>A missing key entirely (culture not found, or that pack never wrote an English string) is
 * treated the same as no file for a personality sheet: say nothing extra, never fall back to a
 * different language and risk a mixed-language prompt.
 */
public final class CultureFlavorDialogueContextProvider implements DialogueContextProvider {

    private static final String ENGLISH = "en";
    private static final String DESC_KEY = "travelbook.culture.desc";
    /** Same idea as {@code PersonalityProfileLoader.MAX_CHARS} — some packs' lore runs long. */
    private static final int MAX_CHARS = 500;

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        String cultureId = traveller.getCultureId();
        if (cultureId == null || cultureId.isEmpty()) return;

        String desc = englishCultureString(cultureId, DESC_KEY);
        if (desc == null || desc.isEmpty()) return;
        if (desc.length() > MAX_CHARS) desc = desc.substring(0, MAX_CHARS) + "...";
        builder.cultureFlavorText(desc);
    }

    /** A culture string from Millénaire's English files whatever the server language; null when missing (see the class doc trap). */
    static String englishCultureString(String cultureId, String key) {
        Culture culture = Culture.getCultureByName(cultureId);
        if (culture == null) return null;
        CultureLanguage english = ((MixinCultureLoadedLanguagesAccessor) culture).travellers$getLoadedLanguages().get(ENGLISH);
        return english == null ? null : english.strings.get(key);
    }
}
