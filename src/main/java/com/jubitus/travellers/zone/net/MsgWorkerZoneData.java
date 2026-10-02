package com.jubitus.travellers.zone.net;

import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerZoneDutyReceiver;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.follow.group.WorkerGroupService;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneType;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Server -> client snapshot for the Duty tab of the zone-assignment GUI: the player's Stables and
 * all of their workers (live + virtualized) with their current duty assignment. Kept as a separate
 * message/wire-format from {@link MsgWorkerBarracksData} on purpose - the Home tab's existing format
 * is never touched by this, and a third future zone type with its own duty gets its own message
 * triple the same way, rather than every zone type growing one shared, ever-widening struct.
 */
public class MsgWorkerZoneData implements IMessage {

    public static class StableEntry {
        public BlockPos center;
        public String name;
        public int assigned;  // palfreniers currently assigned here
        public int capacity;   // bed count - one palfrenier per bed, like a Barrack
    }

    public static class WorkerEntry {
        public UUID uuid;
        public String name;
        public String cultureId;
        public int textureIndex;
        public int ownerColor;
        @Nullable public BlockPos assignedCenter; // null = not on duty
        /** A leader or follower can't be assigned duty (see decision on solo-only assignment) - the
         * client uses this to grey the row out; the server re-validates independently on assign. */
        public boolean grouped;
        /** Current world position (live entity, virtual record, or pending-respawn center). Used
         * client-side only, to sort the unassigned list nearest-first when the GUI opens. */
        @Nullable public BlockPos pos;
    }

    public final List<StableEntry> stables = new ArrayList<>();
    public final List<WorkerEntry> workers = new ArrayList<>();

    @Nullable public String notification;

    public MsgWorkerZoneData() {}

