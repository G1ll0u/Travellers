package com.jubitus.travellers.follow.group;

import com.jubitus.travellers.follow.net.FormationZoneInfo;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import com.jubitus.travellers.zone.ZoneRoles;
import com.jubitus.travellers.zone.ZoneType;
import com.jubitus.travellers.zone.stable.StableCapacity;
import com.jubitus.travellers.zone.stable.StableHorseEntry;
import com.jubitus.travellers.zone.stable.StableZoneData;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The player's Barracks and Stables for the Formation Hub, with who is bound to each. */
final class HubZones {

    final List<FormationZoneInfo> zones = new ArrayList<>();
    /** Workers not walking with the player: idle Barrack residents (shown under their Barrack) and Stable hands on duty. */
    final Set<UUID> stationed = new HashSet<>();

    HubZones(MinecraftServer server, EntityPlayerMP player, List<HubWorker> workers, Set<UUID> leaderIds) {
        UUID owner = player.getUniqueID();
        for (WorldServer ws : server.worlds) {
            for (PlayerZoneRecord zone : PlayerZoneManager.get(ws).getByOwner(owner)) {
                if (zone.type != ZoneType.BARRACK && zone.type != ZoneType.STABLE) continue;
                zones.add(describe(zone, workers, leaderIds));
            }
        }
        zones.sort((a, b) -> Double.compare(a.center.distanceSq(player.getPosition()), b.center.distanceSq(player.getPosition())));
    }

    private FormationZoneInfo describe(PlayerZoneRecord zone, List<HubWorker> workers, Set<UUID> leaderIds) {
        boolean stable = zone.type == ZoneType.STABLE;
        FormationZoneInfo info = new FormationZoneInfo();
        info.id = zone.id;
        info.stable = stable;
        info.name = zone.name != null && !zone.name.isEmpty() ? zone.name : stable ? "Stable" : "Barrack";
        info.center = zone.center;
        for (HubWorker w : workers) {
            if (w.zone == null || !w.zone.matches(zone)) continue;
            info.bound++;
            w.addTo.accept(info.gear);
            if (w.dim == w.zone.dim && zone.contains(w.pos)) info.here++;
            if (stable) {
                stationed.add(w.uuid);
            } else if (w.escortLeader == null && !w.followActive && !leaderIds.contains(w.uuid)) {
                info.members.add(w.uuid);
                stationed.add(w.uuid);
            }
        }
        if (stable && zone.data instanceof StableZoneData) {
            info.capacity = StableCapacity.zoneAreaCap(zone);
            for (StableHorseEntry horse : ((StableZoneData) zone.data).pool) {
                info.horses++;
                if (horse.tamed) info.tamed++;
                if (horse.saddled) info.saddled++;
            }
        } else {
            info.capacity = zone.getRolePositions(ZoneRoles.BED).size();
        }
        return info;
    }
}
