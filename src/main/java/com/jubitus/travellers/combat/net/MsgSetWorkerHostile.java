package com.jubitus.travellers.combat.net;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.net.TravellersNetwork;
import com.jubitus.travellers.worker.net.MsgOpenWorkerSubGui;
import com.jubitus.travellers.combat.PlayerEnemyRelations;
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
                        MsgOpenWorkerSubGui.buildHostileListPacket(sender),
                        sender
                );
            });


            return null;
        }
    }

}
