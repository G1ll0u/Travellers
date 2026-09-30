package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

/**
 * One source of facts for a {@link DialogueContext}. {@link DialogueContextBuilder} runs every
 * registered provider in order against a single {@link DialogueContext.Builder} — this is the whole
 * extension seam for a future context source (persistent per-NPC memory, say) to be added later
 * with zero changes to the builder or to anything downstream of it. Phase 1 shipped exactly one
 * provider ({@link AmbientActivityDialogueContextProvider}); Phase 2 (context-expansion plan) adds
 * several more, each reading exactly one concern instead of growing that one class further.
 *
 * <p>{@code player} was added in Phase 2 so a provider can read the interacting player's own state
 * (see the plan's E6) — every Phase 1 provider ignores it.
 */
public interface DialogueContextProvider {

    void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder);
}
