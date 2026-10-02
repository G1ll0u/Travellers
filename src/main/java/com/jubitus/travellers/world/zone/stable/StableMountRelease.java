package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mount.TravellerMountSupport;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * A palfrenier's own bonded mount, surrendered into the pool it now tends - see
 * {@link #surrenderLoadedMount}/{@link #surrenderVirtualMount}.
 *
 * <p>A <em>dead</em> rider's mount is deliberately NOT handled here any more: it used to be redirected
 * into the owner's nearest Stable (or offered to a nearby groupmate first), both of which teleported
 * the horse away from where its rider actually died. Per an explicit user decision, a dead rider's
 * horse - hired worker included - now always just becomes an ordinary loose, orphaned horse exactly
 * where it died ({@code TravellerMountSupport.releaseMountOnRiderDeath}) - see {@code OrphanMountCleanup}
 * and {@code EntityAIClaimLooseMount} for how it gets cleaned up or claimed from there.
 */
public final class StableMountRelease {

    private StableMountRelease() {
    }

    /**
     * A palfrenier that arrives on Stable duty still carrying its own bonded mount surrenders it into
     * the pool it now tends - riding around while doing stable chores would be absurd, and the horse
     * is exactly the stock the Stable exists to hold. One implementation, called from the loaded
     * arrival ({@code StableHandTask}) and the virtual one ({@code StableHandVirtualDuty}).
     */
    public static void surrenderLoadedMount(WorldServer world, EntityTravellerWorker worker,
                                            PlayerZoneRecord zone, StableZoneData data) {
        NBTTagCompound mountNbt = TravellerMountSupport.captureMount(worker);
        if (mountNbt == null) mountNbt = worker.getMountSnapshotNbt();
        worker.setMountUUID(null);
        worker.setMountSnapshotNbt(null);
        if (mountNbt == null) return;

        data.addCapturedHorse(world, mountNbt, StableHorseEntry.Origin.WORKER_OWN_MOUNT,
                world.getTotalWorldTime());
        PlayerZoneManager.get(world).markDirty();
        if (TravellersConfig.STABLES.notifyOnMountRecovered && world.getMinecraftServer() != null) {
            NotificationService.notifyOffline(world.getMinecraftServer(), zone.ownerUUID,
                    NotificationDefs.STABLE_MOUNT_RECOVERED, worker.getName());
        }
    }

    /** Offscreen counterpart of {@link #surrenderLoadedMount}: the palfrenier reached its Stable
     * while still virtualized, carrying a mount snapshot on its record. */
    public static void surrenderVirtualMount(WorldServer world, VirtualTravellerRecord r,
                                             @Nullable StableZoneData data) {
        NBTTagCompound horseNbt = r.mountNbt;
        if (horseNbt == null && r.entityNbt != null && r.entityNbt.hasKey("MountSnapshot")) {
            horseNbt = r.entityNbt.getCompoundTag("MountSnapshot");
        }
        if (horseNbt != null && data != null) {
            data.addCapturedHorse(world, horseNbt, StableHorseEntry.Origin.WORKER_OWN_MOUNT,
                    world.getTotalWorldTime());
            PlayerZoneManager.get(world).markDirty();
        }
        r.mounted = false;
        r.mountNbt = null;
        r.mountMode = null;
        if (r.entityNbt != null) {
            r.entityNbt.removeTag("MountUUID");
            r.entityNbt.removeTag("MountSnapshot");
            r.entityNbt.removeTag("MountMode");
        }
    }
}
