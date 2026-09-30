package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneRoles;
import com.jubitus.travellers.world.zone.ZonePlacement;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns a dormant {@link StableHorseEntry} back into the world as a real {@code EntityHorse}, at the
 * pen's actual floor level (never a rooftop for an indoor Stable). Split out of
 * {@code StableCoordinator} so the reconciler stays focused on the scan/claim loop.
 */
final class StableMaterializer {

    /** poolId -> world tick it was last materialized here. Lets the fuzzy relink in
     * {@link StablePhysicalReconciler} avoid adopting a substitute it just spawned. Bounded by the
     * pool size; entries are overwritten, never accumulate. */
    private static final Map<UUID, Long> recentlySpawned = new ConcurrentHashMap<>();
    static final long RECENT_SPAWN_WINDOW = 40L;

    private StableMaterializer() {
    }

    static boolean spawnedRecently(UUID poolId, long now) {
        Long t = recentlySpawned.get(poolId);
        return t != null && now - t < RECENT_SPAWN_WINDOW;
    }

    /** A snapshot with no positive health left describes a dead horse, not a dormant one. */
    static boolean isDeadSnapshot(NBTTagCompound horseNbt) {
        return !horseNbt.hasKey("Health") || horseNbt.getFloat("Health") <= 0.0F;
    }

    static EntityHorse materializeEntry(WorldServer world, PlayerZoneRecord zone, StableHorseEntry entry) {
        EntityHorse horse = new EntityHorse(world);
        // A dormant pool entry has no player-facing identity to preserve, and entry.horseNbt may
        // carry a UUID still alive elsewhere (knockback drift past the scan margin) - spawning with it
        // would collide and WorldServer.canAddEntity silently refuses the join. Always spawn fresh.
        NBTTagCompound spawnNbt = entry.horseNbt.copy();
        spawnNbt.removeTag("UUIDMost");
        spawnNbt.removeTag("UUIDLeast");
        horse.readFromNBT(spawnNbt);
        StableCoordinator.setPoolTag(horse, entry.poolId);
        BlockPos spot = findClearSpot(world, zone);
        horse.setLocationAndAngles(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, horse.rotationYaw, 0.0F);
        // A snapshot can carry the source horse's motion / fall distance - clear it so the spawn
        // doesn't drift or take fall damage on its first tick.
        horse.motionX = horse.motionY = horse.motionZ = 0.0D;
        horse.fallDistance = 0.0F;
        StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);
        if (!world.spawnEntity(horse)) return null;
        recentlySpawned.put(entry.poolId, world.getTotalWorldTime());
        return horse;
    }

    /** A standable spot inside the zone, searched ring by ring from the centre at the pen's real
     * floor level (the fence blocks' own Y), not the full height of the bounding box. */
    private static BlockPos findClearSpot(WorldServer world, PlayerZoneRecord zone) {
        BlockPos center = zone.center;
        int floorY = referenceFloorY(zone);
        int vertTolerance = Math.min(zone.halfHeight, 3);
        int maxRing = Math.min(zone.halfWidth, zone.halfDepth);
        for (int r = 0; r <= maxRing; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos column = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                    BlockPos ground = ZonePlacement.findStandableNear(world, column, vertTolerance);
                    if (ground != null) return ground;
                }
            }
        }
        return center;
    }

    static int referenceFloorY(PlayerZoneRecord zone) {
        List<BlockPos> hitches = zone.getRolePositions(ZoneRoles.HITCH);
        if (hitches.isEmpty()) return zone.center.getY();
        long sum = 0;
        for (BlockPos p : hitches) sum += p.getY();
        return (int) (sum / hitches.size());
    }
}
