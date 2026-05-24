package com.jubitus.travellers.server.net.message.group;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.pathing.offscreen.VirtualWorkerManager;
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

public class MsgGroupComeBack implements IMessage {

    private boolean hasLeader;
    private long uuidMost, uuidLeast;

    public MsgGroupComeBack() {}

    public MsgGroupComeBack(UUID leaderUUID) {
        this.hasLeader = leaderUUID != null;
        if (leaderUUID != null) {
            this.uuidMost = leaderUUID.getMostSignificantBits();
            this.uuidLeast = leaderUUID.getLeastSignificantBits();
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        hasLeader = buf.readBoolean();
        if (hasLeader) {
            uuidMost = buf.readLong();
            uuidLeast = buf.readLong();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(hasLeader);
        if (hasLeader) {
            buf.writeLong(uuidMost);
            buf.writeLong(uuidLeast);
        }
    }

    public static class Handler implements IMessageHandler<MsgGroupComeBack, IMessage> {
        @Override
        public IMessage onMessage(MsgGroupComeBack msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (!msg.hasLeader) return;
                UUID leaderUUID = new UUID(msg.uuidMost, msg.uuidLeast);
                UUID ownerId = player.getUniqueID();
                MinecraftServer server = player.getServer();
                if (server == null) return;

                WorldServer overworld = server.worlds[0];
                VirtualWorkerManager vwm = VirtualWorkerManager.get(overworld);

                List<EntityTravellerWorker> toUpdate = new ArrayList<>();
                for (WorldServer ws : server.worlds) {
                    ws.getEntities(EntityTravellerWorker.class, w ->
                            w != null && !w.isDead && w.isHiredFollower()
                                    && Objects.equals(w.getFollowOwnerId(), ownerId)
                                    && leaderUUID.equals(w.getEscortLeaderUUID())
                    ).forEach(toUpdate::add);
                }
                for (EntityTravellerWorker w : toUpdate) {
                    w.clearEscortLeader();
                    w.setFollowOrderActive(true);
                    w.getNavigator().clearPath();
                }

                EntityTravellerWorker leaderEntity = null;
                for (WorldServer ws : server.worlds) {
                    Entity e = ws.getEntityFromUuid(leaderUUID);
                    if (e instanceof EntityTravellerWorker && !e.isDead) {
                        leaderEntity = (EntityTravellerWorker) e;
                        break;
                    }
                }
                if (leaderEntity != null
                        && leaderEntity.isHiredFollower()
                        && Objects.equals(leaderEntity.getFollowOwnerId(), ownerId)) {
                    leaderEntity.clearEscortLeader();
                    leaderEntity.setFollowOrderActive(true);
                    leaderEntity.getNavigator().clearPath();
                }

                vwm.cmdGroupComeBack(leaderUUID, ownerId);
            });
            return null;
        }
    }
}
