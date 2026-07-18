package com.jubitus.travellers.entity.entityAI.combat;

import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "travellers")
public final class WorkerTeamPvPHandler {

    private static final double ENGAGE_RADIUS = 24.0D;

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityLivingBase)) return;

        EntityLivingBase victim = event.getEntityLiving();
        if (victim.world.isRemote) return;

        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityLivingBase)) return;

        EntityLivingBase attacker = (EntityLivingBase) src;

        // We only care about player/worker PvP interactions
        UUID victimOwner = EntityTravellerWorker.getOwnerIdOf(victim);
        UUID attackerOwner = EntityTravellerWorker.getOwnerIdOf(attacker);

        if (victimOwner == null || attackerOwner == null) return;
        if (victimOwner.equals(attackerOwner)) return;

        // Ignore creative/spectator attackers
        if (attacker instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) attacker;
            if (p.isCreative() || p.isSpectator()) return;
        }

        // --- Critical constraint: defender engages ONLY if attacker declared war ---
        if (!attackerDeclaredWar(attacker, attackerOwner, victimOwner, victim)) {
            return;
        }

        // Engage all nearby workers of victimOwner and set target
        AxisAlignedBB box = victim.getEntityBoundingBox().grow(ENGAGE_RADIUS, 8.0D, ENGAGE_RADIUS);
        List<EntityTravellerWorker> defenders = victim.world.getEntitiesWithinAABB(
                EntityTravellerWorker.class,
                box,
                w -> w != null
                        && w.isEntityAlive()
                        && w.isHiredFollower()
                        && victimOwner.equals(w.getFollowOwnerId())
        );

        boolean notified = false;

        for (EntityTravellerWorker w : defenders) {
            // Only those in the area permanently mark attacker owner as enemy (local list)
            boolean added = w.getHostility().addLocalHostile(attackerOwner);

            // Notify owner once per event if anyone added it
            if (added && !notified) {
                notified = true;

                EntityPlayer owner = victim.world.getPlayerEntityByUUID(victimOwner);
                if (owner != null) {
                    String name = attackerOwner.toString();
                    if (victim.world instanceof WorldServer) {
                        MinecraftServer srv = ((WorldServer) victim.world).getMinecraftServer();
                        if (srv != null) {
                            GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(attackerOwner);
                            if (gp != null && gp.getName() != null) name = gp.getName();
                        }
                    }
                    NotificationService.notify(owner, NotificationDefs.WORKER_RETALIATION_TARGET, name);
                }
            }

            // Immediate combat commitment
            w.engageEnemyOwner(attackerOwner);
            if (w.getAttackTarget() == null || !w.getAttackTarget().isEntityAlive()) {
                w.setAttackTarget(attacker);
            }
        }


        // Also engage the victim worker itself (if it is a worker)
        if (victim instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) victim).engageEnemyOwner(attackerOwner);
        }
    }

    private static boolean attackerDeclaredWar(EntityLivingBase attacker, UUID attackerOwner, UUID victimOwner, EntityLivingBase victim) {
        // If attacker is a worker: just read its declared hostile list
        if (attacker instanceof EntityTravellerWorker) {
            EntityTravellerWorker aw = (EntityTravellerWorker) attacker;
            return aw.getEffectiveHostilePlayers().contains(victimOwner);
        }

        // If attacker is a player: check nearby attacker-owned workers for declaration
        if (attacker instanceof EntityPlayer) {
            EntityPlayer ap = (EntityPlayer) attacker;

            AxisAlignedBB nearAttacker = ap.getEntityBoundingBox().grow(EntityTravellerWorker.PVP_DECLARE_CHECK_RADIUS, 16.0D, EntityTravellerWorker.PVP_DECLARE_CHECK_RADIUS);
            List<EntityTravellerWorker> near = ap.world.getEntitiesWithinAABB(
                    EntityTravellerWorker.class,
                    nearAttacker,
                    w -> w != null
                            && w.isEntityAlive()
                            && w.isHiredFollower()
                            && attackerOwner.equals(w.getFollowOwnerId())
                            && w.getEffectiveHostilePlayers().contains(victimOwner)
            );
            if (!near.isEmpty()) return true;

            // also check near victim (projectiles / distance)
            AxisAlignedBB nearVictim = victim.getEntityBoundingBox().grow(EntityTravellerWorker.PVP_DECLARE_CHECK_RADIUS, 16.0D, EntityTravellerWorker.PVP_DECLARE_CHECK_RADIUS);
            List<EntityTravellerWorker> near2 = victim.world.getEntitiesWithinAABB(
                    EntityTravellerWorker.class,
                    nearVictim,
                    w -> w != null
                            && w.isEntityAlive()
                            && w.isHiredFollower()
                            && attackerOwner.equals(w.getFollowOwnerId())
                            && w.getEffectiveHostilePlayers().contains(victimOwner)
            );
            return !near2.isEmpty();
        }

        return false;
    }
}
