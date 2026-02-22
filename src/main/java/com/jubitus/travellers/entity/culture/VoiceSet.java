package com.jubitus.travellers.entity.culture;

import net.minecraft.util.SoundEvent;

public final class VoiceSet {
    public final SoundEvent talk;
    public final SoundEvent hurt;
    public final SoundEvent hello;
    public final SoundEvent[] ambient; // <-- array/pool

    public VoiceSet(SoundEvent talk, SoundEvent hurt, SoundEvent hello, SoundEvent... ambient) {
        this.talk = talk;
        this.hurt = hurt;
        this.hello = hello;
        this.ambient = (ambient == null) ? new SoundEvent[0] : ambient;
    }
    @javax.annotation.Nullable
    public SoundEvent pickAmbient(java.util.Random rand, @javax.annotation.Nullable SoundEvent fallback) {
        if (ambient == null || ambient.length == 0) return fallback;
        if (rand == null) return ambient[0];
        return ambient[rand.nextInt(ambient.length)];
    }
}


