package com.jubitus.travellers.zone.stable;

import net.minecraft.item.Item;

/**
 * A minimal view of the Stable's item supply for {@link StableWork}: the items the stable economy
 * spends ({@link StableChestMirror#ITEMS}). Two implementations so the same rule
 * code runs loaded and offscreen:
 * <ul>
 *   <li>{@link RealChestSource} - reads and removes items straight from the zone's real chest
 *       {@code TileEntity}s. Used while a loaded palfrenier is doing the work.</li>
 *   <li>{@link MirrorChestSource} - draws the persisted {@link StableChestMirror}, whose accrued
 *       spend is reconciled back into the real chests the next time the zone loads. Used by the
 *       offscreen timer pass.</li>
 * </ul>
 */
public interface ChestSource {

    /** How many of {@code item} are believed available to spend right now. */
    int available(Item item);

    /** Spends one {@code item}; returns false (and changes nothing) if none is available. */
    boolean consume(Item item);

    /** True for the loaded (real chest) source, false for the offscreen mirror - used only to tag
     * debug-log lines with where the work happened. */
    boolean isLive();
}
