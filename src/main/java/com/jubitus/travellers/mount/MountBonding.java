package com.jubitus.travellers.mount;

import com.jubitus.travellers.unit.UnitSpeed;
import com.jubitus.travellers.horse.HorseAI;
import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.camp.CampOccupant;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.virtual.MaterializePositionResolver;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

/** Creating a horse bond: where the horse appears, and the invariants (saddle, stripped AI, speed) every bonded horse carries. Reached through {@link TravellerMountSupport}. */
final class MountBonding {

    private MountBonding() {
    }

    /** A new physical copy of the rider's horse is being made: from now on it is the only valid one. */
    static void stampNewGeneration(EntityTraveller rider, EntityHorse horse) {
        int gen = rider.mountState.getMountGeneration() + 1;
        rider.mountState.setMountGeneration(gen);
        HorseMarkers.setGeneration(horse, gen);
    }

    /**
     * Spawns a fresh horse next to the traveller and bonds it, ignoring the config chance roll -
     * used by the debug command. Returns false if mounts are disabled or the traveller already has
     * one.
     */
    static boolean bondNewMount(EntityTraveller traveller) {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        if (traveller.getMountUUID() != null) return false;
        if (!(traveller.world instanceof WorldServer)) return false;

        EntityHorse horse = new EntityHorse(traveller.world);
        horse.onInitialSpawn(traveller.world.getDifficultyForLocation(new BlockPos(traveller)), null);
        horse.setGrowingAge(0);
        return finishBonding(traveller, horse);
    }

    /**
     * Bonds a SPECIFIC existing horse - read from NBT rather than spawned fresh - to a rider. The one
     * bonding moment a Stable's horse pool is allowed to trigger (at Get Equipment pickup): taming,
     * breeding and saddling in the stable never call this or touch {@link MountMode} at all. Same
     * tagging/invariants as {@link #bondNewMount}, from the other direction.
     */
    static boolean bondSpecificMount(EntityTraveller rider, NBTTagCompound horseNbt) {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        if (rider.getMountUUID() != null) return false;
        if (!(rider.world instanceof WorldServer)) return false;

        EntityHorse horse = new EntityHorse(rider.world);
        horse.readFromNBT(horseNbt);
        return finishBonding(rider, horse);
    }

    /**
     * Bonds a rider to an already-live horse standing in the world - unlike {@link #bondSpecificMount}
     * this never reads NBT or spawns a copy, it tags the exact entity handed in. For a recruited
     * worker claiming a loose ex-mount horse it finds nearby ({@code EntityAIClaimLooseMount}):
     * deliberately does not teleport or force-mount either side. It only sets the bond and leaves the
     * horse exactly where it stands; the caller has already walked the worker up to it, and the very
     * next {@link #tickMount} pass finds them already close together and mounts through the same
     * leash-then-remount path a returning bonded rider always uses (see the {@code MOUNTED} case of
     * {@code reconcileOnFoot}) - so this reads as "walks up, then climbs on", never a snap-to.
     */
    static boolean bondToLiveHorse(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        if (rider.getMountUUID() != null) return false;
        if (horse.isDead || HorseMarkers.isBondedMount(horse)) return false;
        if (HorseMarkers.isPoolHorse(horse)) return false;

        horse.setHorseTamed(true);
        HorseMarkers.setBondedOwner(horse, rider.getUniqueID());
        stampNewGeneration(rider, horse);
        applyMountedGroundSpeed(horse);
        applyBondInvariants(horse);
        HorseMarkers.clearOrphanTag(horse);

        rider.setMountUUID(horse.getUniqueID());
        rider.mountState.setMountModeRaw(MountMode.MOUNTED);
        return true;
    }

    /** Shared tail of {@link #bondNewMount}/{@link #bondSpecificMount}: place, tag, tame, spawn, and
     * settle the rider onto (or beside) the horse. */
    static boolean finishBonding(EntityTraveller rider, EntityHorse horse) {
        World world = rider.world;
        MountSpawnPos pos = resolveMountSpawnPos(rider, horse);
        horse.setLocationAndAngles(pos.x, pos.y, pos.z, rider.rotationYaw, 0.0F);
        horse.setHorseTamed(true);
        HorseMarkers.setBondedOwner(horse, rider.getUniqueID());
        stampNewGeneration(rider, horse);
        applyMountedGroundSpeed(horse);
        applyBondInvariants(horse);

        // Linked before the spawn: the join check (MountBondLoadHandler) must see the rider claim it.
        rider.setMountUUID(horse.getUniqueID());
        if (!world.spawnEntity(horse)) {
            rider.setMountUUID(null);
            return false;
        }
        if (pos.parked) {
            // Only the camping sub-case means "leave it standing, no leash" - a plain terrain offset
            // stays MOUNTED (see MountSpawnPos#camping), which reconcileOnFoot's default branch already
            // resolves correctly: leash the horse, walk it over, remount once close.
            rider.mountState.setMountModeRaw(pos.camping ? MountMode.HITCHED : MountMode.MOUNTED);
        } else {
            rider.mountState.setMountModeRaw(MountMode.MOUNTED);
            rider.startRiding(horse, true);
        }
        return true;
    }

