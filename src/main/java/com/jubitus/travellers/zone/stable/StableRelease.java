package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.horse.HorseSnapshots;
import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZonePlacement;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagDouble;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.Iterator;

/**
 * Horses of a deleted Stable that were held as data: each one appears as an ordinary horse at its
 * spot once that ground ticks, never loading a chunk and never in front of a player's eyes more than
 * any other entity loading in. The queue lives in {@link PlayerZoneManager} (persisted).
 */
final class StableRelease {

    private StableRelease() {
    }

    /** The snapshot to queue: no pool identity, and a position inside the old zone. */
    static NBTTagCompound releaseSnapshot(NBTTagCompound horseNbt, PlayerZoneRecord zone) {
        NBTTagCompound nbt = horseNbt.copy();
        StableCoordinator.sanitizeSnapshot(nbt);
        HorseMarkers.clearPoolId(nbt);
        double[] pos = StableMaterializer.readPos(nbt);
        if (pos == null || !StableMaterializer.isInsideZone(zone, pos[0], pos[2])) {
            NBTTagList l = new NBTTagList();
            l.appendTag(new NBTTagDouble(zone.center.getX() + 0.5D));
            l.appendTag(new NBTTagDouble(StableMaterializer.referenceFloorY(zone)));
            l.appendTag(new NBTTagDouble(zone.center.getZ() + 0.5D));
            nbt.setTag("Pos", l);
        }
        return nbt;
    }

    static void processPending(WorldServer world, PlayerZoneManager mgr) {
        if (mgr.getPendingHorseReleases().isEmpty()) return;
        boolean changed = false;
        Iterator<NBTTagCompound> it = mgr.getPendingHorseReleases().iterator();
        while (it.hasNext()) {
            NBTTagCompound nbt = it.next();
            double[] pos = StableMaterializer.readPos(nbt);
            if (pos == null) {
                it.remove();
                changed = true;
                continue;
            }
            BlockPos p = new BlockPos(pos[0], pos[1], pos[2]);
            if (!world.getChunkProvider().chunkExists(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (VirtualTravellerManager.isFrozenByVanillaAt(world, pos[0], pos[2])) continue;
            if (HorseSnapshots.isCorpse(nbt)) {
                it.remove();
                changed = true;
                continue;
            }

            EntityHorse horse = new EntityHorse(world);
            horse.readFromNBT(nbt);
            BlockPos spot = StableMaterializer.fits(world, horse, pos[0], pos[1], pos[2]) ? null
                    : ZonePlacement.findStandableNear(world, p, 3);
            if (spot != null) horse.setLocationAndAngles(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, horse.rotationYaw, 0.0F);
            horse.motionX = horse.motionY = horse.motionZ = 0.0D;
            horse.fallDistance = 0.0F;
            world.spawnEntity(horse);
            it.remove();
            changed = true;
        }
        if (changed) mgr.markDirty();
    }
}
