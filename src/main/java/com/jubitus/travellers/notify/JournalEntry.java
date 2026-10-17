package com.jubitus.travellers.notify;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/**
 * One persisted entry in a player's notification journal (journaled defs only).
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
    /** Non-null for one beat of a story (a battle): a newer beat replaces this row. */
    @Nullable
    public final String storyKey;

    public JournalEntry(NotificationType type, String i18nKey, String[] args, long worldTime,
                        @Nullable BattleSummary battleSummary, @Nullable NoticePlace place,
                        @Nullable NoticeLine detail, @Nullable String storyKey) {
        this.type = type;
        this.i18nKey = i18nKey;
        this.args = args;
        this.worldTime = worldTime;
        this.battleSummary = battleSummary;
        this.place = place;
        this.detail = detail;
        this.storyKey = storyKey;
    }

    public NBTTagCompound toNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("Severity", type.name());
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
        if (storyKey != null) {
            tag.setString("Story", storyKey);
        }

        return tag;
    }

    public static JournalEntry fromNbt(NBTTagCompound tag) {
        String i18nKey = tag.getString("I18nKey");
        NotificationType type = NotificationType.fromNbt(tag, i18nKey);
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

        String storyKey = tag.hasKey("Story", Constants.NBT.TAG_STRING) ? tag.getString("Story") : null;
        return new JournalEntry(type, i18nKey, args, worldTime, summary, place, detail, storyKey);
    }
}