    /** Where a mount should appear next to its rider, and whether it should stay standing there
     * rather than being auto-mounted. */
    static final class MountSpawnPos {
        final double x, y, z;
        /** True whenever the found spot isn't exactly where the rider stands (camping, or simply a
         * horse-sized clearance not being available on the rider's own tile) - the caller must not
         * instantly ride/teleport the rider onto a horse that appeared somewhere else. */
        final boolean parked;
        /** True only for the camping sub-case of {@link #parked}: the rider has an active shelter and
         * must not be yanked out of it. Every other "parked" case is just ordinary terrain not fitting
         * the horse's wider body, and should resolve like any other "horse standing a few blocks off"
         * situation (MOUNTED: leash, walk over, remount) rather than being left HITCHED forever. */
        final boolean camping;

        MountSpawnPos(double x, double y, double z, boolean parked, boolean camping) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parked = parked;
            this.camping = camping;
        }
    }

    /**
     * Normally a mount just appears exactly where its rider is - but only once that spot is checked
     * against the horse's own real (wider, taller) body via
     * {@link MaterializePositionResolver#findClearMountFootprint}, not just the rider's narrow
     * hitbox: a column that reads as a valid stand spot for a 0.6-wide rider is routinely NOT wide
     * enough for a 1.4-wide horse standing on it (a slope, a one-block niche), which is what let a
     * materializing horse appear embedded in a wall. A rider currently camping with an active shelter
     * footprint ({@link CampOccupant#getShelterBounds()}) additionally excludes that footprint from
     * the search - naively co-locating there is what caused a materializing horse to appear inside a
     * camp's tent walls.
     * <p>
     * The result is flagged "parked" whenever camping was involved, or the found spot ended up
     * meaningfully away from the rider, so the caller knows not to auto-mount the rider onto a horse
     * standing somewhere else (that would yank a sleeping/camping traveller out of its shelter, or
     * teleport-mount a rider onto a horse several blocks away the instant it appears). Only the
     * camping sub-case additionally means "leave it be, no leash" (see {@link MountSpawnPos#camping});
     * a plain terrain offset is resolved exactly like a horse waiting a few blocks off (MOUNTED: leash,
     * walk over, remount once close), not left stranded until it drifts far enough to trigger a recall.
     * <p>
     * Search radius and the "least-bad spot when nothing fits" fallback are
     * {@code MATERIALIZATION.mountFootprintSearchRadius} / {@code mountBestEffortLastResort}. Only
     * falls back to the rider's exact column when not even a reachable column exists (all nearby
     * chunks unloaded) - never blocks the mount from appearing.
     */
    static MountSpawnPos resolveMountSpawnPos(EntityTraveller rider, EntityHorse horse) {
        MountSpawnPos atRider = new MountSpawnPos(rider.posX, rider.posY, rider.posZ, false, false);
        if (!(rider.world instanceof WorldServer)) return atRider;
        WorldServer world = (WorldServer) rider.world;

        CampOccupant occ = CampsiteManager.get(world).findOccupant(rider.getUniqueID());
        AxisAlignedBB shelterBounds = occ != null ? occ.getShelterBounds() : null;
        boolean camping = shelterBounds != null;

        int radius = TravellersConfig.MATERIALIZATION.mountFootprintSearchRadius;
        boolean bestEffort = TravellersConfig.MATERIALIZATION.mountBestEffortLastResort;

        // A rider that is LEADING or has HITCHED its horse will not climb on where the horse appears,
        // so only the horse's own body has to fit there - not the full horse+rider silhouette - and
        // the natural spot is a step back along the rider's own trail (in single file), not its exact
        // column. Only a MOUNTED intent (a fresh bond, a materialize) needs the stacked silhouette so
        // the rider can ride away immediately.
        if (rider.mountState.getMountMode() != MountMode.MOUNTED && !camping) {
            Vec3d back = rider.mountState.leadTrailPointBehind(TravellersConfig.MOUNTS.ledFollowLeadGapBlocks);
            double sx = back != null ? back.x : rider.posX;
            double sy = back != null ? back.y : rider.posY;
            double sz = back != null ? back.z : rider.posZ;
            BlockPos hf = MaterializePositionResolver.findClearHorseFootprint(
                    world, horse, sx, sy, sz, null, radius, bestEffort);
            if (hf == null) return new MountSpawnPos(atRider.x, atRider.y, atRider.z, false, false);
            double hx = hf.getX() + 0.5D, hz = hf.getZ() + 0.5D;
            boolean hparked = Math.abs(hx - rider.posX) > 1.0D || Math.abs(hz - rider.posZ) > 1.0D;
            return new MountSpawnPos(hx, hf.getY(), hz, hparked, false);
        }

        BlockPos fit = MaterializePositionResolver.findClearMountFootprint(
                world, horse, rider, rider.posX, rider.posY, rider.posZ, shelterBounds,
                radius, bestEffort);
        if (fit == null) return new MountSpawnPos(atRider.x, atRider.y, atRider.z, camping, camping);

        double fx = fit.getX() + 0.5D, fz = fit.getZ() + 0.5D;
        boolean parked = camping
                || Math.abs(fx - rider.posX) > 1.0D || Math.abs(fz - rider.posZ) > 1.0D;
        return new MountSpawnPos(fx, fit.getY(), fz, parked, camping);
    }

    /** Where a horse tied at {@code post} reappears: the nearest spot its own body fits there. */
    static MountSpawnPos parkedSpawnPos(WorldServer world, EntityHorse horse, BlockPos post) {
        BlockPos hf = MaterializePositionResolver.findClearHorseFootprint(world, horse,
                post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, null,
                TravellersConfig.MATERIALIZATION.mountFootprintSearchRadius,
                TravellersConfig.MATERIALIZATION.mountBestEffortLastResort);
        if (hf == null) return new MountSpawnPos(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, true, false);
        return new MountSpawnPos(hf.getX() + 0.5D, hf.getY(), hf.getZ() + 0.5D, true, false);
    }

    /**
     * Rolls the per-type config chance for a group about to be virtual-spawned (entityClass discriminator
     * as used by {@code VirtualTravellerRecord}: "WORKER"/"TRAVELLER"/"RAIDER"). Callers must roll this
     * once per group and apply the result to every member's {@code VirtualTravellerRecord.mounted} -
     * never re-roll per member, or a group ends up with a mix of mounted/on-foot riders.
     */
    static boolean rollGroupMount(java.util.Random rand, String entityClass) {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        int chance = "WORKER".equals(entityClass) ? TravellersConfig.MOUNTS.workerMountChancePercent
                : "RAIDER".equals(entityClass) ? TravellersConfig.MOUNTS.raiderMountChancePercent
                : TravellersConfig.MOUNTS.travellerMountChancePercent;
        return chance > 0 && rand.nextInt(100) < chance;
    }

    /**
     * A vanilla horse rolls a random MOVEMENT_SPEED attribute (~0.1125-0.3375) independent of this
     * mod's own (much higher) traveller base speed - left at that roll, a bonded mount is often no
     * faster than walking, sometimes slower (see {@code AbstractHorse.travel()}: the horse's own
     * attribute and the rider's own AI-requested speed multiply together, so the horse side alone
     * governs whether mounting is actually an upgrade).
     *
     * <p>Only an initial value: from the first mounted tick onward {@code applyMountedSpeed} keeps it
     * mirrored to the rider's own AI move speed. Called at bond time and whenever a captured mount is
     * restored on materialize (see {@code VirtualTravellerManager.restoreOrBondMount}), and it is also
     * what a led or hitched horse runs on, where there is no rider speed to mirror.
     */
    static void applyMountedGroundSpeed(EntityHorse horse) {
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(UnitSpeed.ATTRIBUTE);
    }

    /**
     * The two things that make a bonded horse steerable, re-asserted at every point one can enter the
     * world: a fresh bond, a restore from captured NBT, and - via {@link MountBondLoadHandler} - a
     * plain chunk/world load. Neither survives an NBT round-trip on its own, which is exactly what
     * made a mounted traveller go haywire the moment the world was reloaded:
     *
     * <p><b>The saddle must be a real item, not just the flag.</b> {@code setHorseSaddled(boolean)}
     * only flips a DataParameter, and that flag is never written to NBT - it is derived state,
     * recomputed by {@code AbstractHorse.updateHorseSlots()} as "is inventory slot 0 non-empty", which
     * {@code readEntityFromNBT} calls on every load. A horse saddled by flag alone therefore comes
     * back <em>unsaddled</em> from disk, and {@code AbstractHorse.travel()} gates its entire ridden
     * branch on {@code isHorseSaddled()} - so the rider's {@code moveForward}/{@code rotationYaw} stop
     * being read at all and the horse falls through to driving itself. Going through {@code
     * replaceItemInInventory(400, ...)} (the public path to the protected {@code horseChest}) puts an
     * actual saddle in slot 0, which persists as the {@code SaddleItem} tag and re-derives the flag
     * correctly on load.
     *
     * <p><b>The horse's own vanilla AI must be stripped again too</b> ({@code EntityAIPanic},
     * {@code EntityAIWanderAvoidWater}, {@code EntityAIMate}, ...). {@code EntityList} rebuilds a
     * loaded horse with {@code new EntityHorse(world)}, whose constructor repopulates {@code tasks}
     * via {@code initEntityAI()} - NBT never carries tasks, so a bond-time strip cannot survive a
     * reload. On its own this would be mostly wasted parallel work (the ridden branch overwrites what
     * that AI sets), but combined with an unsaddled horse it becomes the <em>only</em> thing steering:
     * the horse wanders on its own navigator at this mod's boosted MOVEMENT_SPEED while the rider's AI
     * keeps computing paths that no longer reach the physics. Matches the same defensive choice
     * AncientWarfare 2's {@code NpcAIRideHorse} makes for its own mounted NPCs. A horse that stops being
     * a mount gets them back through {@code HorseAI.restoreVanilla}.
     *
     * <p><b>{@code EntityAISwimming} is the one exception that must survive the strip.</b> It is the
     * only thing keeping <em>any</em> land mob afloat (it does nothing but call
     * {@code jumpHelper.setJumping()} every tick while in liquid, which becomes the upward push in
     * {@code EntityLivingBase.travel}). Stripped along with the rest, a bonded horse simply sinks and
     * sits on the riverbed - and since vanilla dismounts a rider whose head goes underwater
     * ({@code EntityLivingBase.onEntityUpdate} -> {@code shouldDismountInWater}, always true for a
     * living vehicle), the rider was ejected at the water's edge every single time. It holds mutex bit
     * 4 (jump) and never touches navigation, so it cannot fight the rider's steering.
     */
    static void applyBondInvariants(EntityHorse horse) {
        boolean hasSwimming = false;
        for (EntityAITasks.EntityAITaskEntry entry : new java.util.ArrayList<>(horse.tasks.taskEntries)) {
            if (entry.action instanceof EntityAISwimming) {
                hasSwimming = true;
            } else {
                horse.tasks.taskEntries.remove(entry);
            }
        }
        // Also (re)sets canSwim on the navigator, which is what lets a led horse path across water.
        if (!hasSwimming) horse.tasks.addTask(0, new EntityAISwimming(horse));

        if (!horse.isHorseSaddled()) {
            horse.replaceItemInInventory(SADDLE_SLOT, new ItemStack(Items.SADDLE));
        }

        // The horse is the one that plans and walks a mounted route now, so its navigator has to be
        // set up like a traveller's rather than like a wandering farm animal. A vanilla horse's
        // FOLLOW_RANGE is what bounds PathNavigate's search radius, and at its default value a route
        // leg would simply return no path at all.
        horse.getEntityAttribute(SharedMonsterAttributes.FOLLOW_RANGE)
                .setBaseValue(TravellersConfig.ATTRIBUTES.travellerFollowRange);
        if (horse.getNavigator() instanceof net.minecraft.pathfinding.PathNavigateGround) {
            net.minecraft.pathfinding.PathNavigateGround nav =
                    (net.minecraft.pathfinding.PathNavigateGround) horse.getNavigator();
            nav.setCanSwim(true);
            nav.setEnterDoors(true);
            nav.setBreakDoors(false);
        }
    }

    /** Vanilla's {@code replaceItemInInventory} slot id for a horse's saddle slot (400 + slot 0). */
    static final int SADDLE_SLOT = 400;
}
