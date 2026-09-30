package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

/**
 * Origin village context (E1 of the context-expansion plan): {@link EntityTraveller#getHomeVillage()}
 * resolved to a display name via the shared {@link DialogueVillageNaming} helper.
 */
public final class OriginVillageDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        BlockPos home = traveller.getHomeVillage();
        if (home == null) return;
        builder.originVillageName(DialogueVillageNaming.resolveVillageName(traveller.world, home));
    }
}
