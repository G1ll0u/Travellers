package com.jubitus.travellers.zone.stable;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.zone.PlayerZoneManager;
import com.jubitus.travellers.zone.PlayerZoneRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.WorldServer;
import static com.jubitus.travellers.zone.stable.StableHandSupport.*;

/** Palfrenier taming phases of {@link StableHandTask}: approach, feed, mount and buck. */
final class StableTaming {

    private final StableHandSupport support;

    StableTaming(StableHandSupport support) {
        this.support = support;
    }

    void moveToHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                             StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(world, st);
        if (horse == null || horse.isTame() || poolEntry(data, horse) == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        if (support.giveUpUnreachable(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        if (!support.approachWorkSpot(worker, st, horse.posX, horse.posY, horse.posZ, now)) return;
        st.phase = PalfrenierState.Phase.FEED_HORSE;
        st.phaseEndTick = 0L;
    }

    void feedHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(world, st);
        StableHorseEntry entry = horse == null ? null : poolEntry(data, horse);
        if (horse == null || horse.isTame() || entry == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        if (worker.getDistanceSq(horse) > INTERACT_RANGE_SQ) {
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            return;
        }
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        if (now < st.phaseEndTick) return;

        RealChestSource chest = new RealChestSource(world, zone);
        // Keep the feed visibly in hand between beats while there's stock (it was fetched from the
        // chest), drop it once the supply runs out.
        Item feed = StableWork.tamingFeed(chest);
        if (feed != null) {
            worker.setHeldItem(EnumHand.MAIN_HAND, new ItemStack(feed));
        } else {
            support.clearHeldItem(worker);
        }
        worker.doInteractSwing(EnumHand.MAIN_HAND);

        StableWork.TameResult r = StableWork.tameStep(world, zone, entry, chest, 0);
        horse.heal(r.healAmount());
        StableHorseSync.pushEntryOntoHorse(entry, horse, zone.ownerUUID);
        PlayerZoneManager.get(world).markDirty();
        if (entry.tamed) {
            support.clearHeldItem(worker);
            support.reset(st);
            return;
        }

        st.phaseEndTick = now + TravellersConfig.STABLES.tamingAttemptIntervalTicks;
        // Between feeding beats, occasionally ride the horse and get thrown - the buck-off is the
        // visibly big step toward taming (bonus temper).
        if (worker.getRidingEntity() == null && worker.getRNG().nextInt(3) == 0) {
            st.phase = PalfrenierState.Phase.MOUNT_HORSE;
        }
    }

    void mountHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                            StableZoneData data, PalfrenierState st, long now) {
        EntityHorse horse = support.resolveTargetHorse(world, st);
        if (horse == null || horse.isTame() || poolEntry(data, horse) == null) {
            support.abandonChore(world, worker, st);
            return;
        }
        if (worker.getDistanceSq(horse) > INTERACT_RANGE_SQ) {
            st.phase = PalfrenierState.Phase.MOVE_TO_HORSE;
            return;
        }
        if (worker.getRidingEntity() != null || horse.isBeingRidden()) {
            // Already mounted, or another palfrenier got on first - skip the ride beat.
            st.phase = PalfrenierState.Phase.FEED_HORSE;
            st.phaseEndTick = now + TravellersConfig.STABLES.tamingAttemptIntervalTicks;
            return;
        }
        worker.getNavigator().clearPath();
        worker.getLookHelper().setLookPositionWithEntity(horse, 30.0F, 30.0F);
        support.clearHeldItem(worker);
        worker.startRiding(horse, false);
        st.phase = PalfrenierState.Phase.RIDE_HORSE;
        st.phaseEndTick = now + TravellersConfig.STABLES.tamingMountRideTicks;
    }

    void rideHorse(WorldServer world, EntityTravellerWorker worker, PlayerZoneRecord zone,
                           StableZoneData data, PalfrenierState st, long now) {
        Entity mount = worker.getRidingEntity();
        if (mount == null) {
            st.phase = PalfrenierState.Phase.DISMOUNT_PAUSE;
            st.phaseEndTick = now + 15L;
            return;
        }
        if (now < st.phaseEndTick) return;

        double dx = worker.posX - mount.posX;
        double dz = worker.posZ - mount.posZ;
        double len = Math.max(0.001D, Math.sqrt(dx * dx + dz * dz));
        worker.dismountRidingEntity();
        world.playSound(null, mount.posX, mount.posY, mount.posZ, SoundEvents.ENTITY_HORSE_ANGRY,
                SoundCategory.NEUTRAL, 1.0F, 1.0F);
        // A modest hop, not a catapult - it used to fling the palfrenier onto roofs / into walls.
        worker.motionX += (dx / len) * 0.15D;
        worker.motionZ += (dz / len) * 0.15D;
        worker.motionY += 0.28D;
        worker.velocityChanged = true;

        StableHorseEntry entry = (mount instanceof EntityHorse) ? poolEntry(data, (EntityHorse) mount) : null;
        if (entry != null && !entry.tamed) {
            RealChestSource chest = new RealChestSource(world, zone);
            StableWork.TameResult r = StableWork.tameStep(world, zone, entry, chest,
                    TravellersConfig.STABLES.tamingBuckBonusTemper);
            ((EntityHorse) mount).heal(r.healAmount());
            StableHorseSync.pushEntryOntoHorse(entry, (EntityHorse) mount, zone.ownerUUID);
            PlayerZoneManager.get(world).markDirty();
            if (entry.tamed) {
                support.reset(st);
                return;
            }
        }
        st.phase = PalfrenierState.Phase.DISMOUNT_PAUSE;
        st.phaseEndTick = now + 15L;
    }

    void dismountPause(PalfrenierState st, long now) {
        if (now < st.phaseEndTick) return;
        st.phase = PalfrenierState.Phase.FEED_HORSE;
        st.phaseEndTick = now;
    }
}
