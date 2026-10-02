package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

/**
 * The interacting player's own condition (E6 of the context-expansion plan) - so a hurt or hungry
 * player can be acknowledged in character.
 */
public final class PlayerStateDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        if (player == null) return;
        builder.playerHealthPhrase(DialoguePhrasing.healthTier(player.getHealth(), player.getMaxHealth()));
        builder.playerHungerPhrase(DialoguePhrasing.hungerTier(
                player.getFoodStats().getFoodLevel(), player.getFoodStats().getSaturationLevel()));
    }
}
