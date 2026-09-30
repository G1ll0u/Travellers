package com.jubitus.travellers.server.net.message.map;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.world.pathing.itinerary.Itinerary;
import com.jubitus.travellers.world.pathing.itinerary.ItineraryState;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Debug: the population map asks for one unit's itinerary, either the unit nearest a clicked point
 * or an already-selected unit (live refresh). Loaded and virtual units alike. Reply is
 * {@link MsgItineraryDebugMap}.
 */
public class MsgItineraryDebugRequest implements IMessage {

    public int x;
    public int z;
    public int radius;
    @Nullable
    public UUID selected;

    public MsgItineraryDebugRequest() {
    }

    public MsgItineraryDebugRequest(int x, int z, int radius, @Nullable UUID selected) {
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.selected = selected;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        z = buf.readInt();
        radius = buf.readInt();
        selected = buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(z);
        buf.writeInt(radius);
        buf.writeBoolean(selected != null);
        if (selected != null) {
            buf.writeLong(selected.getMostSignificantBits());
            buf.writeLong(selected.getLeastSignificantBits());
        }
    }

    public static class Handler implements IMessageHandler<MsgItineraryDebugRequest, IMessage> {
        @Override
        public IMessage onMessage(MsgItineraryDebugRequest msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer world = player.getServerWorld();
            world.addScheduledTask(() -> {
                MsgItineraryDebugMap reply = msg.selected != null
                        ? describe(world, msg.selected)
                        : describeNearest(world, msg.x, msg.z, Math.max(4, Math.min(512, msg.radius)));
                TravellerFollowNet.CHANNEL.sendTo(reply != null ? reply : MsgItineraryDebugMap.none(), player);
            });
            return null;
        }
    }

    @Nullable
    private static MsgItineraryDebugMap describe(WorldServer world, UUID id) {
        VirtualTravellerRecord r = VirtualTravellerManager.get(world).getRecord(id);
        if (r != null && r.dimension == world.provider.getDimension()) return fromRecord(r);
        Entity e = world.getEntityFromUuid(id);
        if (e instanceof EntityTraveller && !e.isDead) return fromEntity((EntityTraveller) e);
        return null;
    }

    @Nullable
    private static MsgItineraryDebugMap describeNearest(WorldServer world, int x, int z, int radius) {
        double best = (double) radius * radius;
        VirtualTravellerRecord bestRec = null;
        EntityTraveller bestEnt = null;
        int dim = world.provider.getDimension();
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(world).getAllRecords()) {
            if (r.dimension != dim) continue;
            double d = (r.posX - x) * (r.posX - x) + (r.posZ - z) * (r.posZ - z);
            if (d < best) {
                best = d;
                bestRec = r;
            }
        }
        for (Entity e : world.loadedEntityList) {
            if (!(e instanceof EntityTraveller) || e.isDead) continue;
            double d = (e.posX - x) * (e.posX - x) + (e.posZ - z) * (e.posZ - z);
            if (d < best) {
                best = d;
                bestEnt = (EntityTraveller) e;
                bestRec = null;
            }
        }
        if (bestEnt != null) return fromEntity(bestEnt);
        if (bestRec != null) return fromRecord(bestRec);
        return null;
    }

    private static MsgItineraryDebugMap fromRecord(VirtualTravellerRecord r) {
        MsgItineraryDebugMap m = new MsgItineraryDebugMap();
        m.found = true;
        m.id = r.uuid;
        m.name = r.name;
        m.virtual = true;
        m.unitX = r.posX;
        m.unitZ = r.posZ;
        m.tripStops = r.tripStops;
        m.tripIndex = r.tripIndex;
        m.target = r.personalTravelGoal != null ? r.personalTravelGoal : r.currentTripStop();
        fill(m, r.itinerary, r.posX, r.posZ);
        return m;
    }

    private static MsgItineraryDebugMap fromEntity(EntityTraveller e) {
        MsgItineraryDebugMap m = new MsgItineraryDebugMap();
        m.found = true;
        m.id = e.getUniqueID();
        m.name = e.getName();
        m.virtual = false;
        m.unitX = e.posX;
        m.unitZ = e.posZ;
        m.tripStops = e.getTravelRoute();
        m.tripIndex = e.getVillageRouteIndex();
        m.target = e.getTargetVillage();
        fill(m, e.getItineraryState(), e.posX, e.posZ);
        return m;
    }

    private static void fill(MsgItineraryDebugMap m, ItineraryState st, double x, double z) {
        Itinerary it = st.get();
        if (it == null) return;
        int n = Math.min(it.size(), MsgItineraryDebugMap.MAX_VERTICES);
        m.xs = new int[n];
        m.zs = new int[n];
        m.kinds = new byte[Math.max(0, n - 1)];
        for (int i = 0; i < n; i++) {
            m.xs[i] = it.x(i);
            m.zs[i] = it.z(i);
            if (i < n - 1) m.kinds[i] = it.kind(i);
        }
        // A copy so the diagnostic never moves the unit's own cursor.
        com.jubitus.travellers.world.pathing.itinerary.ItineraryCursor c =
                new com.jubitus.travellers.world.pathing.itinerary.ItineraryCursor();
        net.minecraft.nbt.NBTTagCompound tmp = new net.minecraft.nbt.NBTTagCompound();
        st.cursor.writeNbt(tmp);
        c.readNbt(tmp, it);
        c.computeCarrot(it, 0.0);
        m.cursorX = c.carrotX;
        m.cursorZ = c.carrotZ;
        c.advance(it, x, z);
        m.offRoute = (float) c.offRouteDistance();
        m.along = (float) st.cursor.along();
        m.length = (float) it.totalLength();
        m.source = it.source + (st.isForced() ? " (forced)" : "");
        m.quality = it.quality;
        m.bridgesLeft = st.hasBacklog();
        m.forTarget = st.isFor(m.target);
    }

    static void writePos(ByteBuf buf, @Nullable BlockPos p) {
        buf.writeBoolean(p != null);
        if (p != null) {
            buf.writeInt(p.getX());
            buf.writeInt(p.getZ());
        }
    }

    @Nullable
    static BlockPos readPos(ByteBuf buf) {
        return buf.readBoolean() ? new BlockPos(buf.readInt(), 0, buf.readInt()) : null;
    }

    static void writeStops(ByteBuf buf, @Nullable List<BlockPos> stops) {
        int n = stops == null ? 0 : Math.min(64, stops.size());
        buf.writeByte(n);
        for (int i = 0; i < n; i++) {
            buf.writeInt(stops.get(i).getX());
            buf.writeInt(stops.get(i).getZ());
        }
    }
}
