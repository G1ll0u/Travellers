package com.jubitus.travellers.dialogue.tools;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;

/**
 * One server-side tool a player's local model can call mid-conversation (see
 * {@code docs/npc-dialogue-tools-memory-plan.md} decision G-4). Every implementation must return
 * short, bucketed, in-character-safe text — the same grounding discipline {@code DialogueContext}
 * already follows — since the result becomes part of the model's context for the rest of the round.
 */
public interface DialogueToolHandler {

    /** Server thread. {@code args} is whatever the model supplied, resolved best-effort. */
    String handle(EntityTraveller traveller, EntityPlayer player, NBTTagCompound args);
}
