package com.jubitus.travellers.mixins;

import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.util.IntHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the tracker entries by entity id, for {@link MixinEntityTrackerEntry}. */
@Mixin(EntityTracker.class)
public interface MixinEntityTrackerAccessor {

    @Accessor("trackedEntityHashTable")
    IntHashMap<EntityTrackerEntry> travellers$getEntries();
}
