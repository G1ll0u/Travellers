package com.jubitus.travellers.entity.entityai.travel.mount;

import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.world.virtualization.VirtualTravellerManager;
import com.jubitus.travellers.world.virtualization.VirtualTravellerRecord;
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
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountBondLoadHandler {

    private MountBondLoadHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;
        if (!(event.getWorld() instanceof WorldServer)) return;

        EntityHorse horse = (EntityHorse) event.getEntity();
        if (!TravellerMountSupport.isBondedMount(horse)) return;
        if (com.jubitus.travellers.world.zone.stable.StableCoordinator.isPoolHorse(horse)) {
            // A pool horse is never a mount: drop the stale bond instead of releasing it loose.
            TravellerMountSupport.dropBondFromHorse(horse);
            return;
        }
        WorldServer world = (WorldServer) event.getWorld();

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
                NBTTagCompound nbt = new NBTTagCompound();
                horse.writeToNBT(nbt);
                VirtualTravellerManager.get(world).captureOrphanedMount(TravellerMountSupport.getBondedOwner(horse), nbt);
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

        UUID owner = TravellerMountSupport.getBondedOwner(horse);
        int gen = TravellerMountSupport.getHorseGeneration(horse);

        Entity o = world.getEntityFromUuid(owner);
        if (o instanceof EntityTraveller && !o.isDead) {
            EntityTraveller rider = (EntityTraveller) o;
            if (!horseId.equals(rider.getMountUUID())) return Verdict.RELEASE;
            if (gen < rider.getMountGeneration() || rider.getPendingMountNbt() != null) return Verdict.DROP;
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
