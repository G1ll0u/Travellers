package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class MsgGroupMerge implements IMessage {

    private long srcMost, srcLeast;
    private boolean hasTarget;
    private long tgtMost, tgtLeast;

    public MsgGroupMerge() {}

    public MsgGroupMerge(UUID sourceLeaderUUID, UUID targetLeaderUUID) {
        srcMost = sourceLeaderUUID.getMostSignificantBits();
        srcLeast = sourceLeaderUUID.getLeastSignificantBits();
        hasTarget = targetLeaderUUID != null;
        if (hasTarget) {
            tgtMost = targetLeaderUUID.getMostSignificantBits();
            tgtLeast = targetLeaderUUID.getLeastSignificantBits();
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        srcMost = buf.readLong();
        srcLeast = buf.readLong();
        hasTarget = buf.readBoolean();
        if (hasTarget) {
            tgtMost = buf.readLong();
            tgtLeast = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(srcMost);
        buf.writeLong(srcLeast);
        buf.writeBoolean(hasTarget);
        if (hasTarget) {
            buf.writeLong(tgtMost);
            buf.writeLong(tgtLeast);
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupMerge, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupMerge msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                UUID sourceUUID = new UUID(msg.srcMost, msg.srcLeast);
                UUID targetUUID = msg.hasTarget ? new UUID(msg.tgtMost, msg.tgtLeast) : null;
                UUID ownerId = player.getUniqueID();
                MinecraftServer server = player.getServer();
                if (server == null) return;

                WorldServer overworld = server.worlds[0];
                VirtualTravellerManager vwm = VirtualTravellerManager.get(overworld);

                List<EntityTravellerWorker> followers = new ArrayList<>();
                for (WorldServer ws : server.worlds) {
                    ws.getEntities(EntityTravellerWorker.class, w ->
                            w != null && !w.isDead && w.isHiredFollower()
                                    && Objects.equals(w.getFollowOwnerId(), ownerId)
                                    && sourceUUID.equals(w.getEscortLeaderUUID())
                    ).forEach(followers::add);
                }
                for (EntityTravellerWorker w : followers) {
                    if (targetUUID == null) {
                        w.clearEscortLeader();
                        w.setFollowOrderActive(true);
                    } else {
                        w.setEscortLeaderUUID(targetUUID);
                        w.setFollowOrderActive(true);
                    }
                    w.getNavigator().clearPath();
                }

                for (WorldServer ws : server.worlds) {
                    Entity e = ws.getEntityFromUuid(sourceUUID);
                    if (e instanceof EntityTravellerWorker && !e.isDead) {
                        EntityTravellerWorker src = (EntityTravellerWorker) e;
                        if (src.isHiredFollower() && Objects.equals(src.getFollowOwnerId(), ownerId)) {
                            if (targetUUID == null) {
                                src.clearEscortLeader();
                                src.setFollowOrderActive(true);
                            } else {
                                src.setEscortLeaderUUID(targetUUID);
                                src.setFollowOrderActive(true);
                            }
                            src.getNavigator().clearPath();
                        }
                        break;
                    }
                }

                vwm.cmdGroupMerge(sourceUUID, targetUUID, ownerId);
            });
            return null;
        }
    }
}
