package com.jubitus.travellers.mixins;

import org.millenaire.common.culture.Culture;
import org.millenaire.common.culture.CultureLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.HashMap;

/**
 * Exposes {@code Culture.loadedLanguages} (package-private in Millénaire) so
 * {@code CultureFlavorDialogueContextProvider} can read a culture's string in a specific language
 * (always English, for our own English-language prompt) instead of whichever language
 * {@code getCultureString} would pick per the server's own configured Millénaire language. Millénaire
 * eagerly loads every language subdirectory it finds at culture-load time, so "en" is already
 * populated here by the time any player interacts.
 */
@Mixin(value = Culture.class, remap = false)
public interface MixinCultureLoadedLanguagesAccessor {
    @Accessor("loadedLanguages")
    HashMap<String, CultureLanguage> travellers$getLoadedLanguages();
}
