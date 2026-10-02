package com.jubitus.travellers.server.net.message.workerplayer;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.jubitus.travellers.world.data.PlayerEnemyRelations;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;
import java.util.UUID;

/** Edits the sender's account-wide declared-enemy list (see PlayerEnemyRelations). */
public class MsgSetWorkerHostile implements IMessage {
    private String name;
    private boolean hostile;

    public MsgSetWorkerHostile() {
    }

    public MsgSetWorkerHostile(String name, boolean hostile) {
        this.name = name;
        this.hostile = hostile;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        name = ByteBufUtils.readUTF8String(buf);
        hostile = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, name);
        buf.writeBoolean(hostile);
    }

    public static class Handler implements IMessageHandler<MsgSetWorkerHostile, IMessage> {
        @Override
        public IMessage onMessage(MsgSetWorkerHostile msg, MessageContext ctx) {
            EntityPlayerMP sender = ctx.getServerHandler().player;

            sender.getServerWorld().addScheduledTask(() -> {
                if (msg.name == null) return;
                String raw = msg.name.trim();
                if (raw.isEmpty()) return;

                MinecraftServer server = sender.getServer();
                if (server == null) return;

                GameProfile gp;
                EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(raw);
                if (online != null) {
                    gp = online.getGameProfile();
                } else {
                    gp = server.getPlayerProfileCache().getGameProfileForUsername(raw);
                }

                if (gp == null || gp.getId() == null) {
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_PLAYER_NOT_FOUND, raw);
                    return;
                }

                UUID targetId = gp.getId();
                UUID ownerId = sender.getUniqueID();

                PlayerEnemyRelations.get(sender.world).setHostile(ownerId, targetId, msg.hostile);

                if (msg.hostile) {
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_ADDED, gp.getName());
                } else {
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_REMOVED, gp.getName());

                    // ALSO clear auto-retaliation local enemy from all LOADED workers of this owner
                    for (WorldServer ws : server.worlds) {
                        List<EntityTravellerWorker> mine = ws.getEntities(EntityTravellerWorker.class, ww ->
                                ww != null
                                        && ww.isEntityAlive()
                                        && ww.isHiredFollower()
                                        && ownerId.equals(ww.getFollowOwnerId())
                        );
                        for (EntityTravellerWorker ww : mine) {
                            ww.getHostility().removeLocalHostile(targetId);
                        }
                    }
                }

                // Refresh client list
                TravellerFollowNet.CHANNEL.sendTo(
                        MsgOpenWorkerSubGui.buildHostileListPacket(sender),
                        sender
                );
            });


            return null;
        }
    }

}
