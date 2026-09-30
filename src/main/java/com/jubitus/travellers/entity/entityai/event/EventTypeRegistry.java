package com.jubitus.travellers.entity.entityai.event;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps event type ids to factories so persisted events can be reconstructed at load time.
 * Adding a new event type = one {@link #register} call; nothing else in the persistence path changes.
 */
public final class EventTypeRegistry {

    public interface Factory {
        ITravellerEvent fromNbt(WorldServer world, NBTTagCompound tag);
    }

    private static final Map<String, Factory> FACTORIES = new HashMap<>();

    private EventTypeRegistry() {
    }

    public static void register(String typeId, Factory factory) {
        FACTORIES.put(typeId, factory);
    }

    @Nullable
    public static ITravellerEvent reconstruct(WorldServer world, String typeId, NBTTagCompound tag) {
        Factory f = FACTORIES.get(typeId);
        return f == null ? null : f.fromNbt(world, tag);
    }
}
