package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import com.jubitus.travellers.zone.ZonePlacement;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.List;

/**
 * Spawns a data-held {@link StableHorseEntry} back into the world as a real {@code EntityHorse}: at
 * the spot it was captured at when that spot is still inside the pen and clear, else at the pen's
 * real floor level (never a rooftop for an indoor Stable). The entry is linked before the spawn so
 * {@link StableHorseLoadHandler} accepts the join.
 */
final class StableMaterializer {

    private StableMaterializer() {
    }

    /** A snapshot with no positive health left describes a dead horse, not a dormant one. */
    static boolean isDeadSnapshot(NBTTagCompound horseNbt) {
        return !horseNbt.hasKey("Health") || horseNbt.getFloat("Health") <= 0.0F;
    }

    static EntityHorse materializeEntry(WorldServer world, PlayerZoneRecord zone, StableHorseEntry entry) {
        EntityHorse horse = new EntityHorse(world);
        NBTTagCompound spawnNbt = entry.horseNbt.copy();
        StableCoordinator.sanitizeSnapshot(spawnNbt); // always a fresh UUID: never collide with a lingering copy
        horse.readFromNBT(spawnNbt);
        StableCoordinator.setPoolTag(horse, entry.poolId);

        double[] saved = readPos(spawnNbt);
        if (saved == null || !isInsideZone(zone, saved[0], saved[2])
                || !fits(world, horse, saved[0], saved[1], saved[2])) {
            BlockPos spot = findClearSpot(world, zone);
            horse.setLocationAndAngles(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, horse.rotationYaw, 0.0F);
        }
        horse.motionX = horse.motionY = horse.motionZ = 0.0D;
        horse.fallDistance = 0.0F;
        StableHorseAI.applyContainmentInvariants(horse, zone.center, zone.halfWidth, zone.halfDepth);

        entry.liveEntityUUID = horse.getUniqueID();
        if (!world.spawnEntity(horse)) {
            entry.liveEntityUUID = null;
            return null;
        }
        return horse;
    }

    static double[] readPos(NBTTagCompound nbt) {
        if (!nbt.hasKey("Pos", 9)) return null;
        NBTTagList l = nbt.getTagList("Pos", 6);
        if (l.tagCount() < 3) return null;
        return new double[]{l.getDoubleAt(0), l.getDoubleAt(1), l.getDoubleAt(2)};
    }

    /** Inside the zone's box plus the containment margin (XZ). */
    static boolean isInsideZone(PlayerZoneRecord zone, double x, double z) {
        AxisAlignedBB b = zone.box(TravellersConfig.STABLES.containmentMarginBlocks);
        return x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ;
    }

    /** Places {@code horse} at the point and reports whether its body is clear there (loaded ground,
     * no block collision, no liquid). */
    static boolean fits(WorldServer world, EntityHorse horse, double x, double y, double z) {
        if (!world.isBlockLoaded(new BlockPos(x, y, z))) return false;
        horse.setLocationAndAngles(x, y, z, horse.rotationYaw, 0.0F);
        AxisAlignedBB bb = horse.getEntityBoundingBox();
        return world.getCollisionBoxes(horse, bb).isEmpty() && !world.containsAnyLiquid(bb);
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
