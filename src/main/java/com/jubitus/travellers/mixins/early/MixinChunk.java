package com.jubitus.travellers.mixins.early;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla kills a body read from a chunk file whose position lies outside that chunk ("Wrong location!").
 * A rider is saved with its horse, in the horse's chunk, while its own position can sit across the border
 * (a rearing horse shifts it, a fresh mount-up leaves it beside the horse): the unit would be lost for good.
 * Our units are moved just inside the chunk instead; the horse then pulls its rider back onto it.
 * HEAD inject, no local capture, {@code require = 0}: stays out of the way of other mods touching addEntity.
 */
@Mixin(Chunk.class)
public abstract class MixinChunk {

    // Keeps the body strictly inside the chunk's last block column.
    private static final double INSIDE_MARGIN = 1.0E-3;

    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    @Inject(method = "addEntity", at = @At("HEAD"), require = 0)
    private void travellers$keepUnitInsideChunk(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof EntityTraveller)) return;
        if (MathHelper.floor(entity.posX) >> 4 == x && MathHelper.floor(entity.posZ) >> 4 == z) return;
        double minX = x << 4, minZ = z << 4;
        entity.setPosition(MathHelper.clamp(entity.posX, minX, minX + 16 - INSIDE_MARGIN), entity.posY,
                MathHelper.clamp(entity.posZ, minZ, minZ + 16 - INSIDE_MARGIN));
    }
}
