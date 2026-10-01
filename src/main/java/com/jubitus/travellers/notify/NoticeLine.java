package com.jubitus.travellers.notify;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/** One translatable line (i18n key + args), resolved on the client. Used as a notification's optional second line. */
public final class NoticeLine {
    public final String key;
    public final String[] args;

    public NoticeLine(String key, String... args) {
        this.key = key;
        this.args = args;
    }

    public static NoticeLine of(NotificationDef def, String... args) {
        return new NoticeLine(def.i18nKey, args);
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Key", key);
        NBTTagList list = new NBTTagList();
        for (String a : args) list.appendTag(new NBTTagString(a));
        tag.setTag("Args", list);
        return tag;
    }

    public static NoticeLine fromNbt(NBTTagCompound tag) {
        NBTTagList list = tag.getTagList("Args", Constants.NBT.TAG_STRING);
        String[] args = new String[list.tagCount()];
        for (int i = 0; i < args.length; i++) args[i] = list.getStringTagAt(i);
        return new NoticeLine(tag.getString("Key"), args);
    }

    public void write(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, key);
        buf.writeInt(args.length);
        for (String a : args) ByteBufUtils.writeUTF8String(buf, a);
    }

    public static NoticeLine read(ByteBuf buf) {
        String key = ByteBufUtils.readUTF8String(buf);
        String[] args = new String[buf.readInt()];
        for (int i = 0; i < args.length; i++) args[i] = ByteBufUtils.readUTF8String(buf);
        return new NoticeLine(key, args);
    }
}
