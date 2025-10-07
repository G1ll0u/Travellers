package com.jubitus.traveller.traveller.utils.sound;

import com.jubitus.traveller.traveller.entityAI.EntityTraveller;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MovingTravellerSound extends net.minecraft.client.audio.MovingSound
        implements net.minecraft.client.audio.ITickableSound {

    private final EntityTraveller traveller;
    private boolean stoppedExternally = false;

    public MovingTravellerSound(EntityTraveller e, SoundEvent evt, float vol, float pitch) {
        super(evt, e.getSoundCategory());
        this.traveller = e;
        this.volume = vol;
        this.pitch = pitch;
        this.repeat = false; // not looping; audio file length controls playback
        this.attenuationType = AttenuationType.LINEAR;

        this.xPosF = (float) e.posX;
        this.yPosF = (float) (e.posY + e.height * 0.6);
        this.zPosF = (float) e.posZ;
    }

    /** Called by our client bus to stop due to preemption. */
    public void stopNow() {
        this.stoppedExternally = true;
        this.donePlaying = true;
    }

    @Override public void update() {
        if (stoppedExternally || traveller == null || !traveller.isEntityAlive()) {
            this.donePlaying = true;
            return;
        }
        this.xPosF = (float) traveller.posX;
        this.yPosF = (float) (traveller.posY + traveller.height * 0.6);
        this.zPosF = (float) traveller.posZ;
        // No manual timeout here — let the audio end on its own.
    }
}
