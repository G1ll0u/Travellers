package com.jubitus.travellers.utils.mobs;

import com.google.common.base.Optional;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.data.PlayerEnemyRelations;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Player-vs-player hostility for a hired worker's owner.
 * The "declared" list is account-wide (see PlayerEnemyRelations) — every worker of the same
 * owner shares it automatically, no per-worker/per-leader mirroring needed.
 * localHostilePlayers stays per-worker: transient auto-retaliation, not the declared-war list.
 */
public class PlayerHostility {
    private final EntityTravellerWorker w;

    // --- retaliation engagement (server-only runtime memory) ---
    private final java.util.Map<UUID, Long> engagedOwnersUntil = new java.util.HashMap<>();

    // Per-worker local hostiles (auto-retaliation); does NOT sync to escorts
    public final Set<UUID> localHostilePlayers = new HashSet<>();

    public PlayerHostility(EntityTravellerWorker worker) {
        this.w = worker;
    }

    public boolean isPlayerMarkedHostile(@Nullable EntityPlayer p) {
        if (p == null) return false;
        return isOwnerEffectivelyHostile(p.getUniqueID());
    }

    public void removeHostilePlayer(@Nullable UUID id) {
        setHostile(id, false);
    }

    public void setHostile(@Nullable UUID target, boolean hostile) {
        if (target == null) return;
        UUID myOwner = w.getFollowOwnerId();
        if (myOwner == null) return;
        if (!(w.world instanceof WorldServer)) return;

        PlayerEnemyRelations.get(w.world).setHostile(myOwner, target, hostile);
    }

    public Set<UUID> getEffectiveHostilePlayers() {
        Set<UUID> out = new HashSet<>(declaredHostilePlayers());
        out.addAll(localHostilePlayers);
        return out;
    }

    // ----------------------------------------------------------------

    @Nullable
    public UUID getEscortLeaderUUID() {
        Optional<UUID> o = w.getDataManager().get(EntityTravellerWorker.ESCORT_LEADER_DM);
        return o.isPresent() ? o.get() : null;
    }

    @Nullable
    public EntityTravellerWorker resolveEscortLeader(UUID id) {
        if (id == null) return null;
        if (!(w.world instanceof WorldServer)) return null;
        Entity e = ((WorldServer) w.world).getEntityFromUuid(id);
        return (e instanceof EntityTravellerWorker) ? (EntityTravellerWorker) e : null;
    }

    public void tickServer() {
        if (w.world.isRemote) return;
        cleanupEngagements();
    }

    private Set<UUID> declaredHostilePlayers() {
        UUID myOwner = w.getFollowOwnerId();
        if (myOwner == null || !(w.world instanceof WorldServer)) return Collections.emptySet();
        return PlayerEnemyRelations.get(w.world).getHostiles(myOwner);
    }

    // Engage enemy owner for N ticks (retaliation window)
    public void engageOwner(UUID enemyOwner, int durationTicks) {
        if (enemyOwner == null || durationTicks <= 0) return;
        long now = w.world.getTotalWorldTime();
        long until = now + durationTicks;

        Long prev = engagedOwnersUntil.get(enemyOwner);
        if (prev == null || prev < until) {
            engagedOwnersUntil.put(enemyOwner, until);
        }
    }

    // True while engaged (auto-cleans expired)
    public boolean isEngagedOwner(UUID enemyOwner) {
        if (enemyOwner == null) return false;
        Long until = engagedOwnersUntil.get(enemyOwner);
        if (until == null) return false;

        long now = w.world.getTotalWorldTime();
        if (until <= now) {
            engagedOwnersUntil.remove(enemyOwner);
            return false;
        }
        return true;
    }

    private void cleanupEngagements() {
        long now = w.world.getTotalWorldTime();
        java.util.Iterator<java.util.Map.Entry<UUID, Long>> it = engagedOwnersUntil.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<UUID, Long> e = it.next();
            if (e.getValue() == null || e.getValue() <= now) it.remove();
        }
    }

    public boolean addLocalHostile(@Nullable UUID id) {
        if (id == null) return false;
        return localHostilePlayers.add(id);
    }

    public boolean removeLocalHostile(@Nullable UUID id) {
        if (id == null) return false;
        return localHostilePlayers.remove(id);
    }

    // "Declared war" (account-wide list) — does NOT include localHostilePlayers
    public boolean isOwnerDeclaredHostile(@Nullable UUID ownerId) {
        if (ownerId == null) return false;
        return declaredHostilePlayers().contains(ownerId);
    }

    // Used for targeting — declared OR local
    public boolean isOwnerEffectivelyHostile(@Nullable UUID ownerId) {
        if (ownerId == null) return false;
        return isOwnerDeclaredHostile(ownerId) || localHostilePlayers.contains(ownerId);
    }

    // GUI display: union of declared + local for THIS worker's owner
    public Set<UUID> getHostilesForGui() {
        Set<UUID> out = new HashSet<>(declaredHostilePlayers());
        out.addAll(localHostilePlayers);
        return out;
    }

}
