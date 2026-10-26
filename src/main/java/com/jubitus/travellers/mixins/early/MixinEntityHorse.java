package com.jubitus.travellers.mixins.early;

import com.google.common.base.Optional;
import com.jubitus.travellers.horse.HorseInteractLock;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

// Early config: EntityHorse is loaded during vanilla's entity registration.
@Mixin(EntityHorse.class)
public abstract class MixinEntityHorse extends AbstractHorse implements HorseInteractLock.Holder {

    @Unique
    private static final DataParameter<Optional<UUID>> TRAVELLERS_INTERACT_LOCK =
            EntityDataManager.createKey(EntityHorse.class, DataSerializers.OPTIONAL_UNIQUE_ID);

    public MixinEntityHorse(World worldIn) {
        super(worldIn);
    }

    @Inject(method = "entityInit", at = @At("TAIL"))
    private void travellers$registerInteractLock(CallbackInfo ci) {
        this.dataManager.register(TRAVELLERS_INTERACT_LOCK, Optional.absent());
    }

    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void travellers$tickInteractLock(CallbackInfo ci) {
        if (!this.world.isRemote) HorseInteractLock.tick((EntityHorse) (Object) this);
    }

    @Override
    public Optional<UUID> travellers$getInteractLock() {
        return this.dataManager.get(TRAVELLERS_INTERACT_LOCK);
    }

    @Override
    public void travellers$setInteractLock(Optional<UUID> lock) {
        this.dataManager.set(TRAVELLERS_INTERACT_LOCK, lock);
    }
}
