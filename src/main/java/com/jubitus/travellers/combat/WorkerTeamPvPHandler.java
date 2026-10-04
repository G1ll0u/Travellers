package com.jubitus.travellers.combat;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "travellers")
public final class WorkerTeamPvPHandler {

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntityLiving() instanceof EntityLivingBase)) return;

        EntityLivingBase victim = event.getEntityLiving();
        if (victim.world.isRemote) return;

        Entity src = event.getSource().getTrueSource();
        if (!(src instanceof EntityLivingBase)) return;

        EntityLivingBase attacker = (EntityLivingBase) src;

        // We only care about player/worker PvP interactions
        UUID victimOwner = PlayerHostility.ownerIdOf(victim);
        UUID attackerOwner = PlayerHostility.ownerIdOf(attacker);

        if (victimOwner == null || attackerOwner == null) return;
        if (victimOwner.equals(attackerOwner)) return;

        // Ignore creative/spectator attackers
        if (attacker instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) attacker;
            if (p.isCreative() || p.isSpectator()) return;
        }

        if (!(victim.world instanceof WorldServer)) return;
        WorldServer ws = (WorldServer) victim.world;

        // --- Critical constraint: defender engages ONLY if attacker declared war ---
        if (!PlayerEnemyRelations.get(ws).isHostile(attackerOwner, victimOwner)) {
            return;
        }

        // First blood under a declared, one-sided war: from now on the victim's side is at war
        // too, in all circumstances — not just the workers/area involved in this one hit. This is
        // the "betrayal" moment, so notify the victim even if they're offline right now.
        boolean victimNowAtWar = PlayerEnemyRelations.get(ws).setHostile(victimOwner, attackerOwner, true);
        if (victimNowAtWar) {
            MinecraftServer srv = ws.getMinecraftServer();
            if (srv != null) {
                NotificationService.notifyEventOffline(srv, victimOwner, NotificationDefs.WAR_DECLARED_ON_YOU,
                        null, resolveName(ws, attackerOwner));
            }
        }

        // Engage all nearby workers of victimOwner and set target - same group-rally query
        // EntityAIWorkerHurtByTargetTraveller uses for non-PvP retaliation, see
        // PlayerHostility.nearbyOwnedMates.
        List<EntityTravellerWorker> defenders =
                PlayerHostility.nearbyOwnedMates(victim.world, victim.getPosition(), victimOwner);

        boolean notified = false;

        for (EntityTravellerWorker w : defenders) {
            // Only those in the area permanently mark attacker owner as enemy (local list)
            boolean added = w.playerHostility.addLocalHostile(attackerOwner);

            // Notify owner once per event if anyone added it
            if (added && !notified) {
                notified = true;

                EntityPlayer owner = victim.world.getPlayerEntityByUUID(victimOwner);
                if (owner != null) {
                    NotificationService.notify(owner, NotificationDefs.WORKER_RETALIATION_TARGET, resolveName(ws, attackerOwner));
                }
            }

            // Immediate combat commitment
            w.playerHostility.engageEnemyOwner(attackerOwner);
            if (w.getAttackTarget() == null || !w.getAttackTarget().isEntityAlive()) {
                w.setAttackTarget(attacker);
            }
        }


        // Also engage the victim worker itself (if it is a worker)
        if (victim instanceof EntityTravellerWorker) {
            ((EntityTravellerWorker) victim).playerHostility.engageEnemyOwner(attackerOwner);
        }
    }

    private static String resolveName(WorldServer ws, UUID playerId) {
        MinecraftServer srv = ws.getMinecraftServer();
        if (srv != null) {
            GameProfile gp = srv.getPlayerProfileCache().getProfileByUUID(playerId);
            if (gp != null && gp.getName() != null) return gp.getName();
        }
        return playerId.toString();
    }
}
