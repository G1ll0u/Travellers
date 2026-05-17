package com.jubitus.travellers.entity.culture;

import net.minecraft.util.SoundEvent;


public final class VoiceSet {
    public final SoundEvent[] talk;
    public final SoundEvent[] hurt;
    public final SoundEvent[] hello;
    public final SoundEvent[] ambient;

    public VoiceSet(SoundEvent[] talk, SoundEvent[] hurt, SoundEvent[] hello, SoundEvent[] ambient) {
        this.talk = (talk == null) ? new SoundEvent[0] : talk;
        this.hurt = (hurt == null) ? new SoundEvent[0] : hurt;
        this.hello = (hello == null) ? new SoundEvent[0] : hello;
        this.ambient = (ambient == null) ? new SoundEvent[0] : ambient;
    }

    public SoundEvent pickTalk(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(talk, rand, fallback);
    }

    public SoundEvent pickHurt(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(hurt, rand, fallback);
    }

    public SoundEvent pickHello(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(hello, rand, fallback);
    }

    public SoundEvent pickAmbient(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(ambient, rand, fallback);
    }

    private static SoundEvent pickFrom(SoundEvent[] arr, java.util.Random rand, SoundEvent fallback) {
        if (arr == null || arr.length == 0) return fallback;
        if (rand == null) return arr[0];
        return arr[rand.nextInt(arr.length)];
    }
}