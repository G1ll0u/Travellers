package com.jubitus.travellers.combat.net;

import com.jubitus.travellers.combat.PlayerEnemyRelations;
import com.jubitus.travellers.worker.gui.GuiWorkerEnemies;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server -> client: every other player the server knows (online or not) with the sender's enemy flag. */
public class MsgSyncWorkerHostileList implements IMessage {

    /** One known player; {@code enemy} is the receiver's account-wide declaration. */
    public static final class Entry {
        public final UUID id;
        public final String name;
        public final boolean enemy;
        public final boolean online;

        public Entry(UUID id, String name, boolean enemy, boolean online) {
            this.id = id;
            this.name = name;
            this.enemy = enemy;
            this.online = online;
        }
    }

    private List<Entry> entries = new ArrayList<>();

    public MsgSyncWorkerHostileList() {
    }

    private MsgSyncWorkerHostileList(List<Entry> entries) {
        this.entries = entries;
    }

    /** Everyone who ever joined (player data files, resolved to names) except the player, plus declared enemies whatever their data. */
    public static MsgSyncWorkerHostileList build(EntityPlayerMP player) {
        Map<UUID, Entry> byId = new LinkedHashMap<>();
        MinecraftServer srv = player.getServer();
        if (srv != null) {
            UUID self = player.getUniqueID();
            Set<UUID> enemies = PlayerEnemyRelations.get(player.world).getHostiles(self);
            List<UUID> known = new ArrayList<>();
            for (EntityPlayerMP p : srv.getPlayerList().getPlayers()) known.add(p.getUniqueID());
            String[] saved = srv.getWorld(0).getSaveHandler().getPlayerNBTManager().getAvailablePlayerDat();
            for (String s : saved) {
                try {
                    known.add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                    // not a player file
                }
            }
            known.addAll(enemies);
            for (UUID id : known) {
                if (id.equals(self) || byId.containsKey(id)) continue;
                EntityPlayerMP online = srv.getPlayerList().getPlayerByUUID(id);
                GameProfile gp = online != null ? online.getGameProfile() : srv.getPlayerProfileCache().getProfileByUUID(id);
                boolean enemy = enemies.contains(id);
                if (gp == null || gp.getName() == null) {
                    if (!enemy) continue; // unresolvable and not declared: nothing a player could pick
                    byId.put(id, new Entry(id, id.toString(), true, false));
                } else {
                    byId.put(id, new Entry(id, gp.getName(), enemy, online != null));
                }
            }
        }
        return new MsgSyncWorkerHostileList(new ArrayList<>(byId.values()));
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            String name = ByteBufUtils.readUTF8String(buf);
            boolean enemy = buf.readBoolean();
            boolean online = buf.readBoolean();
            entries.add(new Entry(id, name, enemy, online));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entries.size());
        for (Entry e : entries) {
            buf.writeLong(e.id.getMostSignificantBits());
            buf.writeLong(e.id.getLeastSignificantBits());
            ByteBufUtils.writeUTF8String(buf, e.name);
            buf.writeBoolean(e.enemy);
            buf.writeBoolean(e.online);
        }
    }

    // CLIENT handler
    public static class Handler implements IMessageHandler<MsgSyncWorkerHostileList, IMessage> {
        @Override
        public IMessage onMessage(MsgSyncWorkerHostileList msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof GuiWorkerEnemies) {
                    ((GuiWorkerEnemies) screen).receivePlayers(msg.entries);
                }
            });
            return null;
        }
    }
}
