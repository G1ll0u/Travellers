package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.horse.HorseSnapshots;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

/**
 * Ages and heals every dormant (no live entity) pool entry by elapsed real time, mirroring vanilla's
 * own {@code EntityAgeable.onLivingUpdate} growth and {@code AbstractHorse.onLivingUpdate} natural
 * regeneration - which only ever run on a live, loaded entity and would otherwise leave a bred foal
 * frozen as a baby, or a hurt horse hurt, forever while its Stable sits unloaded. Pure NBT math
 * ({@link StableHorseEntry#horseNbt} already IS the entity's full persisted state). Load-agnostic:
 * called every {@code StableCoordinator.tick}, never gated.
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

            NBTTagCompound nbt = entry.horseNbt;
            float health = HorseSnapshots.health(nbt);
            if (health > 0.0F && health < HorseSnapshots.maxHealth(nbt)) {
                HorseSnapshots.heal(nbt, elapsed * StableWork.NATURAL_REGEN_PER_TICK);
                dirty = true;
            }

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
