package com.jubitus.travellers.dialogue;

import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

/**
 * Origin village context (E1 of the context-expansion plan): {@link EntityTraveller#getHomeVillage()}
 * resolved to a display name via {@link com.jubitus.travellers.village.MillSettlements#nameOrNull}.
 */
public final class OriginVillageDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        BlockPos home = traveller.getHomeVillage();
        if (home == null) return;
        builder.originVillageName(com.jubitus.travellers.village.MillSettlements.nameOrNull(traveller.world, home));
    }
}
