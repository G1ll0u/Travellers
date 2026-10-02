package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.entity.entityai.travel.travelcommon.RecentDestinationRing;
import com.jubitus.travellers.entity.entityai.travel.travelcommon.TravelCommon;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The last few villages this traveller actually passed through — reuses {@link TravelCommon}'s own
 * {@code recentDestinations} ring (already kept for the anti-ping-pong destination penalty, see
 * CLAUDE.md's regionalized destination section) rather than building a second history mechanism.
 * 2026-09-13 maintainer feedback: travellers should be aware of recent stops in conversation.
 */
public final class RecentTravelHistoryDialogueContextProvider implements DialogueContextProvider {

    @Override
    public void contribute(EntityTraveller traveller, EntityPlayer player, DialogueContext.Builder builder) {
        TravelCommon travelCommon = traveller.getTravelCommon();
        if (travelCommon == null) return;
        long[] ring = travelCommon.recentDestinations;
        if (ring == null || ring.length == 0) return;

        // The ring is pushed at pick-time (TravelCommon.noteChosenDestination), not on arrival - while
        // actively travelling toward a destination, that destination is already the ring's front entry.
        // Without this exclusion a traveller mid-route to X both "is currently travelling toward X" and
        // "recently passed through X" in the same prompt, a real contradiction a real transcript produced
        // (2026-09-22 maintainer report: "Cela fait déjà un long moment que j'y ai passé" about its own
        // not-yet-reached destination).
        BlockPos current = travelCommon.isVillageIdling() ? travelCommon.getNearestVillage() : travelCommon.getTargetVillage();
        Long excludeKey = current != null ? RecentDestinationRing.pack(current) : null;

        World world = traveller.world;
        int y = traveller.getPosition().getY();
        Set<String> seen = new LinkedHashSet<>();
        for (long packed : ring) {
            if (excludeKey != null && packed == excludeKey) continue;
            int x = (int) (packed >> 32);
            int z = (int) packed;
            String name = com.jubitus.travellers.world.mill.MillSettlements.nameOrNull(world, new BlockPos(x, y, z));
            if (name != null && seen.add(name)) {
                builder.addRecentlyVisitedVillage(name);
            }
        }
    }
}
