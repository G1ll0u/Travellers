package com.jubitus.travellers.world.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.notify.NotificationDef;
import com.jubitus.travellers.notify.NotificationDefs;
import com.jubitus.travellers.notify.NotificationService;
import com.jubitus.travellers.world.zone.PlayerZoneRecord;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

/**
 * The rules of stable work - taming, saddling, breeding - as pure state changes on
 * {@link StableHorseEntry} / {@link StableZoneData}, with no knowledge of whether the Stable is
 * loaded. Two callers drive it:
 * <ul>
 *   <li><b>Loaded</b>: {@link StableHandTask} calls {@link #tameStep}, {@link #saddle} and
 *       {@link #breed} one action at a time, at the moment the palfrenier performs it, against a
 *       {@link RealChestSource}. The palfrenier is the visible cause; the effect is real.</li>
 *   <li><b>Offscreen</b>: {@link #timerPass} runs the same three progressions on elapsed time
 *       against a {@link MirrorChestSource}, from {@code StableCoordinator} - but only while no
 *       loaded palfrenier is on station, so the two never advance the same pool at once.</li>
 * </ul>
 *
 * <p>Ownership rule: a pool entry's {@code tamed}/{@code saddled}/temper progression is owned here.
 * The loaded caller reflects each change onto the real {@code EntityHorse} it is working; the
 * offscreen caller writes {@link StableHorseEntry#horseNbt} for a truly dormant entry and lets
 * {@code StablePhysicalReconciler} push the entry onto a reloaded (stale) entity otherwise.
 */
public final class StableWork {

    static final int TEMPER_GAIN_FED = 3;
    static final int TEMPER_GAIN_UNFED = 2;
    /** {@code AbstractHorse.getMaxTemper()} is a hard-coded 100 in vanilla - inlined so the rules
     * needn't build a scratch entity every taming tick just to read a constant. */
    static final int MAX_TEMPER = 100;
    static final int FOAL_AGE = -24000;

    /** Upper bound on how many missed intervals one {@link #timerPass} call replays, per
     * progression - on a live overworld server there is never a gap (the coordinator ticks every
     * world tick), so this only ever matters after server downtime. */
    private static final int MAX_TAME_CATCHUP = 50;
    private static final int MAX_BREED_CATCHUP = 4;
    private static final int MAX_SADDLE_CATCHUP = 16;

    private StableWork() {
    }

    // =========================================================================
    // Individual ops - the loaded palfrenier calls one of these per action
    // =========================================================================

    /** The outcome of one {@link #tameStep}. */
    public static final class TameResult {
        public final boolean fed;
        public final boolean tamedNow;

        TameResult(boolean fed, boolean tamedNow) {
            this.fed = fed;
            this.tamedNow = tamedNow;
        }
    }

    /**
     * One taming interaction on {@code e}: spends one wheat from {@code chest} if available
     * ({@code fed}), raises temper by {@link #TEMPER_GAIN_FED}/{@link #TEMPER_GAIN_UNFED}
     * (+{@code bonusTemper} for the mount-and-buck beat), then rolls {@code rand(100) < temper} for
     * a tame. Updates the <b>entry</b> only - the caller reflects {@code tamed}/temper onto any live
     * entity. Fires the owner "horse tamed" notification on success, once.
     */
    public static TameResult tameStep(WorldServer world, PlayerZoneRecord zone, StableHorseEntry e,
                                      ChestSource chest, int bonusTemper) {
        boolean fed = chest.consume(Items.WHEAT);
        int gain = (fed ? TEMPER_GAIN_FED : TEMPER_GAIN_UNFED) + Math.max(0, bonusTemper);
        e.temper = Math.min(MAX_TEMPER, e.temper + gain);

        boolean tamedNow = world.rand.nextInt(MAX_TEMPER) < e.temper;
        if (tamedNow && !e.tamed) {
            e.tamed = true;
            notify(world, zone, NotificationDefs.STABLE_HORSE_TAMED, TravellersConfig.STABLES.notifyOnTame);
            StableWatchLog.record(world, zone, StableWatchLog.Kind.TAME,
                    StableWatchLog.shortId(e.poolId) + " temper=" + e.temper, chest.isLive());
        }
        return new TameResult(fed, tamedNow);
    }

