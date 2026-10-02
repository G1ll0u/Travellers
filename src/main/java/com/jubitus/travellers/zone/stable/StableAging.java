package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.world.WorldServer;

/**
 * Ages every dormant (no live entity) pool entry by elapsed real time, mirroring vanilla's own
 * {@code EntityAgeable.onLivingUpdate} tick-by-tick growth - which only ever runs on a live, loaded
 * entity and would otherwise leave a bred foal frozen as a baby forever while its Stable sits
 * unloaded. Pure NBT math on the "Age" tag ({@link StableHorseEntry#horseNbt} already IS the entity's
 * full persisted state). Load-agnostic: called every {@code StableCoordinator.tick}, never gated.
 */
final class StableAging {

    private StableAging() {
    }

    static boolean advance(WorldServer world, PlayerZoneRecord zone, StableZoneData data, long now) {
        boolean dirty = false;
        for (StableHorseEntry entry : data.pool) {
            if (entry.liveEntityUUID != null || entry.horseNbt == null) continue;

            if (entry.lastAgeSyncTick == 0) {
                // Never synced (a fresh entry, or an older save from before this field existed) -
                // establish the anchor without applying anything yet, rather than reading "now - 0"
                // as decades of elapsed time and instantly growing every existing foal up.
                entry.lastAgeSyncTick = now;
                continue;
            }
            long elapsed = now - entry.lastAgeSyncTick;
            if (elapsed <= 0) continue;
            entry.lastAgeSyncTick = now;

            int age = entry.horseNbt.getInteger("Age");
            int newAge;
            if (age < 0) {
                newAge = (int) Math.min(0L, age + elapsed);
            } else if (age > 0) {
                newAge = (int) Math.max(0L, age - elapsed);
            } else {
                continue; // already adult, nothing to do
            }
            entry.horseNbt.setInteger("Age", newAge);
            entry.growingAge = newAge;
            dirty = true;
            if (age < 0 && newAge == 0) {
                StableWatchLog.record(world, zone, StableWatchLog.Kind.GROWN,
                        StableWatchLog.shortId(entry.poolId), false);
            }
        }
        return dirty;
    }
}
