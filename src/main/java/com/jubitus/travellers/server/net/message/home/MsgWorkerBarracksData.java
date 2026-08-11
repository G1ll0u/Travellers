package com.jubitus.travellers.server.net.message.home;

import com.jubitus.travellers.client.gui.worldmap.receiver.IWorkerBarracksReceiver;
import com.jubitus.travellers.entity.entityAI.travel.travelcommon.TravellerHomeManager;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager;
import com.jubitus.travellers.utils.world.lonebar.LoneBarrackRecord;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> client snapshot for the barracks-assignment GUI: the player's lone barracks
 * and all of their workers (live + virtualized) with their current barrack assignment.
 */
public class MsgWorkerBarracksData implements IMessage {

    public static class BarrackEntry {
        public BlockPos center;
        public String name;
        public int beds;     // capacity
        public int assigned; // currently assigned workers
    }

    public static class WorkerEntry {
        public UUID uuid;
        public String name;
        public String cultureId;
        public int textureIndex;
        public int ownerColor;
        @Nullable public BlockPos assignedCenter; // null = unassigned
    }

    public final List<BarrackEntry> barracks = new ArrayList<>();
    public final List<WorkerEntry> workers = new ArrayList<>();

    /** Optional i18n key to display as an error/info notice in the GUI (null = none). */
    @Nullable public String notification;

    public MsgWorkerBarracksData() {}

