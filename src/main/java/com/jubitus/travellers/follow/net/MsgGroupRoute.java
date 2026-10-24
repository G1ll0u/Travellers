package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.follow.gui.FormationRouteOverlay;
import com.jubitus.travellers.pathing.itinerary.Itinerary;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.UUID;

/** Reply to {@link MsgGroupRouteRequest}: the worker's position, then the itinerary vertices still ahead (none when it has no planned route). */
public class MsgGroupRoute implements IMessage {

    private static final int MAX_VERTICES = 2000;

    public UUID workerId;
    public int[] xs = new int[0];
    public int[] zs = new int[0];

    public MsgGroupRoute() {
    }

    MsgGroupRoute(UUID workerId, @Nullable ItineraryState st, double x, double z) {
        this.workerId = workerId;
        Itinerary it = st != null ? st.get() : null;
        if (it == null) return;
        int from = Math.max(0, st.cursor.segment() + 1);
        int n = Math.min(MAX_VERTICES, it.size() - from + 1);
        if (n < 2) return;
        xs = new int[n];
        zs = new int[n];
        xs[0] = (int) Math.floor(x);
        zs[0] = (int) Math.floor(z);
        for (int i = 1; i < n; i++) {
            xs[i] = it.x(from + i - 1);
            zs[i] = it.z(from + i - 1);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = new UUID(buf.readLong(), buf.readLong());
        int n = buf.readInt();
        xs = new int[n];
        zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = buf.readInt();
            zs[i] = buf.readInt();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerId.getMostSignificantBits());
        buf.writeLong(workerId.getLeastSignificantBits());
        buf.writeInt(xs.length);
        for (int i = 0; i < xs.length; i++) {
            buf.writeInt(xs[i]);
            buf.writeInt(zs[i]);
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupRoute, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupRoute msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> FormationRouteOverlay.accept(msg));
            return null;
        }
    }
}
