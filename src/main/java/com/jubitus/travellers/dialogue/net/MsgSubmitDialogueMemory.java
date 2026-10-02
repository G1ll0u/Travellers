package com.jubitus.travellers.dialogue.net;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client -> server, fire-and-forget: a short fact list (and, since Part M, an optional rewritten life
 * summary) distilled from a just-closed conversation. The only memory-write path in this system - no
 * mid-conversation {@code remember_this} tool. Silently dropped if the entity has despawned or
 * virtualized by the time this arrives, or if memory is off server-side - a missed write here is
 * low-stakes, not worth chasing further consistency for.
 *
 * <p>{@code summary} is empty when the extraction call produced no {@code SUMMARY:} section (a
 * malformed reply, or the feature disabled client-side) - an empty string here means "leave the
 * traveller's current summary unchanged", never "clear it" (see
 * {@code GuiTravellerDialogue.extractMemoryOnClose}'s parsing, which never sends an explicit "blank
 * this out" signal).
 */
public class MsgSubmitDialogueMemory implements IMessage {

    private int entityId;
    private List<String> facts;
    private String summary;

    public MsgSubmitDialogueMemory() {
    }

    public MsgSubmitDialogueMemory(int entityId, List<String> facts, String summary) {
        this.entityId = entityId;
        this.facts = facts;
        this.summary = summary == null ? "" : summary;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.entityId = buf.readInt();
        int count = buf.readInt();
        this.facts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            facts.add(ByteBufUtils.readUTF8String(buf));
        }
        this.summary = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(entityId);
        int count = facts == null ? 0 : facts.size();
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            ByteBufUtils.writeUTF8String(buf, facts.get(i));
        }
        ByteBufUtils.writeUTF8String(buf, summary == null ? "" : summary);
    }

    public static class Handler implements IMessageHandler<MsgSubmitDialogueMemory, IMessage> {
        @Override
        public IMessage onMessage(MsgSubmitDialogueMemory msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> handle(msg, player));
            return null;
        }

        private void handle(MsgSubmitDialogueMemory msg, EntityPlayerMP player) {
            if (!TravellersConfig.DIALOGUE.memoryEnabled) return;
            World world = player.world;
            Entity e = world.getEntityByID(msg.entityId);
            if (!(e instanceof EntityTraveller)) return;
            EntityTraveller traveller = (EntityTraveller) e;
            String personalityId = traveller.getVoicePersonalityId();
            long now = world.getTotalWorldTime();
            if (msg.facts != null) {
                for (String fact : msg.facts) {
                    traveller.dialogueMemory.remember(personalityId, fact, now);
                }
            }
            if (TravellersConfig.DIALOGUE.memorySummaryEnabled && msg.summary != null && !msg.summary.isEmpty()) {
                int maxChars = Math.max(1, TravellersConfig.DIALOGUE.memorySummaryMaxChars);
                String summary = msg.summary.length() > maxChars ? msg.summary.substring(0, maxChars) : msg.summary;
                traveller.dialogueMemory.updateLifeSummary(summary);
            }
        }
    }
}
