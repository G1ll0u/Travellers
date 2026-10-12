package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

/** Names every culture the context mentions with Millénaire's English adjective; must run after the providers that set culture ids. */
public final class CultureNameDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        for (String cultureId : builder.cultureIdsInUse()) {
            builder.cultureName(cultureId, CultureFlavorDialogueContextProvider.englishCultureString(cultureId, "culture." + cultureId));
        }
    }
}
