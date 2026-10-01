package com.jubitus.travellers.notify;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * One persisted entry in a player's notification journal (Event-type notifications only).
 */
public final class JournalEntry {
    public final NotificationType type;
    public final String i18nKey;
    public final String[] args;
    public final long worldTime;
    @Nullable
    public final BattleSummary battleSummary;
    @Nullable
    public final NoticePlace place;
    @Nullable
    public final NoticeLine detail;

    public JournalEntry(NotificationType type, String i18nKey, String[] args, long worldTime,
                        @Nullable BattleSummary battleSummary, @Nullable NoticePlace place,
                        @Nullable NoticeLine detail) {
        this.type = type;
        this.i18nKey = i18nKey;
        this.args = args;
        this.worldTime = worldTime;
        this.battleSummary = battleSummary;
        this.place = place;
        this.detail = detail;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("TypeOrdinal", type.ordinal());
        tag.setString("I18nKey", i18nKey);
        tag.setLong("WorldTime", worldTime);

        NBTTagList argsTag = new NBTTagList();
        for (String arg : args) {
            argsTag.appendTag(new NBTTagString(arg));
        }
        tag.setTag("Args", argsTag);

        if (battleSummary != null) {
            tag.setTag("BattleSummary", battleSummary.toNbt());
        }
        if (place != null) {
            tag.setTag("Place", place.toNbt());
        }
        if (detail != null) {
            tag.setTag("Detail", detail.toNbt());
        }

        return tag;
    }

    public static JournalEntry fromNbt(NBTTagCompound tag) {
        NotificationType type = NotificationType.values()[tag.getInteger("TypeOrdinal")];
        String i18nKey = tag.getString("I18nKey");
        long worldTime = tag.getLong("WorldTime");

        NBTTagList argsTag = tag.getTagList("Args", Constants.NBT.TAG_STRING);
        String[] args = new String[argsTag.tagCount()];
        for (int i = 0; i < argsTag.tagCount(); i++) {
            args[i] = argsTag.getStringTagAt(i);
        }

        BattleSummary summary = tag.hasKey("BattleSummary", Constants.NBT.TAG_COMPOUND)
                ? BattleSummary.fromNbt(tag.getCompoundTag("BattleSummary")) : null;

        NoticePlace place;
        if (tag.hasKey("Place", Constants.NBT.TAG_COMPOUND)) {
            place = NoticePlace.fromNbt(tag.getCompoundTag("Place"));
        } else {
            // older entries only carried a location through their battle summary
            place = summary != null ? new NoticePlace(summary.location, summary.dimension, null) : null;
        }

        NoticeLine detail = tag.hasKey("Detail", Constants.NBT.TAG_COMPOUND)
                ? NoticeLine.fromNbt(tag.getCompoundTag("Detail")) : null;

        return new JournalEntry(type, i18nKey, args, worldTime, summary, place, detail);
    }
}
