package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.client.map.ClientItineraryDebugOverlay;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Debug reply to {@link MsgItineraryDebugRequest}: one unit's itinerary for the population map. */
public class MsgItineraryDebugMap implements IMessage {

    public static final int MAX_VERTICES = 4000;

    public boolean found;
    public UUID id;
    public String name = "";
    public boolean virtual;
    public double unitX, unitZ;
    @Nullable
    public BlockPos target;
    public List<BlockPos> tripStops = new ArrayList<>();
    public int tripIndex = -1;

    // Empty when the unit has no itinerary.
    public int[] xs = new int[0];
    public int[] zs = new int[0];
    public byte[] kinds = new byte[0];
    public double cursorX, cursorZ;
    public float offRoute, along, length;
    public String source = "";
    public byte quality;
    public boolean bridgesLeft;
    public boolean forTarget;

    public MsgItineraryDebugMap() {
    }

    static MsgItineraryDebugMap none() {
        return new MsgItineraryDebugMap();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(found);
        if (!found) return;
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
        ByteBufUtils.writeUTF8String(buf, name == null ? "" : name);
        buf.writeBoolean(virtual);
        buf.writeDouble(unitX);
        buf.writeDouble(unitZ);
        MsgItineraryDebugRequest.writePos(buf, target);
        MsgItineraryDebugRequest.writeStops(buf, tripStops);
        buf.writeInt(tripIndex);
        buf.writeInt(xs.length);
        for (int i = 0; i < xs.length; i++) {
            buf.writeInt(xs[i]);
            buf.writeInt(zs[i]);
        }
        buf.writeBytes(kinds);
        buf.writeDouble(cursorX);
        buf.writeDouble(cursorZ);
        buf.writeFloat(offRoute);
        buf.writeFloat(along);
        buf.writeFloat(length);
        ByteBufUtils.writeUTF8String(buf, source);
        buf.writeByte(quality);
        buf.writeBoolean(bridgesLeft);
        buf.writeBoolean(forTarget);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        found = buf.readBoolean();
        if (!found) return;
        id = new UUID(buf.readLong(), buf.readLong());
        name = ByteBufUtils.readUTF8String(buf);
        virtual = buf.readBoolean();
        unitX = buf.readDouble();
        unitZ = buf.readDouble();
        target = MsgItineraryDebugRequest.readPos(buf);
        int ns = buf.readUnsignedByte();
        tripStops = new ArrayList<>(ns);
        for (int i = 0; i < ns; i++) tripStops.add(new BlockPos(buf.readInt(), 0, buf.readInt()));
        tripIndex = buf.readInt();
        int n = Math.min(MAX_VERTICES, buf.readInt());
        xs = new int[n];
        zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = buf.readInt();
            zs[i] = buf.readInt();
        }
        kinds = new byte[Math.max(0, n - 1)];
        buf.readBytes(kinds);
        cursorX = buf.readDouble();
        cursorZ = buf.readDouble();
        offRoute = buf.readFloat();
        along = buf.readFloat();
        length = buf.readFloat();
        source = ByteBufUtils.readUTF8String(buf);
        quality = buf.readByte();
        bridgesLeft = buf.readBoolean();
        forTarget = buf.readBoolean();
    }

    public static class ClientHandler implements IMessageHandler<MsgItineraryDebugMap, IMessage> {
        @Override
        public IMessage onMessage(MsgItineraryDebugMap msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> ClientItineraryDebugOverlay.accept(msg));
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgItineraryDebugMap, IMessage> {
        @Override
        public IMessage onMessage(MsgItineraryDebugMap msg, MessageContext ctx) {
            return null;
        }
    }
}
