package com.jubitus.travellers.debug.net;

import com.jubitus.travellers.debug.client.ClientTraceHud;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -&gt; client: what the on-screen debug panel shows for the travellers currently being traced.
 *
 * <p>The lines arrive already formatted. The server is the only side that can see navigator state,
 * running AI tasks and the horse's collision flags at all, so shipping text costs one small packet a
 * few times a second and keeps a rendering class from needing any of that. Entity ids ride along so
 * the traced individuals can be marked in the world - identifying <em>which</em> of a dozen riders is
 * the one under investigation is half the point of the panel.
 */
public class MsgTraceHud implements IMessage {

    private List<String> lines = new ArrayList<>();
    private int[] tracedIds = new int[0];

    public MsgTraceHud() {
    }

    public MsgTraceHud(List<String> lines, int[] tracedIds) {
        this.lines = lines;
        this.tracedIds = tracedIds;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int count = buf.readUnsignedByte();
        lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(ByteBufUtils.readUTF8String(buf));
        }
        tracedIds = new int[buf.readUnsignedByte()];
        for (int i = 0; i < tracedIds.length; i++) {
            tracedIds[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int count = Math.min(lines.size(), 255);
        buf.writeByte(count);
        for (int i = 0; i < count; i++) {
            ByteBufUtils.writeUTF8String(buf, lines.get(i));
        }
        int ids = Math.min(tracedIds.length, 255);
        buf.writeByte(ids);
        for (int i = 0; i < ids; i++) {
            buf.writeInt(tracedIds[i]);
        }
    }

    public static class Handler implements IMessageHandler<MsgTraceHud, IMessage> {
        @Override
        public IMessage onMessage(MsgTraceHud msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientTraceHud.accept(msg.lines, msg.tracedIds));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgTraceHud, IMessage> {
        @Override
        public IMessage onMessage(MsgTraceHud msg, MessageContext ctx) {
            return null;
        }
    }
}
