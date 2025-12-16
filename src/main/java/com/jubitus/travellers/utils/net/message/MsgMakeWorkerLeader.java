package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
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

                // All hired workers of this owner
                List<EntityTravellerWorker> all = world.getEntities(
                        EntityTravellerWorker.class,
                        w -> w != null
                                && !w.isDead
                                && w.isHiredFollower()
                                && Objects.equals(w.getFollowOwnerId(), ownerId)
                );

                // Does this leader currently have *any* followers?
                boolean hasFollowers = false;
                for (EntityTravellerWorker w : all) {
                    if (w == leader) continue;
                    if (w.isEscortingLeader(leader)) {
                        hasFollowers = true;
                        break;
                    }
                }

                if (hasFollowers) {
                    // 🔁 TOGGLE OFF:
                    //   Only workers that currently follow THIS leader
                    //   go back to following the player directly.
                    for (EntityTravellerWorker w : all) {
                        if (w == leader) continue;
                        if (w.isEscortingLeader(leader)) {
                            w.clearEscortLeader();
                        }
                    }

                    player.sendMessage(new TextComponentString(
                            TextFormatting.YELLOW + "This leader’s followers now follow you again."
                    ));
                } else {
                    // ✅ TOGGLE ON:
                    //   Only workers that currently follow the PLAYER directly
                    //   (no escortLeader) are assigned to this leader.
                    int count = 0;
                    for (EntityTravellerWorker w : all) {
                        if (w == leader) continue;

                        // Only steal direct followers of the player (no current leader)
                        if (!w.hasEscortLeader()) {
                            w.setEscortLeader(leader);
                            count++;
                        }
                    }

                    if (count > 0) {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.GREEN + "This worker is now leading " + count + " of your followers."
                        ));
                    } else {
                        player.sendMessage(new TextComponentString(
                                TextFormatting.YELLOW + "None of your followers were available to assign to this leader."
                        ));
                    }
                }
            });
            return null;
        }
    }
}

