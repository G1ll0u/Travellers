package com.jubitus.traveller.traveller.culture;

import net.minecraft.util.SoundEvent;

public final class VoiceSet {
    public final SoundEvent talk, hurt, hello;
    public final SoundEvent[] ambient;
    public VoiceSet(SoundEvent talk, SoundEvent hurt, SoundEvent hello, SoundEvent... ambient) {
        this.talk = talk; this.hurt = hurt; this.hello = hello; this.ambient = ambient;
    }
}
