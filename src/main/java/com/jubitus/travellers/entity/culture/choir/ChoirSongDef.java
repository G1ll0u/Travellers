package com.jubitus.travellers.entity.culture.choir;

import net.minecraft.util.SoundEvent;

public final class ChoirSongDef {
    public final String name;
    public final SoundEvent[] stems;
    // Duration in ticks computed from the longest stem OGG; 0 = unknown (use config default).
    public final int durationTicks;

    public ChoirSongDef(String name, SoundEvent[] stems, int durationTicks) {
        this.name = name;
        this.stems = stems;
        this.durationTicks = durationTicks;
    }

    public int stemCount() { return stems.length; }

    public SoundEvent stem(int i) {
        return (i >= 0 && i < stems.length) ? stems[i] : null;
    }
}
