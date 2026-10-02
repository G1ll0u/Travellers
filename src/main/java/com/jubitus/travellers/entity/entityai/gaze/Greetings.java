package com.jubitus.travellers.entity.entityai.gaze;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Who has greeted whom, by party: a lone traveller, a traveller group (its leader's UUID), or a
 * player together with their hired workers (the player's UUID). One hello per pair of parties per
 * cooldown, whichever member says it - shared by the spontaneous greeting and the right-click hello.
 * Server thread only, never saved.
 */
public final class Greetings {

    private static final Map<World, Map<UUID, Map<UUID, Long>>> LEDGER = new WeakHashMap<>();
    private static int recordsSincePrune;

    private Greetings() {
    }

    /** The party an entity greets and is greeted as; null for anyone who never takes part. */
    @Nullable
    public static UUID partyOf(@Nullable Entity e) {
        if (e instanceof EntityPlayer) return ((EntityPlayer) e).isSpectator() ? null : e.getUniqueID();
        if (!(e instanceof EntityTraveller) || e instanceof EntityTravellerRaider) return null;
        EntityTraveller t = (EntityTraveller) e;
        UUID owner = t.getFollowOwnerId();
        if (owner != null) return owner;
        UUID leader = t.getGroupLeaderUUID();
        return leader != null ? leader : t.getUniqueID();
    }

    public static boolean hasGreeted(World world, UUID from, UUID to) {
        Map<UUID, Map<UUID, Long>> byFrom = LEDGER.get(world);
        Map<UUID, Long> greeted = byFrom == null ? null : byFrom.get(from);
        Long at = greeted == null ? null : greeted.get(to);
        return at != null && world.getTotalWorldTime() - at < cooldownTicks();
    }

    public static void record(World world, UUID from, UUID to) {
        long now = world.getTotalWorldTime();
        Map<UUID, Map<UUID, Long>> byFrom = LEDGER.computeIfAbsent(world, w -> new HashMap<>());
        byFrom.computeIfAbsent(from, k -> new HashMap<>()).put(to, now);
        if (++recordsSincePrune >= 64) {
            recordsSincePrune = 0;
            prune(byFrom, now);
        }
    }

    private static void prune(Map<UUID, Map<UUID, Long>> byFrom, long now) {
        long cooldown = cooldownTicks();
        Iterator<Map<UUID, Long>> it = byFrom.values().iterator();
        while (it.hasNext()) {
            Map<UUID, Long> greeted = it.next();
            greeted.values().removeIf(at -> now - at >= cooldown);
            if (greeted.isEmpty()) it.remove();
        }
    }

    private static long cooldownTicks() {
        return (long) (TravellersConfig.GAZE.greetCooldownSeconds * 20.0D);
    }
}
