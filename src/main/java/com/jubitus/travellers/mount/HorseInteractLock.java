package com.jubitus.travellers.mount;

import com.google.common.base.Optional;
import com.jubitus.travellers.config.TravellersConfig;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client-visible "hands off" marker on a horse, synced through a DataParameter added by
 * {@code MixinEntityHorse}. Server truth stays in {@link TravellerMountSupport#isBondedMount} and the
 * Stable pool; this only mirrors it so the client refuses the interaction too. Without it the client
 * predicts the mount, the server refuses it, and the player is snapped off beside the horse.
 */
public final class HorseInteractLock {

    /** Locked for everyone (a bonded mount). Any other UUID = only that player may interact. */
    public static final UUID NOBODY = new UUID(0L, 0L);

    private static final int POOL_REFRESH_TICKS = 20;

    private HorseInteractLock() {
    }

    /** Implemented on {@link EntityHorse} by {@code MixinEntityHorse}. */
    public interface Holder {
        Optional<UUID> travellers$getInteractLock();

        void travellers$setInteractLock(Optional<UUID> lock);
    }

    /** Server tick: keeps the synced lock in step with the bond / Stable pool state. */
    public static void tick(EntityHorse horse) {
        if (!(horse.world instanceof WorldServer) || !(horse instanceof Holder)) return;
        Holder h = (Holder) horse;
        UUID current = h.travellers$getInteractLock().orNull();

        UUID wanted;
        if (TravellersConfig.MOUNTS.preventPlayerTheft && TravellerMountSupport.isBondedMount(horse)) {
            wanted = NOBODY;
        } else {
            boolean poolLockFresh = current != null && !NOBODY.equals(current)
                    && horse.ticksExisted % POOL_REFRESH_TICKS != 0;
            if (poolLockFresh) return;
            wanted = stablePoolLock((WorldServer) horse.world, horse);
        }
        if (wanted == null ? current != null : !wanted.equals(current)) {
            h.travellers$setInteractLock(Optional.fromNullable(wanted));
        }
    }

    /** Client side: true if this player must not interact with / mount {@code target}. */
    public static boolean blocksClient(Entity target, @Nullable EntityPlayer player) {
        if (!(target instanceof Holder)) return false;
        UUID lock = ((Holder) target).travellers$getInteractLock().orNull();
        if (lock == null) return false;
        return NOBODY.equals(lock) || player == null || !lock.equals(player.getUniqueID());
    }

    @Nullable
    private static UUID stablePoolLock(WorldServer world, EntityHorse horse) {
        if (!TravellersConfig.STABLES.enabled) return null;
        com.jubitus.travellers.zone.stable.StableCoordinator sc =
                com.jubitus.travellers.zone.stable.StableCoordinator.get(world);
        if (!com.jubitus.travellers.zone.stable.StableCoordinator.isPoolHorse(horse)) return null;
        UUID owner = sc.getPoolOwner(world, horse);
        return owner != null ? owner : NOBODY;
    }
}
