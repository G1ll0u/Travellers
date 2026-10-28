package com.jubitus.travellers.combat.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.combat.PlayerEnemyRelations;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/** Edits the sender's account-wide declared-enemy list (see PlayerEnemyRelations). */
public class MsgSetWorkerHostile implements IMessage {
    private UUID target;
    private boolean hostile;

    public MsgSetWorkerHostile() {
    }

    public MsgSetWorkerHostile(UUID target, boolean hostile) {
        this.target = target;
        this.hostile = hostile;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        target = new UUID(buf.readLong(), buf.readLong());
        hostile = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(target.getMostSignificantBits());
        buf.writeLong(target.getLeastSignificantBits());
        buf.writeBoolean(hostile);
    }

    public static class Handler implements IMessageHandler<MsgSetWorkerHostile, IMessage> {
        @Override
        public IMessage onMessage(MsgSetWorkerHostile msg, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().player;

            sender.getServerWorld().addScheduledTask(() -> {
                MinecraftServer server = sender.getServer();
                if (server == null) return;

                UUID targetId = msg.target;
                UUID ownerId = sender.getUniqueID();
                GameProfile gp = server.getPlayerProfileCache().getProfileByUUID(targetId);
                String targetName = gp != null && gp.getName() != null ? gp.getName() : targetId.toString();

                PlayerEnemyRelations.get(sender.world).setHostile(ownerId, targetId, msg.hostile);

                if (msg.hostile) {
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_ADDED, targetName);
                } else {
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_REMOVED, targetName);
                    // Peace holds: drop the 2-minute retaliation window of loaded workers too.
                    for (WorldServer ws : server.worlds) {
                        for (EntityTravellerWorker ww : ws.getEntities(EntityTravellerWorker.class,
                                e -> e != null && ownerId.equals(e.getFollowOwnerId()))) {
                            ww.playerHostility.clearEngagement(targetId);
                        }
                    }
                }


                // Refresh client list
                TravellersNetwork.CHANNEL.sendTo(
                        MsgSyncWorkerHostileList.build(sender),
                        sender
                );
            });


            return null;
        }
    }

}
