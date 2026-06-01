package com.jubitus.travellers.server.net.message.config;

import com.jubitus.travellers.client.gui.config.GuiTravellersConfig;
import com.jubitus.travellers.config.TravellersConfigGuiState;
import com.jubitus.travellers.config.TravellersConfigHistory;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.*;

// Server → Client: open the config GUI with current server values and history.
public final class MsgOpenTravellersConfigGui implements IMessage {

    private Map<String, String> values;
    private List<String> histModOrder;
    private Map<String, List<String>> histValues;
    private int histMaxSize;

    public MsgOpenTravellersConfigGui() {}

    public MsgOpenTravellersConfigGui(Map<String, String> values) {
        this.values = values;
        this.histModOrder = new ArrayList<>(TravellersConfigHistory.getModOrder());
        this.histValues = TravellersConfigHistory.getAllHistory();
        this.histMaxSize = TravellersConfigHistory.maxSize;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        values = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            values.put(ByteBufUtils.readUTF8String(buf), ByteBufUtils.readUTF8String(buf));
        }
        histMaxSize = buf.readInt();
        int orderSize = buf.readInt();
        histModOrder = new ArrayList<>(orderSize);
        for (int i = 0; i < orderSize; i++) histModOrder.add(ByteBufUtils.readUTF8String(buf));
        int histSize = buf.readInt();
        histValues = new LinkedHashMap<>();
        for (int i = 0; i < histSize; i++) {
            String path = ByteBufUtils.readUTF8String(buf);
            int vSize = buf.readInt();
            List<String> vals = new ArrayList<>(vSize);
            for (int j = 0; j < vSize; j++) vals.add(ByteBufUtils.readUTF8String(buf));
            histValues.put(path, vals);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(values.size());
        for (Map.Entry<String, String> e : values.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, e.getKey());
            ByteBufUtils.writeUTF8String(buf, e.getValue());
        }
        buf.writeInt(histMaxSize);
        buf.writeInt(histModOrder.size());
        for (String s : histModOrder) ByteBufUtils.writeUTF8String(buf, s);
        buf.writeInt(histValues.size());
        for (Map.Entry<String, List<String>> e : histValues.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, e.getKey());
            List<String> vals = e.getValue();
            buf.writeInt(vals.size());
            for (String v : vals) ByteBufUtils.writeUTF8String(buf, v);
        }
    }

    public static final class Handler implements IMessageHandler<MsgOpenTravellersConfigGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenTravellersConfigGui msg, MessageContext ctx) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(() -> {
                TravellersConfigGuiState.applyNetworkHistory(msg.histModOrder, msg.histValues, msg.histMaxSize);
                mc.displayGuiScreen(new GuiTravellersConfig(msg.values, false));
            });
            return null;
        }
    }

    public static final class NoopHandler implements IMessageHandler<MsgOpenTravellersConfigGui, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenTravellersConfigGui msg, MessageContext ctx) { return null; }
    }
}
