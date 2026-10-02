package com.jubitus.travellers.sound;

import com.jubitus.travellers.unit.culture.VoiceSet;
import net.minecraft.util.SoundEvent;

public final class VoicePersonality {
    /** Saved to NBT / synced via DataParameter. Keep this stable. */
    public final String id;

    /** The actual sounds used for this personality. */
    public final VoiceSet voiceSet;

    public VoicePersonality(String id, VoiceSet voiceSet) {
        this.id = (id == null) ? "" : id;
        this.voiceSet = voiceSet;
    }
}
