package com.jubitus.travellers.worker;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A roaming merchant (unhired worker) and its escort, loaded or virtual. A player hires the whole group or
 * nobody; a lone merchant is a group of one.
 */
public final class MerchantGroup {

    private final List<EntityTravellerWorker> loaded = new ArrayList<>();
    private final List<VirtualTravellerRecord> virtual = new ArrayList<>();

    private MerchantGroup() {
    }

    /** The group {@code member} belongs to (itself included). */
    public static MerchantGroup of(WorldServer world, EntityTravellerWorker member) {
        UUID merchant = member.getGroupLeaderUUID() != null ? member.getGroupLeaderUUID() : member.getUniqueID();
        MerchantGroup g = new MerchantGroup();
        Entity leader = world.getEntityFromUuid(merchant);
        if (leader instanceof EntityTravellerWorker && !leader.isDead) g.loaded.add((EntityTravellerWorker) leader);
        for (EntityTravellerWorker w : world.getEntities(EntityTravellerWorker.class, e -> e != null && !e.isDead
                && !e.isHiredFollower() && merchant.equals(e.getGroupLeaderUUID()))) {
            g.loaded.add(w);
        }
        int dim = world.provider.getDimension();
        for (VirtualTravellerRecord r : VirtualTravellerManager.get(world).getRecordsView()) {
            if (r.dimension != dim || !"WORKER".equals(r.entityClass) || r.isHiredFollower) continue;
            if (merchant.equals(r.uuid) || merchant.equals(r.groupLeaderUUID)) g.virtual.add(r);
        }
        return g;
    }

    public int size() {
        return loaded.size() + virtual.size();
    }

    /** Every member becomes an ordinary hired worker following {@code owner}, ungrouped. */
    public void hireAll(WorldServer world, EntityPlayer owner) {
        for (EntityTravellerWorker w : loaded) {
            w.setGroupLeaderUUID(null);
            w.hireAsFollower(owner);
        }
        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        for (VirtualTravellerRecord r : new ArrayList<>(virtual)) vtm.hireVirtualWorker(world, r, owner);
    }
}