    /** Spends one saddle from {@code chest} and marks {@code e} saddled. Caller puts the real saddle
     * on the real horse. */
    public static boolean saddle(WorldServer world, PlayerZoneRecord zone, StableHorseEntry e, ChestSource chest) {
        if (e.saddled || !chest.consume(Items.SADDLE)) return false;
        e.saddled = true;
        notify(world, zone, NotificationDefs.STABLE_HORSE_SADDLED, TravellersConfig.STABLES.notifyOnSaddle);
        StableWatchLog.record(world, zone, StableWatchLog.Kind.SADDLE,
                StableWatchLog.shortId(e.poolId), chest.isLive());
        return true;
    }

    /**
     * Attempts one deliberate breeding of {@code a} and {@code b}: checks the player-wide and
     * per-pen caps and the pair's cooldown, spends two golden carrots/apples from {@code chest},
     * and appends a {@code BRED} dormant entry (a foal, aged from {@link #FOAL_AGE}) to the pool.
     * Returns the new entry, or null if any gate failed. The loaded caller may materialize the
     * returned entry into a real foal at once; the offscreen caller lets the reconcile trickle do
     * it later.
     */
    public static StableHorseEntry breed(WorldServer world, PlayerZoneRecord zone, StableZoneData data,
                                         ChestSource chest, long now, StableHorseEntry a, StableHorseEntry b) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0) : null;
        if (overworld == null) return null;
        if (!StableCapacity.canBreed(overworld, zone.ownerUUID)) return null;
        if (!StableCapacity.hasRoomFor(zone)) return null;
        if (a == null || b == null || a == b) return null;
        if (!eligibleForBreeding(a, now) || !eligibleForBreeding(b, now)) return null;

        Item feed = chest.available(Items.GOLDEN_CARROT) >= 2 ? Items.GOLDEN_CARROT
                : chest.available(Items.GOLDEN_APPLE) >= 2 ? Items.GOLDEN_APPLE : null;
        if (feed == null) return null;
        chest.consume(feed);
        chest.consume(feed);

        StableHorseEntry foal = buildFoal(world, zone, a, now);
        data.pool.add(foal);

        long cooldownUntil = now + TravellersConfig.STABLES.breedingCooldownTicks;
        a.breedingCooldownUntil = cooldownUntil;
        b.breedingCooldownUntil = cooldownUntil;

        notify(world, zone, NotificationDefs.STABLE_FOAL_BORN, TravellersConfig.STABLES.notifyOnBirth);
        StableWatchLog.record(world, zone, StableWatchLog.Kind.BORN,
                StableWatchLog.shortId(foal.poolId) + " parents " + StableWatchLog.shortId(a.poolId)
                        + "+" + StableWatchLog.shortId(b.poolId), chest.isLive());
        return foal;
    }

    public static boolean eligibleForBreeding(StableHorseEntry e, long now) {
        return e != null && e.tamed && e.growingAge >= 0 && e.horseNbt != null
                && e.breedingCooldownUntil <= now;
    }

    /** Whether at least two pool entries could breed together right now (cooldown, age, tamed) -
     * ignores the caps, so it can tell "a pair is ready but a cap blocks it" from "no pair". */
    public static boolean eligiblePairExists(StableZoneData data, long now) {
        return firstEligiblePair(data, now) != null;
    }

    /**
     * Cheap "may a deliberate breeding start at all" check - the player-wide horse cap, the per-pen
     * area guard-rail, and an eligible pair. Both schedulers ({@link StableHandTask#decide} and
     * {@link #timerPass}) call this <b>before</b> any chest trip / heart animation, so a palfrenier
     * at cap never mimes a breeding that {@link #breed} would just refuse. {@link #breed} keeps its
     * own final check as the race backstop.
     */
    public static boolean canBreedNow(WorldServer world, PlayerZoneRecord zone, StableZoneData data, long now) {
        WorldServer overworld = world.getMinecraftServer() != null
                ? world.getMinecraftServer().getWorld(0) : null;
        if (overworld == null) return false;
        if (!StableCapacity.canBreed(overworld, zone.ownerUUID)) return false;
        if (!StableCapacity.hasRoomFor(zone)) return false;
        return eligiblePairExists(data, now);
    }

    // =========================================================================
    // Offscreen timer pass - same three progressions, elapsed time, mirror supply
    // =========================================================================

    /**
     * Advances one step of stable work purely from elapsed time. {@code hands} is how many
     * palfreniers are on station offscreen - it scales taming/saddling throughput. Returns true if
     * any persisted state changed. Only called by {@code StableCoordinator} when there is no loaded
     * palfrenier working the zone.
     */
    public static boolean timerPass(WorldServer world, PlayerZoneRecord zone, StableZoneData data,
                                    long now, int hands) {
        if (hands <= 0) return false;
        boolean dirty = false;
        ChestSource chest = new MirrorChestSource(data.chestMirror);

        int tameInterval = TravellersConfig.STABLES.tamingAttemptIntervalTicks;
        int tamingBudget = hands;
        for (StableHorseEntry e : data.pool) {
            if (tamingBudget <= 0) break;
            if (e.tamed || e.horseNbt == null) continue;
            if (e.lastTameProgressTick == 0) {
                e.lastTameProgressTick = now;
                continue;
            }
            long missed = (now - e.lastTameProgressTick) / tameInterval;
            if (missed <= 0) continue;
            int steps = (int) Math.min(missed, MAX_TAME_CATCHUP);
            e.lastTameProgressTick = now;
            tamingBudget--;
            for (int i = 0; i < steps && !e.tamed; i++) {
                tameStep(world, zone, e, chest, 0);
                writeDormantTaming(world, zone, e);
                dirty = true;
            }
        }

        int saddleBudget = Math.min(hands * MAX_SADDLE_CATCHUP, chest.available(Items.SADDLE));
        for (StableHorseEntry e : data.pool) {
            if (saddleBudget <= 0) break;
            if (!e.tamed || e.saddled || e.horseNbt == null || e.growingAge < 0) continue;
            if (!saddle(world, zone, e, chest)) break;
            writeDormantSaddle(world, e);
            saddleBudget--;
            dirty = true;
        }

        int breedInterval = TravellersConfig.STABLES.breedingCheckIntervalTicks;
        if (data.lastBreedTick == 0) {
            data.lastBreedTick = now;
        } else if (now - data.lastBreedTick > (long) MAX_BREED_CATCHUP * breedInterval) {
            data.lastBreedTick = now - (long) MAX_BREED_CATCHUP * breedInterval;
        }
        if (!canBreedNow(world, zone, data, now)) {
            // A cap (player-wide or per-pen) blocks breeding: keep the anchor fresh so no catch-up
            // burst accrues while blocked, and note it once if a pair was actually waiting.
            if (now - data.lastBreedTick >= breedInterval && eligiblePairExists(data, now)) {
                StableWatchLog.record(world, zone, StableWatchLog.Kind.HELD, "breeding: cap reached", false);
            }
            data.lastBreedTick = now;
            return dirty;
        }
        int breedCycles = 0;
        while (now - data.lastBreedTick >= breedInterval && breedCycles < MAX_BREED_CATCHUP) {
            data.lastBreedTick += breedInterval;
            breedCycles++;
            StableHorseEntry[] pair = firstEligiblePair(data, now);
            if (pair != null && breed(world, zone, data, chest, now, pair[0], pair[1]) != null) {
                dirty = true;
            }
        }
        return dirty;
    }

    /** The first two pool entries that may breed together right now, or null. */
    public static StableHorseEntry[] firstEligiblePair(StableZoneData data, long now) {
        StableHorseEntry first = null;
        for (StableHorseEntry e : data.pool) {
            if (!eligibleForBreeding(e, now)) continue;
            if (first == null) {
                first = e;
            } else {
                return new StableHorseEntry[]{first, e};
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------

    /**
     * Writes a timer-pass taming step into a <b>truly dormant</b> entry's {@link
     * StableHorseEntry#horseNbt}. An entry that still has a {@code liveEntityUUID} is left to the
     * ownership rule - the entry is authoritative and {@code StablePhysicalReconciler} pushes it
     * onto the (stale) entity when the zone finishes loading. The timer pass never runs while a
     * loaded palfrenier is on station, so there is no visible entity to keep in step here.
     */
    private static void writeDormantTaming(WorldServer world, PlayerZoneRecord zone, StableHorseEntry e) {
        if (e.liveEntityUUID != null || e.horseNbt == null) return;
        e.horseNbt.setInteger("Temper", e.temper);
        if (e.tamed) {
            EntityHorse scratch = new EntityHorse(world);
            scratch.readFromNBT(e.horseNbt);
            scratch.setHorseTamed(true);
            scratch.setOwnerUniqueId(zone.ownerUUID);
            NBTTagCompound fresh = new NBTTagCompound();
            scratch.writeToNBT(fresh);
            e.horseNbt = fresh;
        }
    }

    private static void writeDormantSaddle(WorldServer world, StableHorseEntry e) {
        if (e.liveEntityUUID != null || e.horseNbt == null) return;
        EntityHorse scratch = new EntityHorse(world);
        scratch.readFromNBT(e.horseNbt);
        scratch.replaceItemInInventory(400, new ItemStack(Items.SADDLE));
        NBTTagCompound fresh = new NBTTagCompound();
        scratch.writeToNBT(fresh);
        e.horseNbt = fresh;
    }

    // -------------------------------------------------------------------------

    static StableHorseEntry buildFoal(WorldServer world, PlayerZoneRecord zone, StableHorseEntry parent, long now) {
        EntityHorse foalEnt = new EntityHorse(world);
        foalEnt.readFromNBT(parent.horseNbt);
        foalEnt.setHorseTamed(true);
        foalEnt.setOwnerUniqueId(zone.ownerUUID);
        foalEnt.setGrowingAge(FOAL_AGE);
        foalEnt.setHealth(foalEnt.getMaxHealth());
        foalEnt.replaceItemInInventory(400, ItemStack.EMPTY);
        foalEnt.replaceItemInInventory(401, ItemStack.EMPTY);

        NBTTagCompound foalNbt = new NBTTagCompound();
        foalEnt.writeToNBT(foalNbt);
        // Identity + gear the foal must not inherit.
        for (String t : new String[]{"UUIDMost", "UUIDLeast", "Leash", "ArmorItem", "SaddleItem", "Items",
                "CustomName", "CustomNameVisible", "Tags", "Bred", "EatingHaystack"}) {
            foalNbt.removeTag(t);
        }
        // Transient state the parent snapshot carries that a foal must not inherit. Position and
        // motion are left in (StableMaterializer overrides the position and zeroes the motion
        // anyway) - only the tags that would otherwise wedge or mis-state the fresh foal go.
        for (String t : new String[]{"FallDistance", "Fire", "PortalCooldown", "HurtTime",
                "HurtByTimestamp", "DeathTime", "Passengers", "Riding", "Sleeping",
                "SleepingX", "SleepingY", "SleepingZ"}) {
            foalNbt.removeTag(t);
        }

        StableHorseEntry foal = new StableHorseEntry();
        foal.origin = StableHorseEntry.Origin.BRED;
        foal.tamed = true;
        foal.saddled = false;
        foal.growingAge = FOAL_AGE;
        foal.reservedAtTick = now;
        foal.lastAgeSyncTick = now;
        foal.lastTameProgressTick = now;
        foal.horseNbt = foalNbt;
        return foal;
    }

    private static void notify(WorldServer world, PlayerZoneRecord zone, NotificationDef def, boolean enabled) {
        if (!enabled || world.getMinecraftServer() == null) return;
        String name = (zone.name == null || zone.name.isEmpty()) ? "Stable" : zone.name;
        NotificationService.notifyOffline(world.getMinecraftServer(), zone.ownerUUID, def, name);
    }
}
