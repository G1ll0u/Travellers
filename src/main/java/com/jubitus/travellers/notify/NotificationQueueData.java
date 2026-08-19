package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global (not per-dimension) store of notifications queued for offline players,
 * delivered on their next login. One instance world-wide, always backed by the overworld.
 */
public class NotificationQueueData extends WorldSavedData {

    public static final String DATA_NAME = "travellers_notification_queue";

    private final Map<UUID, List<QueuedNotice>> pending = new HashMap<>();

    public NotificationQueueData() {
        super(DATA_NAME);
    }

    public NotificationQueueData(String name) {
        super(name);
    }

    public static NotificationQueueData get(World world) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0)
                : (WorldServer) world;

        MapStorage storage = overworld.getPerWorldStorage();
        NotificationQueueData data =
                (NotificationQueueData) storage.getOrLoadData(NotificationQueueData.class, DATA_NAME);

        if (data == null) {
            data = new NotificationQueueData();
            storage.setData(DATA_NAME, data);
        }

        return data;
    }

    public void enqueue(UUID owner, NotificationType type, String i18nKey, String[] args, long worldTime,
                        @Nullable BlockPos location, int dimension) {
        List<QueuedNotice> list = pending.computeIfAbsent(owner, k -> new ArrayList<>());
        list.add(new QueuedNotice(type, i18nKey, args, worldTime, location, dimension));

        int cap = TravellersConfig.NOTIFICATIONS.offlineQueueCapPerPlayer;
        while (list.size() > cap) {
            list.remove(0);
        }

        markDirty();
    }

    public List<QueuedNotice> take(UUID owner) {
        List<QueuedNotice> list = pending.remove(owner);
        if (list == null) {
            return new ArrayList<>();
        }
        markDirty();
        return list;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        pending.clear();

        if (!nbt.hasKey("Players", Constants.NBT.TAG_LIST)) {
            return;
        }

        NBTTagList players = nbt.getTagList("Players", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < players.tagCount(); i++) {
            NBTTagCompound playerTag = players.getCompoundTagAt(i);
            if (!playerTag.hasKey("PlayerUUID")) {
                continue;
            }

            try {
                UUID owner = UUID.fromString(playerTag.getString("PlayerUUID"));
                List<QueuedNotice> list = new ArrayList<>();

                NBTTagList notices = playerTag.getTagList("Notices", Constants.NBT.TAG_COMPOUND);
                for (int j = 0; j < notices.tagCount(); j++) {
                    NBTTagCompound noticeTag = notices.getCompoundTagAt(j);
                    NotificationType type = NotificationType.values()[noticeTag.getInteger("TypeOrdinal")];
                    String i18nKey = noticeTag.getString("I18nKey");
                    long worldTime = noticeTag.getLong("WorldTime");

                    NBTTagList argsTag = noticeTag.getTagList("Args", Constants.NBT.TAG_STRING);
                    String[] args = new String[argsTag.tagCount()];
                    for (int k = 0; k < argsTag.tagCount(); k++) {
                        args[k] = argsTag.getStringTagAt(k);
                    }

                    BlockPos location = noticeTag.hasKey("LocX")
                            ? new BlockPos(noticeTag.getInteger("LocX"), noticeTag.getInteger("LocY"), noticeTag.getInteger("LocZ"))
                            : null;
                    int dimension = noticeTag.getInteger("LocDim");

                    list.add(new QueuedNotice(type, i18nKey, args, worldTime, location, dimension));
                }

                pending.put(owner, list);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList players = new NBTTagList();

        for (Map.Entry<UUID, List<QueuedNotice>> e : pending.entrySet()) {
            NBTTagCompound playerTag = new NBTTagCompound();
            playerTag.setString("PlayerUUID", e.getKey().toString());

            NBTTagList notices = new NBTTagList();
            for (QueuedNotice n : e.getValue()) {
                NBTTagCompound noticeTag = new NBTTagCompound();
                noticeTag.setInteger("TypeOrdinal", n.type.ordinal());
                noticeTag.setString("I18nKey", n.i18nKey);
                noticeTag.setLong("WorldTime", n.worldTime);

                NBTTagList argsTag = new NBTTagList();
                for (String arg : n.args) {
                    argsTag.appendTag(new NBTTagString(arg));
                }
                noticeTag.setTag("Args", argsTag);

                if (n.location != null) {
                    noticeTag.setInteger("LocX", n.location.getX());
                    noticeTag.setInteger("LocY", n.location.getY());
                    noticeTag.setInteger("LocZ", n.location.getZ());
                    noticeTag.setInteger("LocDim", n.dimension);
                }

                notices.appendTag(noticeTag);
            }
            playerTag.setTag("Notices", notices);

            players.appendTag(playerTag);
        }

        nbt.setTag("Players", players);
        return nbt;
    }
}
