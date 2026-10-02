package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import com.jubitus.travellers.world.zone.PlayerZoneManager;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import com.jubitus.travellers.world.zone.ZoneType;
import com.jubitus.travellers.world.zone.duty.VirtualDutyBehavior;
import net.minecraft.world.WorldServer;

/**
 * Offscreen counterpart of {@link StableHandTask}'s arrival check: a palfrenier that reaches its
 * Stable duty <b>while virtualized</b> surrenders any bonded mount it arrived on into the pool it now
 * tends - the same operation {@link StableMountRelease#surrenderVirtualMount} performs, so the loaded
 * and virtual arrival paths can never diverge. Everything else about the Stable duty progresses via
 * {@code StableCoordinator}/{@code StableWork} against the zone's own pool, so this is the only
 * per-record piece the offscreen side needs.
 */
public final class StableHandVirtualDuty implements VirtualDutyBehavior {

    @Override
    public void tickVirtual(WorldServer world, VirtualTravellerRecord r, long now) {
        if (r.followOwnerUUID == null || r.dutyZoneCenter == null) return;
        if (!r.mounted && r.mountNbt == null
                && (r.entityNbt == null || !r.entityNbt.hasKey("MountSnapshot"))) return;

        PlayerZoneRecord zone = PlayerZoneManager.get(world)
                .getByOwnerAndCenter(r.followOwnerUUID, r.dutyZoneCenter);
        if (zone == null || zone.type != ZoneType.STABLE || !(zone.data instanceof StableZoneData)) {
            // Zone gone: still end the bond so the record doesn't materialize a ghost rider later.
            StableMountRelease.surrenderVirtualMount(world, r, null);
            return;
        }
        StableMountRelease.surrenderVirtualMount(world, r, (StableZoneData) zone.data);
    }
}