    /** Build the snapshot for the given player from the server state. */
    public static MsgWorkerBarracksData buildFor(EntityPlayerMP player) {
        MsgWorkerBarracksData msg = new MsgWorkerBarracksData();
        UUID ownerId = player.getUniqueID();
        net.minecraft.server.MinecraftServer srv = player.getServer();
        if (srv == null) return msg;

        // Pre-collect valid lone barrack centers for this player across all worlds.
        // Any worker whose assignedCenter doesn't appear here has a stale/orphaned assignment
        // and must be treated as unassigned so the player can re-assign them.
        java.util.Set<BlockPos> validBarrackCenters = new java.util.HashSet<>();
        for (WorldServer ws : srv.worlds) {
            for (LoneBarrackRecord r : LoneBarrackManager.get(ws).getByOwner(ownerId)) {
                validBarrackCenters.add(r.center);
            }
        }

        // Live workers across all worlds (robust hired/owner checks, mirroring WorkerMapStream)
        java.util.Set<UUID> seen = new java.util.HashSet<>();
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
                BlockPos homePos = (w.getAssignedHomeType() == TravellerHomeManager.HomeType.LONE_BARRACK)
                        ? w.getAssignedHomePos() : null;
                we.assignedCenter = (homePos != null && validBarrackCenters.contains(homePos)) ? homePos : null;
                msg.workers.add(we);
                seen.add(we.uuid);
            }
        }

        // Virtualized workers (stored on the overworld VirtualTravellerManager)
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
                if (nbt != null && nbt.hasKey("HomeX") && nbt.getInteger("HomeType") == 2) {
                    BlockPos homePos = new BlockPos(nbt.getInteger("HomeX"), nbt.getInteger("HomeY"), nbt.getInteger("HomeZ"));
                    if (validBarrackCenters.contains(homePos)) we.assignedCenter = homePos;
                }
                msg.workers.add(we);
                seen.add(we.uuid);
            }
        }

        // Dead workers pending respawn in lone barracks — must be collected BEFORE reconciliation
        // so their bed assignments survive the retainAll pass below.
        for (WorldServer ws : srv.worlds) {
            com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager lbmPending =
                    com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager.get(ws);
            for (com.jubitus.travellers.utils.world.lonebar.LoneBarrackManager.PendingRespawn pr
                    : lbmPending.getPendingRespawnsView().values()) {
                if (!ownerId.equals(pr.ownerUUID)) continue;
                if (seen.contains(pr.workerUUID)) continue;
                NBTTagCompound nbt = pr.workerNbt;
                WorkerEntry we = new WorkerEntry();
                we.uuid = pr.workerUUID;
                we.name = nbt != null && nbt.hasKey("CustomName") ? nbt.getString("CustomName") : "Worker";
                we.cultureId = nbt != null ? nbt.getString("CultureId") : "";
                we.textureIndex = nbt != null ? nbt.getInteger("TextureIdx") : 0;
                we.ownerColor = com.jubitus.travellers.server.marker.WorkerOwnerVisualsSavedData.get(ws).getFor(ownerId).color;
                we.assignedCenter = pr.center;
                msg.workers.add(we);
                seen.add(pr.workerUUID);
            }
        }

        // Lone barracks owned by the player (per-world storage, current dimension).
        // Reconcile each barrack's bed assignments against the actual worker assignments.
        WorldServer pworld = player.getServerWorld();
        LoneBarrackManager lbMgr = LoneBarrackManager.get(pworld);
        for (LoneBarrackRecord r : lbMgr.getByOwner(ownerId)) {
            lbMgr.refreshBeds(pworld, ownerId, r.center); // rescan beds if loaded; prune broken-bed assignments

            java.util.Set<UUID> assignedHere = new java.util.HashSet<>();
            for (WorkerEntry we : msg.workers) {
                if (r.center.equals(we.assignedCenter)) assignedHere.add(we.uuid);
            }
            boolean changed = r.bedByWorker.keySet().retainAll(assignedHere);
            for (UUID wu : assignedHere) {
                if (r.bedByWorker.containsKey(wu)) continue;
                BlockPos free = r.pickFreeBed();
                if (free == null) break; // over capacity (e.g. beds broken): worker stays bedless
                r.bedByWorker.put(wu, free);
                changed = true;
            }
            if (changed) lbMgr.markDirty();

            BarrackEntry b = new BarrackEntry();
            b.center = r.center;
            b.name = (r.name == null || r.name.isEmpty()) ? "Barrack" : r.name;
            b.beds = r.bedPositions.size();
            b.assigned = assignedHere.size();
            msg.barracks.add(b);
        }

        return msg;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int nb = buf.readInt();
        for (int i = 0; i < nb; i++) {
            BarrackEntry b = new BarrackEntry();
            b.center = BlockPos.fromLong(buf.readLong());
            b.name = ByteBufUtils.readUTF8String(buf);
            b.beds = buf.readInt();
            b.assigned = buf.readInt();
            barracks.add(b);
        }
        int nw = buf.readInt();
        for (int i = 0; i < nw; i++) {
            WorkerEntry w = new WorkerEntry();
            w.uuid = new UUID(buf.readLong(), buf.readLong());
            w.name = ByteBufUtils.readUTF8String(buf);
            w.cultureId = ByteBufUtils.readUTF8String(buf);
            w.textureIndex = buf.readInt();
            w.ownerColor = buf.readInt();
            boolean hasHome = buf.readBoolean();
            w.assignedCenter = hasHome ? BlockPos.fromLong(buf.readLong()) : null;
            workers.add(w);
        }
        boolean hasNotif = buf.readBoolean();
        notification = hasNotif ? ByteBufUtils.readUTF8String(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(barracks.size());
        for (BarrackEntry b : barracks) {
            buf.writeLong(b.center.toLong());
            ByteBufUtils.writeUTF8String(buf, b.name == null ? "" : b.name);
            buf.writeInt(b.beds);
            buf.writeInt(b.assigned);
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
        }
        buf.writeBoolean(notification != null && !notification.isEmpty());
        if (notification != null && !notification.isEmpty()) ByteBufUtils.writeUTF8String(buf, notification);
    }

    public static class ClientHandler implements IMessageHandler<MsgWorkerBarracksData, IMessage> {
        @Override
        public IMessage onMessage(MsgWorkerBarracksData msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                GuiScreen screen = Minecraft.getMinecraft().currentScreen;
                if (screen instanceof IWorkerBarracksReceiver) {
                    ((IWorkerBarracksReceiver) screen).receiveBarracksData(msg);
                }
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgWorkerBarracksData, IMessage> {
        @Override public IMessage onMessage(MsgWorkerBarracksData msg, MessageContext ctx) { return null; }
    }
}
