package com.jubitus.travellers.server.net.message.workerplayer;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.utils.mobs.PlayerHostility;
import com.jubitus.travellers.server.net.TravellerFollowNet;
import com.jubitus.travellers.server.net.message.MsgOpenWorkerSubGui;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.List;
import java.util.UUID;

public class MsgSetWorkerHostile implements IMessage {
    private int workerId;
    private String name;
    private boolean hostile;

    public MsgSetWorkerHostile() {
    }

    public MsgSetWorkerHostile(int workerId, String name, boolean hostile) {
        this.workerId = workerId;
        this.name = name;
        this.hostile = hostile;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        workerId = buf.readInt();
        name = ByteBufUtils.readUTF8String(buf);
        hostile = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(workerId);
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

                Entity ent = sender.world.getEntityByID(msg.workerId);
                if (!(ent instanceof EntityTravellerWorker)) return;

                EntityTravellerWorker worker = (EntityTravellerWorker) ent;

                // Only the owner can edit
                UUID ownerId = worker.getFollowOwnerId();
                if (ownerId == null || !ownerId.equals(sender.getUniqueID())) {
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_NOT_OWNER);
                    return;
                }

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

                // Always edit the hostility list OWNER (leader if loaded, else self)
                EntityTravellerWorker listOwner = worker.getHostility().getHostileListOwner();
                PlayerHostility hostility = listOwner.getHostility();

                if (msg.hostile) {
                    hostility.hostilePlayers.add(targetId);
                    NotificationService.notify(sender, NotificationDefs.HOSTILE_ADDED, gp.getName());
                } else {
                    hostility.hostilePlayers.remove(targetId);
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

                // Push declared list to loaded escorts (followers still mirror over time too)
                listOwner.syncHostileListToEscorts();

                // Refresh client list
                TravellerFollowNet.CHANNEL.sendTo(
                        MsgOpenWorkerSubGui.buildHostileListPacket(worker),
                        sender
                );
            });


            return null;
        }
    }

}
