package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Index-tier NPC memory about the interacting player specifically (see
 * {@code docs/npc-dialogue-tools-memory-plan.md} Task J1). {@link DialogueContext#memoryEnabled} is
 * always set regardless of the switch's own value, so the client can gate the end-of-conversation
 * extraction without needing its own copy of this server-authoritative config (M-6).
 */
public final class MemoryDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        // Always computed, unlike everything below - "have we met" is basic conversational realism,
        // not the opt-in fact-memory feature (see EntityTraveller.processInteract for where this gets
        // marked true, always after the context for THIS visit is already built).
        boolean metBefore = traveller.dialogueMemory.hasMetBefore(player.getUniqueID());
        builder.metPlayerBefore(metBefore);
        if (metBefore) {
            long lastTick = traveller.dialogueMemory.getLastMetTick(player.getUniqueID());
            if (lastTick >= 0) {
                long elapsed = traveller.world.getTotalWorldTime() - lastTick;
                builder.timeSinceLastMetPhrase(DialoguePhrasing.timeSinceTier(Math.max(0, elapsed)));
            }
        }

        builder.memoryEnabled(TravellersConfig.DIALOGUE.memoryEnabled);
        if (!TravellersConfig.DIALOGUE.memoryEnabled) return;
        for (String phrase : traveller.dialogueMemory.indexPhrasesFor()) {
            builder.addMemoryIndexPhrase(phrase);
        }

        if (TravellersConfig.DIALOGUE.memorySummaryEnabled) {
            String summary = traveller.dialogueMemory.getLifeSummary();
            if (!summary.isEmpty()) {
                builder.lifeSummaryPhrase(summary);
            }
        }
    }
}
