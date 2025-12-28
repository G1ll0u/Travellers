package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.TravellersModConfig;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

// MsgMakeWorkerLeader
public class MsgMakeWorkerLeader implements IMessage {
    private int entityId;

    public MsgMakeWorkerLeader() {}
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

                // ✅ Radius-limited: only touch followers near the leader
                final int R = TravellersModConfig.recruitLeaderAssignRadius; // e.g. 32
                AxisAlignedBB box = leader.getEntityBoundingBox().grow(R, 6.0D, R);

                List<EntityTravellerWorker> nearby = world.getEntitiesWithinAABB(
                        EntityTravellerWorker.class,
                        box,
                        w -> w != null
                                && !w.isDead
                                && w != leader
                                && w.isHiredFollower()
                                && Objects.equals(w.getFollowOwnerId(), ownerId)
                                // recommended: don't mess with active jobs
                                && !w.isJobActive()
                                && !w.isWaitingForPickup()
                );

                // Does THIS leader currently have any nearby escorts?
                boolean hasFollowers = false;
                for (EntityTravellerWorker w : nearby) {
                    if (w.isEscortingLeader(leader)) {
                        hasFollowers = true;
                        break;
                    }
                }

                if (hasFollowers) {
                    // 🔁 TOGGLE OFF: only workers escorting THIS leader go back to player
                    int cleared = 0;
                    for (EntityTravellerWorker w : nearby) {
                        if (w.isEscortingLeader(leader)) {
                            w.clearEscortLeader();
                            cleared++;
                        }
                    }

                    player.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + "Released " + cleared + " followers back to you."
                    ));
                } else {
                    // ✅ TOGGLE ON: only nearby direct followers (no leader) get assigned
                    int count = 0;
                    for (EntityTravellerWorker w : nearby) {
                        if (!w.hasEscortLeader()) {
                            w.setEscortLeader(leader);
                            count++;
                        }
                    }

                    if (count > 0) {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.GREEN + "This worker is now leading " + count + " nearby followers."
                        ));
                    } else {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.YELLOW + "No nearby direct followers to assign."
                        ));
                    }
                }
            });
            return null;
        }
    }

}

