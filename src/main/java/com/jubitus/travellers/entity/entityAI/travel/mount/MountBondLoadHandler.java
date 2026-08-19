package com.jubitus.travellers.entity.entityAI.travel.mount;

import net.minecraft.entity.passive.EntityHorse;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Re-asserts {@link TravellerMountSupport#applyBondInvariants} on every bonded horse entering the
 * world. The bond itself round-trips fine (it lives in {@code getEntityData()}, persisted under
 * vanilla's ForgeData tag), but neither invariant it implies does: the saddled flag is derived from
 * inventory contents and the horse's stripped AI is rebuilt by {@code initEntityAI()} in the
 * constructor. See {@code applyBondInvariants} for why an unsaddled bonded horse stops obeying its
 * rider entirely rather than merely looking wrong.
 *
 * <p>Hooking join (rather than fixing each load path) covers chunk load, world load and a restore
 * from captured NBT with one handler, and doubles as the migration path for horses bonded by an
 * older version of this mod, which have never had a real saddle item in slot 0.
 */
@Mod.EventBusSubscriber(modid = "travellers")
public final class MountBondLoadHandler {

    private MountBondLoadHandler() {
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        if (!(event.getEntity() instanceof EntityHorse)) return;

        EntityHorse horse = (EntityHorse) event.getEntity();
        if (!TravellerMountSupport.isBondedMount(horse)) return;

        TravellerMountSupport.applyBondInvariants(horse);
    }
}
