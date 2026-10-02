package com.jubitus.travellers.server.dialogue.tools;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

import java.util.List;

/**
 * The {@code recall_memory} dialogue tool: best-effort keyword/substring match against the
 * traveller's whole global archive-tier memory (identity-memory plan, Part M - not scoped to the
 * interacting player) - the index tier is already always in the prompt, nothing to fetch there. No
 * embedding/semantic search (accepted v1 limitation, matching weakness is fine for a system that's
 * meant to be imperfect) - never returns silently empty, always a graceful in-character-safe line
 * either way.
 */
public final class MemoryRecallToolHandler implements DialogueToolHandler {

    private static final int MAX_MATCHES = 3;

    @Override
    public String handle(EntityTraveller traveller, EntityPlayer player, NBTTagCompound args) {
        String topic = args.getString("topic");
        List<String> matches = traveller.dialogueMemory.recall(topic);
        if (matches.isEmpty()) return "Nothing comes to mind about that.";
        List<String> capped = matches.size() > MAX_MATCHES ? matches.subList(0, MAX_MATCHES) : matches;
        return String.join("; ", capped);
    }
}
