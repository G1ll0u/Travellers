package com.jubitus.travellers.notify;

import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.util.Constants;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Global (not per-dimension) per-player history of Event-type notifications, feeding a future
 * journal GUI. One instance world-wide, always backed by the overworld.
 */
public class NotificationJournalData extends WorldSavedData {

    public static final String DATA_NAME = "travellers_notification_journal";

    private final Map<UUID, Deque<JournalEntry>> journals = new HashMap<>();

    public NotificationJournalData() {
        super(DATA_NAME);
    }

    public NotificationJournalData(String name) {
        super(name);
    }

    public static NotificationJournalData get(World world) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0)
                : (WorldServer) world;

        MapStorage storage = overworld.getPerWorldStorage();
        NotificationJournalData data =
                (NotificationJournalData) storage.getOrLoadData(NotificationJournalData.class, DATA_NAME);

        if (data == null) {
            data = new NotificationJournalData();
            storage.setData(DATA_NAME, data);
        }

        return data;
    }

    public void append(UUID owner, JournalEntry entry) {
        Deque<JournalEntry> deque = journals.computeIfAbsent(owner, k -> new ArrayDeque<>());
        deque.addLast(entry);

        int cap = TravellersConfig.NOTIFICATIONS.journalRetentionPerPlayer;
        while (deque.size() > cap) {
            deque.pollFirst();
        }

        markDirty();
    }

    public List<JournalEntry> getFor(UUID owner) {
        Deque<JournalEntry> deque = journals.get(owner);
        if (deque == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(deque);
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        journals.clear();

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
                Deque<JournalEntry> deque = new ArrayDeque<>();

                NBTTagList entries = playerTag.getTagList("Entries", Constants.NBT.TAG_COMPOUND);
                for (int j = 0; j < entries.tagCount(); j++) {
                    deque.addLast(JournalEntry.fromNbt(entries.getCompoundTagAt(j)));
                }

                journals.put(owner, deque);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList players = new NBTTagList();

        for (Map.Entry<UUID, Deque<JournalEntry>> e : journals.entrySet()) {
            NBTTagCompound playerTag = new NBTTagCompound();
            playerTag.setString("PlayerUUID", e.getKey().toString());

            NBTTagList entries = new NBTTagList();
            for (JournalEntry entry : e.getValue()) {
                entries.appendTag(entry.toNbt());
            }
            playerTag.setTag("Entries", entries);

            players.appendTag(playerTag);
        }

        nbt.setTag("Players", players);
        return nbt;
    }
}
