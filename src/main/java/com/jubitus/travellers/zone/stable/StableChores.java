package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import static com.jubitus.travellers.zone.stable.StableHandSupport.*;

/** Palfrenier chest trips, saddling and breeding phases of {@link StableHandTask}. */
final class StableChores {

    private static final int BREED_FEED_BEATS = 3;

    private final StableHandSupport support;

    StableChores(StableHandSupport support) {
        this.support = support;
    }

    void fetchFeed(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(world, st);
        if (horse == null || horse.isTame() || poolEntry(data, horse) == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        if (support.countItem(world, zone, Items.WHEAT) <= 0) {
            // Wheat gone before we got there - just tame dry. Shut the chest if we already opened it.
            if (st.chestOpen && st.targetChest != null) support.closeChestVisual(world, st.targetChest);
            st.chestOpen = false;
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            st.targetSince = now;
            st.approachStartTick = -1L;
            return;
        }
        if (walkToChestAndGrab(world, worker, zone, st, now, Items.WHEAT)) {
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            st.targetSince = now;
            st.approachStartTick = -1L;
        }
    }

    void fetchCarrots(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                              StableZoneData data, PalfrenierState st, long now) {
        if (!StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdA), now)
                || !StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdB), now)) {
            support.abandonChore(world, worker, st);
            return;
        }
        Item feed = support.breedingFeedItem(world, zone);
        if (feed == null) {
            support.abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }
        if (walkToChestAndGrab(world, worker, zone, st, now, feed)) {
            st.phase = PalfrenierState.Phase.MOVE_TO_PAIR;
            st.targetSince = now;
            st.approachStartTick = -1L;
        }
    }

    /** Walk to {@code st.targetChest}, open it, hold {@code prop}, close it. Returns true the tick
     * the prop is in hand and the chest is shut; false while still working (or after a give-up,
     * which resets the state itself). */
    boolean walkToChestAndGrab(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                       PalfrenierState st, long now, Item prop) {
        BlockPos chest = st.targetChest;
        if (chest == null) return true;
        double cx = chest.getX() + 0.5D, cy = chest.getY() + 0.5D, cz = chest.getZ() + 0.5D;
        if (!st.chestOpen) {
            if (support.giveUpUnreachable(worker, st, cx, cy, cz, now)) return false;
            if (!support.approachWorkSpot(worker, st, cx, cy, cz, now)) return false;
            worker.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
            support.openChestVisual(world, chest);
            st.chestOpen = true;
            st.phaseEndTick = now + TravellersConfig.STABLES.chestOpenTicks;
            return false;
        }
        worker.getLookHelper().setLookPosition(cx, cy, cz, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return false;
        worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(prop));
        support.closeChestVisual(world, chest);
        st.chestOpen = false;
        return true;
    }

    void moveToChestForSaddle(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                      StableZoneData data, PalfrenierState st, long now) {
        BlockPos chest = st.targetChest;
        EntityHorse horse = support.resolveTargetHorse(world, st);
        if (chest == null || horse == null || poolEntry(data, horse) == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        if (support.giveUpUnreachable(worker, st, chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, now)) return;
        if (!support.approachWorkSpot(worker, st, chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, now)) return;
        worker.getLookHelper().setLookPosition(chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, 30.0F, 30.0F);
        st.phase = PalfrenierState.Phase.OPEN_CHEST_TAKE_SADDLE;
        st.chestOpen = false;
    }

    void openChestTakeSaddle(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                                     PalfrenierState st, long now) {
        if (!st.chestOpen) {
            if (support.countItem(world, zone, Items.SADDLE) <= 0 || st.targetChest == null) {
                support.abandonChore(world, worker, st);
                st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
                return;
            }
            support.openChestVisual(world, st.targetChest);
            st.chestOpen = true;
            st.phaseEndTick = now + TravellersConfig.STABLES.chestOpenTicks;
        }
        BlockPos chest = st.targetChest;
        worker.getLookHelper().setLookPosition(chest.getX() + 0.5D, chest.getY() + 0.5D, chest.getZ() + 0.5D, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;

        worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(Items.SADDLE)); // carried prop; consumed on apply
        support.closeChestVisual(world, st.targetChest);
        st.chestOpen = false;
        st.phase = PalfrenierState.Phase.CARRY_SADDLE_TO_HORSE;
        st.targetSince = now;
    }

    void carrySaddleToHorse(WorldServer world, EntityTravellerWorker worker, StableZoneData data,
                                    PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(world, st);
        if (horse == null || !horse.isTame() || horse.isHorseSaddled() || poolEntry(data, horse) == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        if (support.giveUpUnreachable(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        if (!support.approachWorkSpot(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        st.phase = PalfrenierState.Phase.APPLY_SADDLE;
        st.phaseEndTick = now + 10L;
    }

    void applySaddle(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                             StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(world, st);
        StableHorseEntry entry = horse == null ? null : poolEntry(data, horse);
        if (horse == null || horse.isHorseSaddled() || entry == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;
        worker.doInteractSwing(EnumHand.MAIN_HAND);

        if (StableWork.saddle(world, zone, entry, new RealChestSource(world, zone))) {
            horse.replaceItemInInventory(400, new ItemStack(Items.SADDLE));
            PlayerZoneManager.get(world).markDirty();
        }
        // Whether or not a saddle was still there (the player may have emptied the chest mid-carry),
        // this attempt is done - drop the prop and re-decide.
        support.abandonChore(world, worker, st);
        st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
    }

    void moveToPair(WorldServer world, EntityTravellerWorker worker, StableZoneData data,
                            PalfrenierState st, long now) {
        EntityHorse a = support.resolvePoolHorse(world, data, st.breedPoolIdA);
        EntityHorse b = support.resolvePoolHorse(world, data, st.breedPoolIdB);
        if (a == null || b == null
                || !StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdA), now)
                || !StableWork.eligibleForBreeding(data.findByPoolId(st.breedPoolIdB), now)) {
            support.abandonChore(world, worker, st);
            return;
        }
        double mx = (a.posX + b.posX) * 0.5D, my = (a.posY + b.posY) * 0.5D, mz = (a.posZ + b.posZ) * 0.5D;
        if (st.targetSince >= 0L && now - st.targetSince > MOVE_GIVE_UP_TICKS
                && worker.getDistanceSq(mx, my, mz) > INTERACT_RANGE_SQ * 4.0D) {
            support.abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }
        if (!support.approachWorkSpot(worker, st, mx, my, mz, now)) return;
        st.phase = PalfrenierState.Phase.FEED_PAIR;
        st.phaseEndTick = 0L;
    }

    void feedPair(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                          StableZoneData data, PalfrenierState st, long now) {
        EntityHorse a = support.resolvePoolHorse(world, data, st.breedPoolIdA);
        EntityHorse b = support.resolvePoolHorse(world, data, st.breedPoolIdB);
        StableHorseEntry ea = data.findByPoolId(st.breedPoolIdA);
        StableHorseEntry eb = data.findByPoolId(st.breedPoolIdB);
        if (a == null || b == null || !StableWork.eligibleForBreeding(ea, now)
                || !StableWork.eligibleForBreeding(eb, now)) {
            support.abandonChore(world, worker, st);
            return;
        }
        worker.getLookHelper().setLookPositionWithEntity((st.breedFeedBeats % 2 == 0) ? a : b, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;

        // A cap may have filled between decide() and here (another pen bred, a horse tamed elsewhere).
        // Re-check once per beat, before the heart animation, rather than mime a breeding breed()
        // would just refuse. (breed() keeps its own final check as the last-tick backstop.)
        if (!StableWork.canBreedNow(world, zone, data, now)) {
            support.abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }

        RealChestSource chest = new RealChestSource(world, zone);
        Item feed = chest.available(Items.GOLDEN_CARROT) >= 2 ? Items.GOLDEN_CARROT
                : chest.available(Items.GOLDEN_APPLE) >= 2 ? Items.GOLDEN_APPLE : null;
        if (feed == null) {
            support.abandonChore(world, worker, st);
            st.nextDecideTick = now + TravellersConfig.STABLES.ambientDutyIntervalTicks;
            return;
        }
        worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(feed));
        worker.doInteractSwing(EnumHand.MAIN_HAND);
        support.spawnHearts(world, a);
        support.spawnHearts(world, b);
        st.breedFeedBeats++;
        st.phaseEndTick = now + 20L;

        if (st.breedFeedBeats >= BREED_FEED_BEATS) {
            support.clearHeldItem(worker);
            StableHorseEntry foal = StableWork.breed(world, zone, data, chest, now, ea, eb);
            if (foal != null) {
                // Let StableMaterializer place it (ring search from the pen centre, well inside the
                // loaded area) - teleporting it next to a parent risked dropping it near the zone
                // edge where vanilla's 32-block tick gate freezes it: no gravity, no AI, floating.
                StableMaterializer.materializeEntry(world, zone, foal);
                support.spawnHearts(world, a);
                support.spawnHearts(world, b);
            }
            PlayerZoneManager.get(world).markDirty();
            support.reset(st);
        }
    }
}
