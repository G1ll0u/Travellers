package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.virtual.VirtualTravellerManager;
import com.jubitus.travellers.virtual.VirtualTravellerRecord;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.UUID;

/**
 * Every bonded horse entering the world passes here, for two reasons.
 *
 * <p><b>One live copy, always.</b> A bonded horse is either a live entity, data held by its rider
 * (loaded) or its record (virtual) - never two of those. A copy read back from a chunk file is checked
 * against its owner: a rider or record that already holds the horse as data, or that rebuilt a newer
 * copy (higher bond generation), makes this one a stale twin, removed on the spot. A virtual owner that
 * lost track of it gets it back as data. An owner that no longer claims it, or a bond ended while the
 * horse was unloaded ({@link MountReleaseLedger}), turns it into an ordinary loose horse. An owner that
 * is neither loaded nor virtual is simply not loaded yet (a chunk file) - the horse is left alone.
 *
 * <p><b>Bond invariants.</b> {@link TravellerMountSupport#applyBondInvariants} is re-asserted on join:
 * the saddle flag and the stripped AI don't survive a reload on their own.
 */
public final class MountBondLoadHandler {

    private MountBondLoadHandler() {
    }

    /** A bonded (non-pool) horse joining the world; dispatched by {@code HorseEvents}. */
    public static void onJoin(WorldServer world, EntityHorse horse, EntityJoinWorldEvent event) {
        switch (verdict(world, horse)) {
            case DROP:
                event.setCanceled(true);
                horse.setDead();
                log("removed a stale copy of a bonded horse", horse);
                return;
            case RELEASE:
                TravellerMountSupport.detachBondFromHorse(horse);
                log("released a bonded horse nobody claims any more", horse);
                return;
            case TO_RECORD:
                VirtualTravellerManager.get(world).captureOrphanedMount(HorseMarkers.getBondedOwner(horse),
                        com.jubitus.travellers.horse.HorseSnapshots.capture(horse));
                event.setCanceled(true);
                horse.setDead();
                log("handed a bonded horse back to its offscreen rider", horse);
                return;
            case KEEP:
            default:
                TravellerMountSupport.applyBondInvariants(horse);
        }
    }

    private enum Verdict {
        KEEP, DROP, RELEASE, TO_RECORD
    }

    private static Verdict verdict(WorldServer world, EntityHorse horse) {
        UUID horseId = horse.getUniqueID();
        Boolean ledger = MountReleaseLedger.get(world).consume(horseId);
        if (ledger != null) return ledger ? Verdict.DROP : Verdict.RELEASE;

        UUID owner = HorseMarkers.getBondedOwner(horse);
        int gen = HorseMarkers.getGeneration(horse);

        Entity o = world.getEntityFromUuid(owner);
        if (o instanceof EntityTraveller && !o.isDead) {
            EntityTraveller rider = (EntityTraveller) o;
            if (!horseId.equals(rider.getMountUUID())) return Verdict.RELEASE;
            if (gen < rider.mountState.getMountGeneration() || rider.mountState.getPendingMountNbt() != null) return Verdict.DROP;
            return Verdict.KEEP;
        }

        VirtualTravellerManager vtm = VirtualTravellerManager.get(world);
        VirtualTravellerRecord r = vtm.getRecord(owner);
        if (r != null) {
            NBTTagCompound e = r.entityNbt;
            UUID claimed = e != null && e.hasUniqueId("MountUUID") ? e.getUniqueId("MountUUID") : null;
            if (!horseId.equals(claimed)) return Verdict.RELEASE;
            int recordGen = e.getInteger("MountGen");
            if (r.mountNbt != null || gen < recordGen) return Verdict.DROP;
            return Verdict.TO_RECORD;
        }
        return Verdict.KEEP;
    }

    private static void log(String what, EntityHorse horse) {
        FMLLog.log.info("[Travellers/Mounts] {} ({} at {}, {})", what, horse.getUniqueID(),
                (int) horse.posX, (int) horse.posZ);
    }
}
