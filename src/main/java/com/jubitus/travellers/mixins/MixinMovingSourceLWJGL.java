package com.jubitus.travellers.mixins;

import com.jubitus.travellers.client.sound.SPMovingSoundHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import paulscode.sound.Channel;
import paulscode.sound.libraries.ChannelLWJGLOpenAL;
import paulscode.sound.libraries.SourceLWJGLOpenAL;

/**
 * Hooks SourceLWJGLOpenAL to enable periodic SoundPhysics environment re-evaluation
 * for sounds that follow moving entities.
 *
 * - play(): captures the SP context (category, sound name) right after SP itself evaluates
 *   the initial environment, so we can replay it on subsequent position updates.
 * - setPosition(): triggers SPMovingSoundHelper to re-evaluate SP environment when the
 *   source has moved enough (throttled to avoid excess raycasting).
 */
@Mixin(value = SourceLWJGLOpenAL.class, remap = false)
public abstract class MixinMovingSourceLWJGL {

    @Shadow private ChannelLWJGLOpenAL channelOpenAL;

    @Inject(method = "play", at = @At("TAIL"))
    private void travellers_captureSpContext(Channel c, CallbackInfo ci) {
        if (channelOpenAL == null || channelOpenAL.ALSource == null) return;
        SPMovingSoundHelper.captureContext(channelOpenAL.ALSource.get(0));
    }

    @Inject(method = "setPosition", at = @At("TAIL"))
    private void travellers_onPositionUpdated(float x, float y, float z, CallbackInfo ci) {
        if (channelOpenAL == null || channelOpenAL.ALSource == null) return;
        SPMovingSoundHelper.onSourceMoved(channelOpenAL.ALSource.get(0), x, y, z);
    }
}
