package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.world.WorldServer;

/** Per-tick containment for a live Stable's linked horses: the AI-strip re-assert, the mount-marker
 * sanitizer and {@link StableContainmentGuard}'s boundary nudge/teleport. Never claims or spawns. */
final class StableContainmentSweep {

    private StableContainmentSweep() {
    }

    static void run(WorldServer world, PlayerZoneRecord zone, StableZoneData data) {
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID == null) continue;
            Entity ent = world.getEntityFromUuid(entry.liveEntityUUID);
            if (!(ent instanceof EntityHorse) || ent.isDead) continue;
            EntityHorse horse = (EntityHorse) ent;
            StableCoordinator.sanitizeLive(horse);
            if (StableHorseAI.needsInvariants(horse)) {
                StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
            }
            StableContainmentGuard.enforce(horse, zone.center, zone.halfWidth, zone.halfDepth);
        }
    }
}
