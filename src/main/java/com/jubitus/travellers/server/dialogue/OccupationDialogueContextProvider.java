package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.culture.JobLabelFormatter;
import com.jubitus.travellers.unit.culture.TravellerAppearance;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Occupation identity (identity-memory plan, Part K): a traveller's own trade/role, drawn from the
 * real Millénaire villager .txt behind its {@link EntityTraveller#getActiveAppearance()} - never
 * invented by the model. MINIMAL tier, same as identity/name - "what do you do" is as basic a fact
 * about a person as their name, and is exactly the missing data behind the plan's motivating
 * complaint ("what are you doing here, so far from home?" -> "nothing special").
 */
public final class OccupationDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        if (!TravellersConfig.DIALOGUE.occupationLabelEnabled) return;
        TravellerAppearance appearance = traveller.getActiveAppearance();
        String label = appearance == null ? "" : JobLabelFormatter.format(appearance.jobId);
        if (!label.isEmpty()) {
            builder.occupationLabel(label);
        }
    }
}
