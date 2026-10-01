package com.jubitus.travellers.notify;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/** Where a notification happened and, optionally, which unit it is about (the journal centers the map on it). */
public final class NoticePlace {
    public final BlockPos pos;
    public final int dimension;
    @Nullable
    public final UUID subject;

    public NoticePlace(BlockPos pos, int dimension, @Nullable UUID subject) {
        this.pos = pos;
        this.dimension = dimension;
        this.subject = subject;
    }

    public static NoticePlace of(net.minecraft.entity.Entity entity) {
        return new NoticePlace(entity.getPosition(), entity.dimension, entity.getUniqueID());
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("X", pos.getX());
        tag.setInteger("Y", pos.getY());
        tag.setInteger("Z", pos.getZ());
        tag.setInteger("Dim", dimension);
        if (subject != null) tag.setUniqueId("Subject", subject);
        return tag;
    }

    public static NoticePlace fromNbt(NBTTagCompound tag) {
        UUID subject = tag.hasUniqueId("Subject") ? tag.getUniqueId("Subject") : null;
        return new NoticePlace(new BlockPos(tag.getInteger("X"), tag.getInteger("Y"), tag.getInteger("Z")),
                tag.getInteger("Dim"), subject);
    }
}
