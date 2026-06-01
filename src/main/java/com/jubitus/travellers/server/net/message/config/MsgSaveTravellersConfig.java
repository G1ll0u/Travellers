package com.jubitus.travellers.server.net.message.config;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.config.TravellersConfigEntry;
import com.jubitus.travellers.config.TravellersConfigHistory;
import com.jubitus.travellers.config.TravellersConfigPersistence;
import com.jubitus.travellers.config.TravellersConfigReflector;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Client → Server: save config changes, including the pre-edit baseline for history comparison.
// The client sends its own baseline because in singleplayer the client and server share the same
// JVM — liveApplyCurrentField modifies TravellersConfig fields in real-time, so the server can't
// build an accurate pre-edit snapshot from TravellersConfig on its own.
public final class MsgSaveTravellersConfig implements IMessage {

    private Map<String, String> baseline;
    private Map<String, String> values;

    public MsgSaveTravellersConfig() {}

    public MsgSaveTravellersConfig(Map<String, String> baseline, Map<String, String> values) {
        this.baseline = baseline;
        this.values = values;
    }

    private static void writeMap(ByteBuf buf, Map<String, String> map) {
        buf.writeInt(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, e.getKey());
            ByteBufUtils.writeUTF8String(buf, e.getValue());
        }
    }

    private static Map<String, String> readMap(ByteBuf buf) {
        int n = buf.readInt();
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) map.put(ByteBufUtils.readUTF8String(buf), ByteBufUtils.readUTF8String(buf));
        return map;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        baseline = readMap(buf);
        values   = readMap(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        writeMap(buf, baseline);
        writeMap(buf, values);
    }

    public static final class Handler implements IMessageHandler<MsgSaveTravellersConfig, IMessage> {
        @Override
        public IMessage onMessage(MsgSaveTravellersConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            if (player == null || player.server == null) return null;
            if (!player.server.getPlayerList().canSendCommands(player.getGameProfile())) {
                player.sendMessage(new TextComponentString("[Travellers] You need op permission to change config."));
                return null;
            }
            player.getServerWorld().addScheduledTask(() -> {
                List<TravellersConfigEntry> entries = TravellersConfigReflector.buildEntries();
                TravellersConfigReflector.applyAll(entries, msg.values);
                TravellersModConfig.syncFromTravellersConfig();
                TravellersModConfig.applyDerivedValues();
                TravellersModConfig.applyRuntimeEffects();
                TravellersConfigPersistence.save(msg.values);
                TravellersModConfig.syncServerCachesFromConfig();
                TravellersModConfig.broadcastServerCostsToAllPlayers();
                // Use client-supplied baseline so the comparison is accurate even in singleplayer
                TravellersConfigHistory.onSave(msg.baseline, msg.values);
                player.sendMessage(new TextComponentString("[Travellers] Config saved."));
            });
            return null;
        }
    }
}
