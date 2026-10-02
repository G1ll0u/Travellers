package com.jubitus.travellers.dialogue.client;

import com.jubitus.travellers.unit.culture.CultureRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads a personality's "character sheet" — a short piece of free text describing how that voice
 * personality behaves and talks — for {@link DialoguePromptBuilder} to fold into the system prompt.
 *
 * <p>A traveller's {@code personalityId} (e.g. {@code "vener"}) previously only picked which voice
 * clips play; the model had no idea what the word meant and would talk about it, or ignore it,
 * essentially at random. A file here gives it real meaning.
 *
 * <p><b>Location and key</b>: {@code assets/travellers/personalities/<lang>/<personalityId>.md},
 * where {@code lang} is exactly {@link CultureRegistry#langForCulture} for the traveller's culture —
 * the same key the voice system itself resolves personalities by (see CLAUDE.md's sound system
 * section: "Audio is organized on disk by language/personality/type"). Deliberately a sibling of
 * {@code sounds/travellers/<lang>/<personalityId>/}, not inside it — this is prompt content, not an
 * audio asset, and keeping it out of that tree means {@code generateDynamicSounds} never has to know
 * it exists.
 *
 * <p>No file for a given personality (including {@code "default"}) is not an error — it just means
 * nothing extra is said about temperament, exactly like today's behaviour for every personality that
 * hasn't been written up yet.
 */
public final class PersonalityProfileLoader {

    private static final Logger LOGGER = LogManager.getLogger("Travellers/Dialogue");

    /**
     * A local model's context window is precious (see CLAUDE.md's dialogue system note on this) -
     * a character sheet is meant to be a short paragraph or two, not a biography. Cut, not refused,
     * so a maintainer who writes too much still gets *something* sensible rather than nothing.
     */
    private static final int MAX_CHARS = 1200;

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    /** Distinguishes "not loaded yet" from "loaded, and it's empty" in the cache without a second map. */
    private static final String MISSING = "";

    private PersonalityProfileLoader() {
    }

    /** Empty string if this culture/personality pair has no sheet - callers should treat that as "say nothing extra". */
    public static String forCultureAndPersonality(@Nullable String cultureId, @Nullable String personalityId) {
        if (personalityId == null || personalityId.trim().isEmpty()) return "";
        String lang = CultureRegistry.langForCulture(cultureId == null ? "" : cultureId).toLowerCase(Locale.ROOT);
        String pid = personalityId.trim().toLowerCase(Locale.ROOT);

        String cacheKey = lang + "/" + pid;
        String cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        String loaded = load(lang, pid);
        CACHE.put(cacheKey, loaded == null ? MISSING : loaded);
        return loaded == null ? "" : loaded;
    }

    @Nullable
    private static String load(String lang, String personalityId) {
        String path = "assets/travellers/personalities/" + lang + "/" + personalityId + ".md";
        try (InputStream in = PersonalityProfileLoader.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            String text = sb.toString().trim();
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "...";
            }
            return text;
        } catch (IOException ex) {
            LOGGER.warn("Could not read personality profile {}", path, ex);
            return null;
        }
    }
}