    public static MsgWorkerZoneData buildFor(EntityPlayerMP player) {
        MsgWorkerZoneData msg = new MsgWorkerZoneData();
        UUID ownerId = player.getUniqueID();
        MinecraftServer srv = player.getServer();
        if (srv == null) return msg;

        Set<BlockPos> validStableCenters = new HashSet<>();
        for (WorldServer ws : srv.worlds) {
            for (PlayerZoneRecord r : PlayerZoneManager.get(ws).getByOwnerAndType(ownerId, ZoneType.STABLE)) {
                validStableCenters.add(r.center);
            }
        }

        Set<UUID> seen = new HashSet<>();
        for (WorldServer ws : srv.worlds) {
            for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class,
                    e -> e != null && e.isEntityAlive())) {
                UUID owner = w.getFollowOwnerId();
                if (owner == null) owner = w.followOwnerId;
                if (owner == null || !ownerId.equals(owner)) continue;
                boolean hired;
                try { hired = w.isHiredFollower(); } catch (Throwable t) { hired = false; }
                if (!hired) hired = w.hiredFollower;
                if (!hired) continue;

                WorkerEntry we = new WorkerEntry();
                we.uuid = w.getUniqueID();
                we.name = w.getName();
                we.cultureId = w.getCultureId();
                we.textureIndex = w.getTextureIndex();
                we.ownerColor = w.getOwnerMarkColor();
                BlockPos dutyPos = w.getDutyZoneCenter();
                we.assignedCenter = (dutyPos != null && validStableCenters.contains(dutyPos)) ? dutyPos : null;
                we.grouped = w.hasEscortLeader() || w.isVisualLeader();
                we.pos = w.getPosition();
                msg.workers.add(we);
                seen.add(we.uuid);
            }
        }

        WorldServer overworld = srv.getWorld(0);
        if (overworld != null) {
            VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);
            for (VirtualTravellerRecord r : vwm.getRecordsView()) {
                if (!r.isHiredFollower || r.followOwnerUUID == null
                        || !ownerId.equals(r.followOwnerUUID)) continue;
                if (seen.contains(r.uuid)) continue;
                NBTTagCompound nbt = r.entityNbt;
                WorkerEntry we = new WorkerEntry();
                we.uuid = r.uuid;
                we.name = (r.name != null && !r.name.isEmpty()) ? r.name
                        : (nbt != null && nbt.hasKey("CustomName") ? nbt.getString("CustomName") : "Worker");
                we.cultureId = nbt != null ? nbt.getString("CultureId") : "";
                we.textureIndex = nbt != null ? nbt.getInteger("TextureIdx") : 0;
                we.ownerColor = r.ownerColor;
                if (r.dutyZoneCenter != null && validStableCenters.contains(r.dutyZoneCenter)) {
                    we.assignedCenter = r.dutyZoneCenter;
                }
                we.grouped = r.escortLeaderUUID != null
                        || WorkerGroupService.countFollowers(srv, ownerId, r.uuid) > 0;
                we.pos = new BlockPos(r.posX, r.posY, r.posZ);
                msg.workers.add(we);
                seen.add(we.uuid);
            }
        }

        WorldServer pworld = player.getServerWorld();
        for (PlayerZoneRecord r : PlayerZoneManager.get(pworld).getByOwnerAndType(ownerId, ZoneType.STABLE)) {
            int assigned = 0;
            for (WorkerEntry we : msg.workers) {
                if (r.center.equals(we.assignedCenter)) assigned++;
            }
            StableEntry s = new StableEntry();
            s.center = r.center;
            s.name = (r.name == null || r.name.isEmpty()) ? "Stable" : r.name;
            s.assigned = assigned;
            s.capacity = r.getRolePositions(com.jubitus.travellers.zone.ZoneRoles.BED).size();
            msg.stables.add(s);
        }

        return msg;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int ns = buf.readInt();
        for (int i = 0; i < ns; i++) {
            StableEntry s = new StableEntry();
            s.center = BlockPos.fromLong(buf.readLong());
            s.name = ByteBufUtils.readUTF8String(buf);
            s.assigned = buf.readInt();
            s.capacity = buf.readInt();
            stables.add(s);
        }
        int nw = buf.readInt();
        for (int i = 0; i < nw; i++) {
            WorkerEntry w = new WorkerEntry();
            w.uuid = new UUID(buf.readLong(), buf.readLong());
            w.name = ByteBufUtils.readUTF8String(buf);
            w.cultureId = ByteBufUtils.readUTF8String(buf);
            w.textureIndex = buf.readInt();
            w.ownerColor = buf.readInt();
            boolean hasDuty = buf.readBoolean();
            w.assignedCenter = hasDuty ? BlockPos.fromLong(buf.readLong()) : null;
            w.grouped = buf.readBoolean();
            w.pos = buf.readBoolean() ? BlockPos.fromLong(buf.readLong()) : null;
            workers.add(w);
        }
        boolean hasNotif = buf.readBoolean();
        notification = hasNotif ? ByteBufUtils.readUTF8String(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(stables.size());
        for (StableEntry s : stables) {
            buf.writeLong(s.center.toLong());
            ByteBufUtils.writeUTF8String(buf, s.name == null ? "" : s.name);
            buf.writeInt(s.assigned);
            buf.writeInt(s.capacity);
        }
        buf.writeInt(workers.size());
        for (WorkerEntry w : workers) {
            buf.writeLong(w.uuid.getMostSignificantBits());
            buf.writeLong(w.uuid.getLeastSignificantBits());
            ByteBufUtils.writeUTF8String(buf, w.name == null ? "" : w.name);
            ByteBufUtils.writeUTF8String(buf, w.cultureId == null ? "" : w.cultureId);
            buf.writeInt(w.textureIndex);
            buf.writeInt(w.ownerColor);
            buf.writeBoolean(w.assignedCenter != null);
            if (w.assignedCenter != null) buf.writeLong(w.assignedCenter.toLong());
            buf.writeBoolean(w.grouped);
            buf.writeBoolean(w.pos != null);
            if (w.pos != null) buf.writeLong(w.pos.toLong());
        }
        buf.writeBoolean(notification != null && !notification.isEmpty());
        if (notification != null && !notification.isEmpty()) ByteBufUtils.writeUTF8String(buf, notification);
    }

    public static class ClientHandler implements IMessageHandler<MsgWorkerZoneData, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerZoneData msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IWorkerZoneDutyReceiver) {
                    ((IWorkerZoneDutyReceiver) screen).receiveZoneDutyData(msg);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgWorkerZoneData, IMessage> {
        @Override public IMessage onMessage(MsgWorkerZoneData msg, MessageContext ctx) { return null; }
    }
}
