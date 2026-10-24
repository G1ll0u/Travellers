package com.jubitus.travellers.follow.net;

import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.pathing.itinerary.ItineraryState;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.WorkerLookup;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** Formation Hub → server: the planned route still ahead of one of the player's workers; reply {@link MsgGroupRoute}. */
public class MsgGroupRouteRequest implements IMessage {

    private UUID workerId;

    public MsgGroupRouteRequest() {
    }

    public MsgGroupRouteRequest(UUID workerId) {
        this.workerId = workerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(workerId.getMostSignificantBits());
        buf.writeLong(workerId.getLeastSignificantBits());
    }

    public static class Handler implements IMessageHandler<MsgGroupRouteRequest, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupRouteRequest msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() ->
                    TravellersNetwork.CHANNEL.sendTo(describe(player, msg.workerId), player));
            return null;
        }
    }

    private static MsgGroupRoute describe(EntityPlayerMP player, UUID id) {
        WorkerLookup.Owned o = WorkerLookup.findOwned(player.getServer(), player.getUniqueID(), id);
        if (o == null) return new MsgGroupRoute(id, null, 0, 0);
        if (o.live != null) {
            EntityTravellerWorker w = o.live;
            ItineraryState st = w.getItineraryState();
            boolean planned = w.dimension == player.dimension && w.isItineraryDriven() && st.isFor(w.getVirtualGoal());
            return new MsgGroupRoute(id, planned ? st : null, w.posX, w.posZ);
        }
        boolean planned = o.record.dimension == player.dimension && VirtualTravellerManager.followsItinerary(o.record);
        return new MsgGroupRoute(id, planned ? o.record.itinerary : null, o.record.posX, o.record.posZ);
    }
}
