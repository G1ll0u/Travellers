package com.jubitus.travellers.entity.entityAI.event;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

/**
 * Lifecycle contract for a world-sim "event" that happens to travellers and must behave identically
 * whether a player is watching (physical) or not (virtual). Mirrors the entity virtual/physical cycle
 * used by VirtualTravellerManager so events ride the same proximity machinery instead of duplicating it.
 *
 * The first implementation is the camp system. New events should implement this and register a factory
 * with {@link EventTypeRegistry} — that is the whole cost of adding an event, by design.
 *
 * Contract:
 *  - {@link #tickVirtual} advances the event while no player is near (cheap, no world interaction).
 *  - {@link #onMaterialize} reconciles the physical world to the current virtual truth when a player
 *    approaches (build what should exist, remove what should not).
 *  - {@link #tickPhysical} runs while loaded.
 *  - {@link #onVirtualize} snapshots physical state back into virtual form when players leave.
 *  - {@link #toNbt}/{@code fromNbt} persist the event across save/load via {@link EventTypeRegistry}.
 */
public interface ITravellerEvent {

    /** Stable type id used to pick the right factory at load time. */
    String typeId();

    void tickVirtual(WorldServer world, long now);

    void onMaterialize(WorldServer world, long now);

    void tickPhysical(WorldServer world, long now);

    void onVirtualize(WorldServer world, long now);

    /** True while a player is close enough that this event should be physical. */
    boolean hasNearbyPlayer(WorldServer world);

    NBTTagCompound toNbt();
}
