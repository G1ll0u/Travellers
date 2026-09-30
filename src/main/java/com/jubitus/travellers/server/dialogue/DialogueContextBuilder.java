package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single authority that assembles a {@link DialogueContext} for one {@code EntityTraveller} —
 * runs every registered {@link DialogueContextProvider} in order and hands back the finished DTO.
 * Nothing else should build a {@link DialogueContext} directly.
 *
 * <p>Every provider always runs, regardless of the interacting player's client-local detail-level
 * preference (see the context-expansion plan's E-2) — tiering which fields actually reach the
 * prompt is {@code DialoguePromptBuilder}'s job, on the client, once the context is already there.
 */
public final class DialogueContextBuilder {

    private static final List<DialogueContextProvider> PROVIDERS;

    static {
        List<DialogueContextProvider> providers = new ArrayList<>();
        providers.add(new AmbientActivityDialogueContextProvider());
        providers.add(new OriginVillageDialogueContextProvider());
        providers.add(new OccupationDialogueContextProvider());
        providers.add(new TravelMotiveDialogueContextProvider());
        providers.add(new RecentTravelHistoryDialogueContextProvider());
        providers.add(new CultureFlavorDialogueContextProvider());
        providers.add(new WorldStateDialogueContextProvider());
        providers.add(new TerrainSummaryDialogueContextProvider());
        providers.add(new SurroundingBiomeDialogueContextProvider());
        providers.add(new TravellerStateDialogueContextProvider());
        providers.add(new EquipmentDialogueContextProvider());
        providers.add(new PlayerStateDialogueContextProvider());
        providers.add(new NearbyEntitiesDialogueContextProvider());
        providers.add(new MemoryDialogueContextProvider());
        PROVIDERS = Collections.unmodifiableList(providers);
    }

    private DialogueContextBuilder() {
    }

    public static DialogueContext build(EntityTraveller traveller, EntityPlayer player) {
        DialogueContext.Builder builder = new DialogueContext.Builder();
        for (DialogueContextProvider provider : PROVIDERS) {
            provider.contribute(traveller, player, builder);
        }
        return builder.build();
    }
}
