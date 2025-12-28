package com.jubitus.travellers.utils.net.message;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.net.TravellerFollowNet;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

public class MsgSetWorkerHostile implements IMessage {
    private int workerId;
    private String name;
    private boolean hostile;

    public MsgSetWorkerHostile() {}

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
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "You don't own this worker."));
                    return;
                }

                EntityTravellerWorker leader = worker.getGroupLeaderOrSelf();

                MinecraftServer server = sender.getServer();
                GameProfile gp = null;

                EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(raw);
                if (online != null) {
                    gp = online.getGameProfile();
                } else {
                    gp = server.getPlayerProfileCache().getGameProfileForUsername(raw);
                }

                if (gp == null || gp.getId() == null) {
                    sender.sendMessage(new TextComponentString(
                            TextFormatting.RED + "Player '" + raw + "' not found. They must have joined the server at least once."
                    ));
                    return;
                }

                UUID targetId = gp.getId();

                if (msg.hostile) {
                    leader.hostilePlayers.add(targetId);
                    sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Workers will attack: " + gp.getName()));
                } else {
                    leader.hostilePlayers.remove(targetId);
                    sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "Removed enemy: " + gp.getName()));
                }

                // ✅ PATCH HERE: after mutating the set, sync the updated list to the client
                TravellerFollowNet.CHANNEL.sendTo(
                        MsgOpenWorkerSubGui.buildHostileListPacket(worker),
                        sender
                );
            });

            return null;
        }
    }

}
