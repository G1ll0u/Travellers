package com.jubitus.travellers.worker;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.unit.UnitWorth;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An unhired spawn group (a traveller and its followers, a roaming merchant and its escort), loaded or
 * virtual. It joins a player whole or not at all - hired, or recruited; a lone unit is a group of one.
 */
public final class SpawnGroup {

    public final List<EntityTraveller> loaded = new ArrayList<>();
    public final List<VirtualTravellerRecord> virtual = new ArrayList<>();

    private SpawnGroup() {
    }

    public static SpawnGroup of(WorldServer world, EntityTraveller member) {
        return of(world, member.getUniqueID(), member.getGroupLeaderUUID());
    }

    public static SpawnGroup of(WorldServer world, VirtualTravellerRecord member) {
        return of(world, member.uuid, member.groupLeaderUUID);
    }

    /** The group of the unit {@code self} (itself included), led by {@code groupLeader} or by itself. */
    private static SpawnGroup of(WorldServer world, UUID self, @Nullable UUID groupLeader) {
        UUID leaderId = groupLeader != null ? groupLeader : self;
        SpawnGroup g = new SpawnGroup();
        Entity leader = world.getEntityFromUuid(leaderId);
        if (isFree(leader)) g.loaded.add((EntityTraveller) leader);
        for (EntityTraveller e : world.getEntities(EntityTraveller.class, e -> isFree(e) && leaderId.equals(e.getGroupLeaderUUID()))) {
            g.loaded.add(e);
        }
        int dim = world.provider.getDimension();
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(world).getRecordsView()) {
            if (r.dimension != dim || "RAIDER".equals(r.entityClass) || r.isHiredFollower) continue;
            if (leaderId.equals(r.uuid) || leaderId.equals(r.groupLeaderUUID)) g.virtual.add(r);
        }
        return g;
    }

    private static boolean isFree(@Nullable Entity e) {
        return e instanceof EntityTraveller && !e.isDead && !(e instanceof EntityTravellerRaider)
                && !((EntityTraveller) e).isHiredFollower();
    }

    public int size() {
        return loaded.size() + virtual.size();
    }

    /** What the whole group costs, each member priced by its own gear. */
    public int hirePrice() {
        int total = 0;
        for (EntityTraveller e : loaded) total += UnitWorth.hirePrice(e);
        for (VirtualTravellerRecord r : virtual) total += UnitWorth.hirePrice(r);
        return total;
    }

    /** A merchant group: every member becomes an ordinary hired worker following {@code owner}, ungrouped. */
    public void hireAll(WorldServer world, EntityPlayer owner) {
        for (EntityTraveller e : loaded) {
            if (!(e instanceof EntityTravellerWorker)) continue;
            e.setGroupLeaderUUID(null);
            ((EntityTravellerWorker) e).hireAsFollower(owner);
        }
        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        for (VirtualTravellerRecord r : new ArrayList<>(virtual)) {
            if ("WORKER".equals(r.entityClass)) vtm.hireVirtualWorker(world, r, owner);
        }
    }
}
