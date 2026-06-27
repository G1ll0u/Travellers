package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerRecord;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import javax.annotation.Nullable;
import java.util.*;

public class MsgGroupCreate implements IMessage {

    public enum Action { CREATE_GROUP, COME_BACK, ASSIGN_TO_LEADER }

    private Action action;
    private List<UUID> workerUUIDs = new ArrayList<>();
    @Nullable private UUID targetLeaderUUID; // used for ASSIGN_TO_LEADER

    public MsgGroupCreate() {}

    public MsgGroupCreate(Action action, List<UUID> uuids, @Nullable UUID targetLeaderUUID) {
        this.action = action;
        this.workerUUIDs = new ArrayList<>(uuids);
        this.targetLeaderUUID = targetLeaderUUID;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int actionOrd = buf.readByte();
        action = Action.values()[actionOrd < Action.values().length ? actionOrd : 0];
        int n = buf.readInt();
        workerUUIDs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            workerUUIDs.add(new UUID(buf.readLong(), buf.readLong()));
        }
        boolean hasLeader = buf.readBoolean();
        if (hasLeader) targetLeaderUUID = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action == null ? 0 : action.ordinal());
        buf.writeInt(workerUUIDs.size());
        for (UUID u : workerUUIDs) {
            buf.writeLong(u.getMostSignificantBits());
            buf.writeLong(u.getLeastSignificantBits());
        }
        buf.writeBoolean(targetLeaderUUID != null);
        if (targetLeaderUUID != null) {
            buf.writeLong(targetLeaderUUID.getMostSignificantBits());
            buf.writeLong(targetLeaderUUID.getLeastSignificantBits());
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupCreate, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupCreate msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (msg.workerUUIDs.isEmpty() || msg.action == null) return;
                UUID ownerId = player.getUniqueID();
                MinecraftServer server = player.getServer();
                if (server == null) return;

                Set<UUID> targetSet = new HashSet<>(msg.workerUUIDs);
                WorldServer overworld = server.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);

                // Collect matching real workers
                Map<UUID, EntityTravellerWorker> realWorkers = new HashMap<>();
                for (WorldServer ws : server.worlds) {
                    for (EntityTravellerWorker w : ws.getEntities(EntityTravellerWorker.class,
                            e -> e != null && !e.isDead && e.isHiredFollower()
                                    && ownerId.equals(e.getFollowOwnerId())
                                    && targetSet.contains(e.getUniqueID()))) {
                        realWorkers.put(w.getUniqueID(), w);
                    }
                }

                // Collect matching virtual workers
                Map<UUID, VirtualTravellerRecord> virtualWorkers = new HashMap<>();
                for (VirtualTravellerRecord vr : vwm.getRecordsView()) {
                    if (vr.isHiredFollower && ownerId.equals(vr.followOwnerUUID) && targetSet.contains(vr.uuid)) {
                        virtualWorkers.put(vr.uuid, vr);
                    }
                }

                if (realWorkers.isEmpty() && virtualWorkers.isEmpty()) return;

                if (msg.action == Action.COME_BACK) {
                    for (EntityTravellerWorker w : realWorkers.values()) {
                        w.clearEscortLeader();
                        w.setFollowOrderActive(true);
                        w.getNavigator().clearPath();
                    }
                    for (VirtualTravellerRecord vr : virtualWorkers.values()) {
                        vr.escortLeaderUUID = null;
                        vr.followOrderActive = true;
                        vr.goOrderActive = false;
                    }
                    vwm.markDirty();
                    return;
                }

                if (msg.action == Action.ASSIGN_TO_LEADER) {
                    UUID leaderUUID = msg.targetLeaderUUID;
                    if (leaderUUID == null) return;
                    for (EntityTravellerWorker w : realWorkers.values()) {
                        w.setEscortLeaderUUID(leaderUUID);
                        w.setFollowOrderActive(true);
                        w.getNavigator().clearPath();
                    }
                    for (VirtualTravellerRecord vr : virtualWorkers.values()) {
                        vr.escortLeaderUUID = leaderUUID;
                        vr.followOrderActive = true;
                    }
                    vwm.markDirty();
                    return;
                }

                // CREATE_GROUP: pick best equipped as leader
                UUID leaderUUID = pickBestEquipped(realWorkers, virtualWorkers);
                if (leaderUUID == null) return;

                EntityTravellerWorker newLeaderEntity = null;
                for (Map.Entry<UUID, EntityTravellerWorker> entry : realWorkers.entrySet()) {
                    EntityTravellerWorker w = entry.getValue();
                    if (leaderUUID.equals(entry.getKey())) {
                        w.clearEscortLeader();
                        w.setFollowOrderActive(false);
                        w.getNavigator().clearPath();
                        newLeaderEntity = w;
                    } else {
                        w.setEscortLeaderUUID(leaderUUID);
                        w.setFollowOrderActive(true);
                        w.getNavigator().clearPath();
                    }
                }

                for (Map.Entry<UUID, VirtualTravellerRecord> entry : virtualWorkers.entrySet()) {
                    VirtualTravellerRecord vr = entry.getValue();
                    if (leaderUUID.equals(entry.getKey())) {
                        vr.escortLeaderUUID = null;
                        vr.followOrderActive = false;
                    } else {
                        vr.escortLeaderUUID = leaderUUID;
                        vr.followOrderActive = true;
                    }
                }
                vwm.markDirty();

                // Force the leader's follower count cache to update immediately so the
                // next MsgRequestFormationHub (sent right after from the client) sees it.
                if (newLeaderEntity != null) {
                    newLeaderEntity.forceRefreshLeaderCount();
                }
            });
            return null;
        }

        private UUID pickBestEquipped(Map<UUID, EntityTravellerWorker> real,
                                       Map<UUID, VirtualTravellerRecord> virtual) {
            UUID best = null;
            int bestScore = -1;

            for (Map.Entry<UUID, EntityTravellerWorker> e : real.entrySet()) {
                int score = e.getValue().getTotalArmorValue();
                if (score > bestScore) { bestScore = score; best = e.getKey(); }
            }

            for (Map.Entry<UUID, VirtualTravellerRecord> e : virtual.entrySet()) {
                int score = armorScoreFromNbt(e.getValue());
                if (score > bestScore) { bestScore = score; best = e.getKey(); }
            }

            if (best == null) {
                if (!real.isEmpty()) return real.keySet().iterator().next();
                if (!virtual.isEmpty()) return virtual.keySet().iterator().next();
            }
            return best;
        }

        private int armorScoreFromNbt(VirtualTravellerRecord vr) {
            if (vr.entityNbt == null || !vr.entityNbt.hasKey("ArmorItems")) return 0;
            NBTTagList armorList = vr.entityNbt.getTagList("ArmorItems", 10);
            int score = 0;
            for (int i = 0; i < armorList.tagCount(); i++) {
                ItemStack s = new ItemStack(armorList.getCompoundTagAt(i));
                if (!s.isEmpty() && s.getItem() instanceof ItemArmor) {
                    score += ((ItemArmor) s.getItem()).damageReduceAmount;
                }
            }
            return score;
        }
    }
}
