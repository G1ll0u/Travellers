package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;

/**
 * Per-tick containment for a Stable's <i>already-known</i> pool horses - the AI-strip re-assert plus
 * {@link StableContainmentGuard}'s boundary nudge/teleport. Deliberately <b>not</b> behind the
 * whole-zone settle gate the heavy reconcile pass uses: containment is the user's one hard guarantee
 * ("horses never leave the zone") and must not wait seconds after a chunk loads, nor stall
 * indefinitely on a large zone whose corner chunks keep flickering. It only ever touches entries
 * that already have a {@code liveEntityUUID} resolving to a loaded horse - it never claims, captures
 * NBT, materializes, or writes persisted state, so it cannot cause the duplication the settle gate
 * guards against.
 */
final class StableContainmentSweep {

    private StableContainmentSweep() {
    }

    static void run(WorldServer world, PlayerZoneRecord zone, StableZoneData data, StableCoordinator coord) {
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID == null) continue;
            Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
            if (!(ent instanceof EntityHorse) || ent.isDead) continue;
            EntityHorse horse = (EntityHorse) ent;
            coord.trackLiveHorse(entry.liveEntityUUID);
            if (StableHorseAI.needsInvariants(horse)) {
                StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
            }
            StableContainmentGuard.enforce(horse, zone.center, zone.halfWidth, zone.halfDepth);
        }
    }
}
