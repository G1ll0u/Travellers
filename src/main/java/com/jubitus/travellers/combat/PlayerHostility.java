package com.jubitus.travellers.combat;

import com.google.common.base.Optional;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.village.TravellerVillageRelations;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;

import javax.annotation.Nullable;
import java.util.*;

/**
 * A hired worker's hostility: who its enemies are, when it may retaliate, and its owner's PvP state.
 * The "declared" list is account-wide (see PlayerEnemyRelations) — every worker of the same
 * owner shares it automatically, no per-worker/per-leader mirroring needed.
 */
public class PlayerHostility {
    public static final int PVP_ENGAGE_TICKS = 20 * 120; // 2 minutes

    private final EntityTravellerWorker w;

    // --- retaliation engagement (server-only runtime memory) ---
    private final java.util.Map<UUID, Long> engagedOwnersUntil = new java.util.HashMap<>();

    public PlayerHostility(EntityTravellerWorker worker) {
        this.w = worker;
    }

    public boolean isPlayerMarkedHostile(@Nullable EntityPlayer p) {
        if (p == null) return false;
        return isOwnerDeclaredHostile(p.getUniqueID());
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

    // ----------------------------------------------------------------

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

    public void clearEngagement(@Nullable UUID enemyOwner) {
        if (enemyOwner != null) engagedOwnersUntil.remove(enemyOwner);
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

    // "Declared war" (account-wide list) — does NOT include localHostilePlayers
    public boolean isOwnerDeclaredHostile(@Nullable UUID ownerId) {
        if (ownerId == null) return false;
        return declaredHostilePlayers().contains(ownerId);
    }

    @Nullable
    public static UUID ownerIdOf(@Nullable EntityLivingBase e) {
        if (e == null) return null;
        if (e instanceof EntityPlayer) return e.getUniqueID();
        if (e instanceof EntityTravellerWorker) return ((EntityTravellerWorker) e).getFollowOwnerId();
        return null;
    }

    public boolean isEnemyOwner(@Nullable UUID otherOwner) {
        UUID myOwner = w.getFollowOwnerId();
        if (myOwner == null || otherOwner == null) return false;
        if (myOwner.equals(otherOwner)) return false;

        if (w.world instanceof WorldServer
                && PlayerEnemyRelations.get(w.world).isOfflineProtected(w.world, otherOwner)) return false;

        // declared hostility OR currently engaged (retaliation window)
        return isOwnerDeclaredHostile(otherOwner)
                || isEngagedOwner(otherOwner);

    }

    public boolean isEnemyPlayer(@Nullable EntityPlayer p) {
        if (p == null || p.isDead) return false;
        if (p.isCreative() || p.isSpectator()) return false;
        if (w.isOwner(p)) return false;
        return isEnemyOwner(p.getUniqueID());
    }

    public boolean isEnemyHiredWorker(@Nullable EntityTravellerWorker other) {
        if (other == null || other == w) return false;
        if (!other.isEntityAlive()) return false;

        if (!w.isHiredFollower() || !other.isHiredFollower()) return false;

        UUID otherOwner = other.getFollowOwnerId();
        if (otherOwner == null) return false;

        return isEnemyOwner(otherOwner);
    }

    /**
     * Retaliation permission (your critical constraint):
     * We only retaliate if the ATTACKER has declared the victim owner hostile.
     */
    public boolean canRetaliateAgainst(@Nullable EntityLivingBase attacker) {
        if (attacker == null || !attacker.isEntityAlive()) return false;

        UUID myOwner = w.getFollowOwnerId();
        if (myOwner == null) return false;

        // Always retaliate vs mobs
        if (attacker instanceof net.minecraft.entity.monster.IMob) return true;

        // Never retaliate vs owner
        if (attacker == w.getFollowOwner()) return false;

        // If attacker is an enemy worker: easy check (their declared list includes my owner)
        if (attacker instanceof EntityTravellerWorker) {
            EntityTravellerWorker aw = (EntityTravellerWorker) attacker;
            UUID ao = aw.getFollowOwnerId();
            if (ao == null || ao.equals(myOwner)) return false;
            return aw.playerHostility.isOwnerDeclaredHostile(myOwner);
        }

        // If attacker is a player: only retaliate if that player has declared war on my owner
        if (attacker instanceof EntityPlayer) {
            EntityPlayer ap = (EntityPlayer) attacker;
            if (ap.isCreative() || ap.isSpectator()) return false;

            UUID attackerOwner = ap.getUniqueID();
            if (attackerOwner.equals(myOwner)) return false;
            if (!(w.world instanceof WorldServer)) return false;

            return PlayerEnemyRelations.get(w.world)
                    .isHostile(attackerOwner, myOwner);
        }

        // Ancient Warfare 2 faction NPC (Empire, Bandits, etc.) - reputation/engagement-aware,
        // see AncientWarfare2Compat. Covers both "I hit this one, it's now hostile to my owner"
        // and "this one joined the fight against my owner via AW2's own alert reaction".
        if (com.jubitus.travellers.combat.compat.AncientWarfare2Compat.isHostile(attacker, w.getFollowOwner())) {
            return true;
        }

        // Any other creature or unit that hurt us or our owner is fair game, unless it is on the owner's side.
        return !Hostility.isOwnerSide(w, attacker);
    }

    public void engageEnemyOwner(@Nullable UUID enemyOwner) {
        if (enemyOwner == null) return;
        engageOwner(enemyOwner, PVP_ENGAGE_TICKS);
    }

    private static final double GROUP_RALLY_RADIUS = 24.0D;

    /**
     * Nearby hired workers of {@code ownerId} around {@code center} - the group a single worker's
     * retaliation should pull in, shared by WorkerTeamPvPHandler (declared-war PvP) and
     * EntityAIWorkerHurtByTargetTraveller (everything else canRetaliateAgainst already allows:
     * mobs, AW2 hostiles, enemy hired workers). Loaded-side only, same as the offscreen side's
     * own group pull-in in BattleEncounterScanner.
     */
    public static List<EntityTravellerWorker> nearbyOwnedMates(World world, BlockPos center, UUID ownerId) {
        if (!(world instanceof WorldServer) || ownerId == null) return Collections.emptyList();
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(center)
                .grow(GROUP_RALLY_RADIUS, 8.0D, GROUP_RALLY_RADIUS);
        return world.getEntitiesWithinAABB(EntityTravellerWorker.class, box,
                m -> m != null && m.isEntityAlive() && m.isHiredFollower() && ownerId.equals(m.getFollowOwnerId()));
    }

    /**
     * Pulls every nearby same-owner hired worker into a fight against {@code attacker}, without
     * stealing a mate that's already busy with a live target. Callers are expected to have already
     * decided the single worker in {@code self} is allowed to fight {@code attacker} (this doesn't
     * re-check canRetaliateAgainst for the group - the whole point is that admission was already
     * granted once, at the individual level).
     */
    public static void rallyGroupAgainst(EntityTravellerWorker self, EntityLivingBase attacker, @Nullable UUID enemyOwner) {
        UUID ownerId = self.getFollowOwnerId();
        if (ownerId == null || attacker == null || !attacker.isEntityAlive()) return;
        for (EntityTravellerWorker mate : nearbyOwnedMates(self.world, self.getPosition(), ownerId)) {
            if (mate == self) continue;
            if (enemyOwner != null) mate.playerHostility.engageEnemyOwner(enemyOwner);
            EntityLivingBase cur = mate.getAttackTarget();
            if (cur == null || !cur.isEntityAlive()) {
                mate.setAttackTarget(attacker);
            }
        }
    }

    public boolean isEnemyMillVillager(org.millenaire.common.entity.MillVillager v) {
        if (v == null || !v.isEntityAlive()) return false;
        if (!(w.world instanceof net.minecraft.world.WorldServer)) return false;

        java.util.UUID ownerId = w.getFollowOwnerId();
        if (ownerId == null) return false;

        Building th = v.getTownHall();
        if (th == null) return false;

       Point p = th.getPos();
        if (p == null) return false;

        net.minecraft.util.math.BlockPos center = new net.minecraft.util.math.BlockPos(p.getiX(), p.getiY(), p.getiZ());

        TravellerVillageRelations rel =
                TravellerVillageRelations.get((net.minecraft.world.WorldServer) w.world);

        int dim = w.world.provider.getDimension();
        return rel.isEnemy(ownerId, dim, center);
    }
}
