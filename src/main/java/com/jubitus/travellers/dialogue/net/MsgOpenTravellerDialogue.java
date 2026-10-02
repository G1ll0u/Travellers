package com.jubitus.travellers.dialogue.net;

import com.jubitus.travellers.dialogue.gui.GuiTravellerDialogue;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.dialogue.DialogueContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server → client, one shot: opens the dialogue GUI for one traveller, carrying the entity id (to
 * resolve the live, already-loaded {@code EntityTraveller} for the portrait — the interacting
 * player is standing right next to it, same tick, so this is exactly as safe as every other
 * "open a GUI for this entity" message in the mod, see {@code MsgOpenClientWorkerGui}) and the
 * already-built {@link DialogueContext} in the same payload — no separate request/response round
 * trip, the server already has everything by the time it decides to send this (see the dialogue
 * plan's D-9). No streaming here: token-by-token generation is a purely client-local concern
 * against Ollama (see {@code OllamaClient}), not a network message.
 *
 * <p>The context itself is carried as one {@link DialogueContext#toNbt()} tag (mirrors
 * {@code MsgJournalEntryAdded}'s idiom) rather than fields mirrored by hand here — an earlier version
 * of this class did that and silently dropped every field two rounds of context-expansion work added,
 * because nothing forced the wire format to be touched when {@link DialogueContext} grew a field.
 */
public class MsgOpenTravellerDialogue implements IMessage {

    private int entityId;
    private NBTTagCompound contextTag;

    public MsgOpenTravellerDialogue() {
    }

    public MsgOpenTravellerDialogue(int entityId, DialogueContext context) {
        this.entityId = entityId;
        this.contextTag = context.toNbt();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        this.contextTag = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        ByteBufUtils.writeTag(buf, contextTag != null ? contextTag : new NBTTagCompound());
    }

    public static class Handler implements IMessageHandler<MsgOpenTravellerDialogue, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenTravellerDialogue msg, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                EntityPlayer player = mc.player;
                World world = mc.world;
                if (player == null || world == null) return;

                Entity e = world.getEntityByID(msg.entityId);
                EntityTraveller traveller = (e instanceof EntityTraveller) ? (EntityTraveller) e : null;
                DialogueContext context = DialogueContext.fromNbt(msg.contextTag != null ? msg.contextTag : new NBTTagCompound());
                mc.displayGuiScreen(new GuiTravellerDialogue(player, traveller, context));
            });
            return null;
        }
    }

    public static class NoopHandler implements IMessageHandler<MsgOpenTravellerDialogue, IMessage> {
        @Override
        public IMessage onMessage(MsgOpenTravellerDialogue msg, MessageContext ctx) {
            return null;
        }
    }
}
