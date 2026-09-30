package com.jubitus.travellers.entity.culture;

import net.minecraft.util.SoundEvent;


public final class VoiceSet {
    public final SoundEvent[] talk;
    public final SoundEvent[] hurt;
    public final SoundEvent[] hello;
    public final SoundEvent[] ambient;
    public final SoundEvent[] attack;
    public final SoundEvent[] creeper;
    public final SoundEvent[] kill;
    public final SoundEvent[] death;

    public VoiceSet(SoundEvent[] talk, SoundEvent[] hurt, SoundEvent[] hello, SoundEvent[] ambient,
                    SoundEvent[] attack, SoundEvent[] creeper, SoundEvent[] kill, SoundEvent[] death) {
        this.talk    = (talk    == null) ? new SoundEvent[0] : talk;
        this.hurt    = (hurt    == null) ? new SoundEvent[0] : hurt;
        this.hello   = (hello   == null) ? new SoundEvent[0] : hello;
        this.ambient = (ambient == null) ? new SoundEvent[0] : ambient;
        this.attack  = (attack  == null) ? new SoundEvent[0] : attack;
        this.creeper = (creeper == null) ? new SoundEvent[0] : creeper;
        this.kill    = (kill    == null) ? new SoundEvent[0] : kill;
        this.death   = (death   == null) ? new SoundEvent[0] : death;
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

    public SoundEvent pickAttack(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(attack, rand, fallback);
    }

    public SoundEvent pickCreeper(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(creeper, rand, fallback);
    }

    public SoundEvent pickKill(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(kill, rand, fallback);
    }

    public SoundEvent pickDeath(java.util.Random rand, SoundEvent fallback) {
        return pickFrom(death, rand, fallback);
    }

    /** True if every clip category is empty - a personality with no sound folder at all (a dialogue-only "no voice" personality), or a genuinely empty one. */
    public boolean isEmpty() {
        return talk.length == 0 && hurt.length == 0 && hello.length == 0 && ambient.length == 0
                && attack.length == 0 && creeper.length == 0 && kill.length == 0 && death.length == 0;
    }

    private static SoundEvent pickFrom(SoundEvent[] arr, java.util.Random rand, SoundEvent fallback) {
        if (arr == null || arr.length == 0) return fallback;
        if (rand == null) return arr[0];
        return arr[rand.nextInt(arr.length)];
    }
}