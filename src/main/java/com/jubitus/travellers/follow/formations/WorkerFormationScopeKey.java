package com.jubitus.travellers.follow.formations;

import net.minecraft.nbt.NBTTagCompound;

import java.util.Objects;
import java.util.UUID;

public final class WorkerFormationScopeKey {

    public enum Type {
        PLAYER,
        WORKER
    }

    private final Type type;
    private final UUID id;

    public WorkerFormationScopeKey(Type type, UUID id) {
        this.type = Objects.requireNonNull(type, "type");
        this.id = Objects.requireNonNull(id, "id");
    }

    public Type getType() {
        return type;
    }

    public UUID getId() {
        return id;
    }

    public String asMapKey() {
        return type.name() + ":" + id.toString();
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Type", type.name());
        tag.setUniqueId("Id", id);
        return tag;
    }

    public static WorkerFormationScopeKey fromNbt(NBTTagCompound tag) {
        Type type = Type.valueOf(tag.getString("Type"));
        UUID id = tag.getUniqueId("Id");
        return new WorkerFormationScopeKey(type, id);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WorkerFormationScopeKey)) return false;
        WorkerFormationScopeKey other = (WorkerFormationScopeKey) obj;
        return type == other.type && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + id.hashCode();
    }

    @Override
    public String toString() {
        return asMapKey();
    }
}