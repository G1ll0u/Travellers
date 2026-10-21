package com.jubitus.travellers.combat;

import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * One help call per attacker per group every few seconds, so a hit does not ripple through a whole
 * army. Shared by the call for help (TravellerHurtByTarget) and the hired workers' rally.
 */
public final class HelpCallGate {

    public static final int COOLDOWN_TICKS = 3 * 20;
    private static final int SWEEP_SIZE = 512;

    // group -> attacker -> world tick of the last call
    private static final Map<UUID, Map<UUID, Long>> LAST_CALL = new HashMap<>();

    private HelpCallGate() {
    }

    /** True (and the call is recorded) when {@code caller}'s group has not called for help against {@code attacker} lately. */
    public static boolean tryCall(EntityTraveller caller, EntityLivingBase attacker) {
        long now = caller.world.getTotalWorldTime();
        Map<UUID, Long> calls = LAST_CALL.computeIfAbsent(groupOf(caller), k -> new HashMap<>());
        Long last = calls.get(attacker.getUniqueID());
        if (last != null && now - last < COOLDOWN_TICKS) return false;
        calls.put(attacker.getUniqueID(), now);
        if (LAST_CALL.size() > SWEEP_SIZE) sweep(now);
        return true;
    }

    /** How many melee allies a call may still send at {@code attacker}: the free attacker slots on it. */
    public static int freeMeleeSlots(EntityTraveller caller, EntityLivingBase attacker) {
        return Math.max(0, caller.computeEffectiveAttackerCap(attacker) - AttackTargetLimiter.getAttackerCount(attacker));
    }

    private static UUID groupOf(EntityTraveller unit) {
        if (unit instanceof EntityTravellerWorker) {
            UUID owner = ((EntityTravellerWorker) unit).getFollowOwnerId();
            if (owner != null) return owner;
        }
        UUID leader = unit.getGroupLeaderUUID();
        return leader != null ? leader : unit.getUniqueID();
    }

    private static void sweep(long now) {
        Iterator<Map<UUID, Long>> groups = LAST_CALL.values().iterator();
        while (groups.hasNext()) {
            Map<UUID, Long> calls = groups.next();
            calls.values().removeIf(t -> now - t >= COOLDOWN_TICKS);
            if (calls.isEmpty()) groups.remove();
        }
    }
}
