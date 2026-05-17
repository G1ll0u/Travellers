package com.jubitus.travellers.server.net.message.leadfollow;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

// MsgMakeWorkerLeader
public class MsgMakeWorkerLeader implements IMessage {
    private int entityId;

    public MsgMakeWorkerLeader() {
    }

    public MsgMakeWorkerLeader(int entityId) {
        this.entityId = entityId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
    }

    public static class Handler implements IMessageHandler<MsgMakeWorkerLeader, IMessage> {
        @Override
        public IMessage onMessage(MsgMakeWorkerLeader msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                World world = player.world;
                Entity e = world.getEntityByID(msg.entityId);
                if (!(e instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker leader = (EntityTravellerWorker) e;

                // Only the hiring player can do this
                if (!leader.isHiredFollower()) return;
                if (!player.getUniqueID().equals(leader.getFollowOwnerId())) return;

                UUID ownerId = leader.getFollowOwnerId();
                UUID leaderId = leader.getUniqueID();

                MinecraftServer server = player.getServer();

// GLOBAL escorts of this leader (NO radius, used for RELEASE + toggle decision)
                int cleared = 0;
                int escortCount = 0;

// Count escorts globally
                for (WorldServer ws : server.worlds) { // keep this as-is since you're already using it
                    List<EntityTravellerWorker> escorts = ws.getEntities(EntityTravellerWorker.class, w ->
                            w != null
                                    && !w.isDead
                                    && w.isHiredFollower()
                                    && Objects.equals(w.getFollowOwnerId(), ownerId)
                                    && leaderId.equals(w.getEscortLeaderUUID())
                    );
                    escortCount += escorts.size();
                }

// TOGGLE: if there is ANY escort anywhere -> RELEASE ALL of them
                if (escortCount > 0) {

                    for (WorldServer ws : server.worlds) {
                        List<EntityTravellerWorker> escorts = ws.getEntities(EntityTravellerWorker.class, w ->
                                w != null
                                        && !w.isDead
                                        && w.isHiredFollower()
                                        && Objects.equals(w.getFollowOwnerId(), ownerId)
                                        && leaderId.equals(w.getEscortLeaderUUID())
                        );

                        for (EntityTravellerWorker w : escorts) {
                            w.clearEscortLeader();
                            cleared++;
                        }
                    }

                    player.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + "Released " + cleared + " followers back to you."
                    ));

                } else {
                    // ASSIGN ALL free followers globally (NO radius)
                    int assigned = 0;

                    for (WorldServer ws : server.worlds) {
                        List<EntityTravellerWorker> freeFollowers = ws.getEntities(EntityTravellerWorker.class, w ->
                                w != null
                                        && !w.isDead
                                        && w != leader
                                        && w.isHiredFollower()
                                        && Objects.equals(w.getFollowOwnerId(), ownerId)
                                        && !w.isJobActive()
                                        && !w.isWaitingForPickup()
                                        && !w.hasEscortLeader() // only free followers
                        );

                        for (EntityTravellerWorker w : freeFollowers) {
                            w.setEscortLeader(leader);
                            assigned++;
                        }
                    }

                    if (assigned > 0) {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.GREEN + "This worker is now leading " + assigned + " followers."
                        ));
                    } else {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.YELLOW + "No free direct followers to assign."
                        ));
                    }
                }


            });
            return null;
        }
    }

}

