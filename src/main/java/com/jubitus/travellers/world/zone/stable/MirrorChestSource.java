package com.jubitus.travellers.world.zone.stable;

import net.minecraft.item.Item;

/**
 * {@link ChestSource} backed by the persisted {@link StableChestMirror} - the offscreen supply.
 * Every {@link #consume} is recorded as unreconciled spend that {@code StablePhysicalReconciler}
 * drains out of the real chests the next time the zone loads.
 */
public final class MirrorChestSource implements ChestSource {

    private final StableChestMirror mirror;

    public MirrorChestSource(StableChestMirror mirror) {
        this.mirror = mirror;
    }

    @Override
    public int available(Item item) {
        return mirror.available(item);
    }

    @Override
    public boolean consume(Item item) {
        return mirror.consume(item);
    }

    @Override
    public boolean isLive() {
        return false;
    }
}
