package com.jubitus.travellers.server.net.message.dialogue;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.server.dialogue.tools.DialogueToolRegistry;
import com.jubitus.travellers.server.net.handler.TravellerFollowNet;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client -> server: "run this dialogue tool call and send me the result" (see the tools/memory plan's
 * Task G4). Mirrors {@code MsgWorkerRequestVillageRelations}'s request/reply shape exactly. Resolving
 * {@code entityId} straight off the server world with no extra range check mirrors every other
 * "act on this entity id" message in the mod (e.g. {@code MsgHireWorker}) - the player is mid-dialogue
 * with it, same as every other GUI-driven interaction here.
 */
public class MsgDialogueToolQuery implements IMessage {

    private int requestId;
    private int entityId;
    private String toolName;
    private NBTTagCompound args;

    public MsgDialogueToolQuery() {
    }

    public MsgDialogueToolQuery(int requestId, int entityId, String toolName, NBTTagCompound args) {
        this.requestId = requestId;
        this.entityId = entityId;
        this.toolName = toolName;
        this.args = args;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.requestId = buf.readInt();
        this.entityId = buf.readInt();
        this.toolName = ByteBufUtils.readUTF8String(buf);
        this.args = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeInt(entityId);
        ByteBufUtils.writeUTF8String(buf, toolName == null ? "" : toolName);
        ByteBufUtils.writeTag(buf, args != null ? args : new NBTTagCompound());
    }

    public static class Handler implements IMessageHandler<MsgDialogueToolQuery, IMessage> {
        @Override
        public IMessage onMessage(MsgDialogueToolQuery msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private void handle(MsgDialogueToolQuery msg, EntityPlayerMP player) {
            World world = player.world;
            Entity e = world.getEntityByID(msg.entityId);
            String resultText;
            boolean error;
            if (!(e instanceof EntityTraveller)) {
                resultText = "That is not something you can ask right now.";
                error = true;
            } else {
                resultText = DialogueToolRegistry.dispatch(msg.toolName, (EntityTraveller) e, player,
                        msg.args != null ? msg.args : new NBTTagCompound());
                error = false;
            }
            TravellerFollowNet.sendToPlayer(player, new MsgDialogueToolResult(msg.requestId, resultText, error));
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgDialogueToolQuery, IMessage> {
        @Override
        public IMessage onMessage(MsgDialogueToolQuery message, MessageContext ctx) {
            return null;
        }
    }
}
