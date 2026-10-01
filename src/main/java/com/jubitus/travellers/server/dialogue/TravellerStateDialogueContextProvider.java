package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.entity.entityai.travel.mount.MountMode;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

/**
 * The traveller's own condition (E5 of the context-expansion plan): health, mount state, camping,
 * and whether it was fighting a moment ago. {@code isInPostCombatSilence()} (a 5-second window
 * already used to hold off ambient voice barks right after a fight) doubles as "recently in combat"
 * here - it's the exact "right after a fight ends" window the plan asks for, with no new state
 * needed. During an actual ongoing fight the dialogue GUI never opens at all ({@code processInteract}'s
 * own {@code isInCombat()} early return), so this field is only ever seen true just after one.
 */
public final class TravellerStateDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        builder.healthPhrase(DialoguePhrasing.healthTier(traveller.getHealth(), traveller.getMaxHealth()));

        if (traveller.getMountUUID() != null) {
            MountMode mode = traveller.getMountMode();
            String phrase = mode == MountMode.LED ? "leading a horse on foot" : "riding a horse";
            builder.mountPhrase(phrase);
        }

        builder.camping(traveller.isCampingPassive());
        builder.recentlyInCombat(traveller.isInPostCombatSilence());
    }
}
