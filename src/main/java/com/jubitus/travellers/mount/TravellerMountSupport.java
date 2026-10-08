package com.jubitus.travellers.mount;

import com.jubitus.travellers.horse.HorseAI;
import com.jubitus.travellers.horse.HorseSnapshots;
import com.jubitus.travellers.horse.HorseMarkers;
import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import com.jubitus.travellers.unit.EntityTravellerRaider;
import com.jubitus.travellers.unit.EntityTravellerWorker;
import com.jubitus.travellers.camp.CampOccupant;
import com.jubitus.travellers.camp.CampsiteManager;
import com.jubitus.travellers.virtual.MaterializePositionResolver;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLLog;
import org.apache.logging.log4j.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Spawns, bonds and steers a traveller/raider/worker's horse companion. A bond is a permanent 1:1
 * pairing (see {@link EntityTraveller#getMountUUID()}), marked on the horse itself via
 * {@link Entity#getEntityData()} (persisted under vanilla's "ForgeData" tag) so the pairing and the
 * anti-theft guard both survive a save/reload without needing a custom horse subclass.
 *
 * <p><b>Steering needs no custom code, and no mixin.</b> While a traveller rides an
 * {@link EntityHorse}, vanilla already reads the rider's own {@code moveForward}/{@code moveStrafing}/
 * {@code rotationYaw} and applies them with the horse's own attributes ({@code AbstractHorse.travel()};
 * {@code canBeSteered()} accepts any {@code EntityLivingBase}, not just a player). Those are the exact
 * fields the rider's own move helper writes every tick, so the whole existing AI drives the horse
 * untouched. Same "physics stays vanilla, we only steer" principle as the elytra flight controller.
 *
 * <p>What that leaves is one real problem, and it is not a steering problem: vanilla sizes every part
 * of <em>navigation</em> from whichever entity asked for the path, which here is the 0.6-wide rider
 * rather than the 1.4-wide horse that actually collides. That is fixed once, in
 * {@code com.jubitus.travellers.pathing.PathBody}, and nothing in this class needs to know
 * about it. Handing the horse its own navigator instead was tried and is worse: it needs a mixin to
 * refuse {@code canBeSteered()}, it costs the yaw control that makes a horse face where it is going,
 * and the horse's fields cannot be replaced without reflection anyway.
 */
public final class TravellerMountSupport {

    private TravellerMountSupport() {
    }

    /** A new physical copy of the rider's horse is being made: from now on it is the only valid one. */
    private static void stampNewGeneration(EntityTraveller rider, EntityHorse horse) {
        int gen = rider.mountState.getMountGeneration() + 1;
        rider.mountState.setMountGeneration(gen);
        HorseMarkers.setGeneration(horse, gen);
    }

    /** A horse that must stop being a mount (a Stable pool horse): its loaded rider lets go of it and
     * every mount marker is removed. */
    public static void dropBondFromHorse(EntityHorse horse) {
        UUID owner = HorseMarkers.getBondedOwner(horse);
        if (owner != null && horse.world instanceof WorldServer) {
            Entity o = ((WorldServer) horse.world).getEntityFromUuid(owner);
            if (o instanceof EntityTraveller && horse.getUniqueID().equals(((EntityTraveller) o).getMountUUID())) {
                EntityTraveller rider = (EntityTraveller) o;
                if (rider.getRidingEntity() == horse) rider.dismountRidingEntity();
                setLeashed(horse, null);
                rider.setMountUUID(null);
                rider.mountState.setMountSnapshotNbt(null);
            }
        }
        HorseMarkers.clearMountMarkers(horse.getEntityData());
    }

    /**
     * Spawns a fresh horse next to the traveller and bonds it, ignoring the config chance roll -
     * used by the debug command. Returns false if mounts are disabled or the traveller already has
     * one.
     */
    public static boolean bondNewMount(EntityTraveller traveller) {
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
    public static boolean bondSpecificMount(EntityTraveller rider, NBTTagCompound horseNbt) {
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
    public static boolean bondToLiveHorse(EntityTraveller rider, EntityHorse horse) {
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
    private static boolean finishBonding(EntityTraveller rider, EntityHorse horse) {
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
    private static final class MountSpawnPos {
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
    private static MountSpawnPos resolveMountSpawnPos(EntityTraveller rider, EntityHorse horse) {
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
    private static MountSpawnPos parkedSpawnPos(WorldServer world, EntityHorse horse, BlockPos post) {
        BlockPos hf = MaterializePositionResolver.findClearHorseFootprint(world, horse,
                post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, null,
                TravellersConfig.MATERIALIZATION.mountFootprintSearchRadius,
                TravellersConfig.MATERIALIZATION.mountBestEffortLastResort);
        if (hf == null) return new MountSpawnPos(post.getX() + 0.5D, post.getY(), post.getZ() + 0.5D, true, false);
        return new MountSpawnPos(hf.getX() + 0.5D, hf.getY(), hf.getZ() + 0.5D, true, false);
    }

    private static int chanceFor(EntityTraveller traveller) {
        if (traveller instanceof EntityTravellerWorker) return TravellersConfig.MOUNTS.workerMountChancePercent;
        if (traveller instanceof EntityTravellerRaider) return TravellersConfig.MOUNTS.raiderMountChancePercent;
        return TravellersConfig.MOUNTS.travellerMountChancePercent;
    }

    /**
     * Rolls the per-type config chance for a group about to be virtual-spawned (entityClass discriminator
     * as used by {@code VirtualTravellerRecord}: "WORKER"/"TRAVELLER"/"RAIDER"). Callers must roll this
     * once per group and apply the result to every member's {@code VirtualTravellerRecord.mounted} -
     * never re-roll per member, or a group ends up with a mix of mounted/on-foot riders.
     */
    public static boolean rollGroupMount(java.util.Random rand, String entityClass) {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        int chance = "WORKER".equals(entityClass) ? TravellersConfig.MOUNTS.workerMountChancePercent
                : "RAIDER".equals(entityClass) ? TravellersConfig.MOUNTS.raiderMountChancePercent
                : TravellersConfig.MOUNTS.travellerMountChancePercent;
        return chance > 0 && rand.nextInt(100) < chance;
    }

    /**
     * The single per-tick reconciler for everything mount-related: it compares the world against the
     * rider's {@link MountMode} intent and closes the gap, then (when actually mounted) handles the
     * one steering detail vanilla doesn't do for a non-player rider - jumping a gap.
     *
     * <p>Deliberately the <em>only</em> place allowed to mount, dismount, leash, unleash or move a
     * bonded horse on its own initiative. Everything else (AI tasks, the camp, boat crossings, a
     * future player-facing setting) states an intent through {@link #setMountMode} and lets this
     * settle it, so a mount can never be left half-transitioned by whichever code path ran last.
     */
    public static void tickMount(EntityTraveller traveller) {
        if (traveller.getMountUUID() == null || !(traveller.world instanceof WorldServer)) {
            traveller.mountState.setWasRidingBondedMountLastTick(false);
            return;
        }

        // The horse is held as data (tied at a post whose chunk unloaded, or restored offscreen):
        // nothing to resolve, only the moment to put it back into the world.
        if (traveller.mountState.getPendingMountNbt() != null) {
            traveller.mountState.setWasRidingBondedMountLastTick(false);
            tickParkedMount(traveller);
            return;
        }

        EntityHorse horse = resolveMount(traveller);
        if (horse == null) {
            traveller.mountState.setWasRidingBondedMountLastTick(traveller.isMountedOnBondedMount());
            return;
        }

        // Captured before anything below can mount/dismount - the one true "start of tick" snapshot.
        // Any AI task later this same game tick (the stuck/rescue-teleport ladder in EntityAITravel is
        // the first consumer) can diff this against isMountedOnBondedMount() once tickMount() has run
        // to tell "did the mount state just flip", regardless of which of the several triggers below
        // (low health, swimming, a sidestep give-up) or in EntityAITravel (terrain dismount, a fresh
        // remount) caused it. Without this, a rescue teleport armed by "stuck while mounted" evidence
        // could fire the moment the rider steps off to fix exactly that stuckness, moving it again on
        // top of the dismount's own small step-aside - reading as a quick flash to another spot and
        // straight back once the two settle.
        traveller.mountState.setWasRidingBondedMountLastTick(traveller.isMountedOnBondedMount());

        refreshMountSnapshot(traveller, horse);

        // Self-heal the AI strip. A log named the culprit: another mod adds an EntityAIAvoidEntity to
        // horses on EntityJoinWorldEvent, after applyBondInvariants has already run, and that is a
        // pathing task - it would give the horse its own navigation to fight the steering.
        // The size test is O(1), so this costs nothing on the overwhelming majority of ticks and
        // survives whatever any mod adds, whenever it adds it.
        if (horse.tasks.taskEntries.size() != 1) applyBondInvariants(horse);

        // A badly wounded mount gets led rather than ridden, in or out of combat - checked before
        // nowMounted below so a rider currently in the saddle actually dismounts this same tick, not
        // next time reconcileOnFoot happens to run. Armed with markTerrainFallbackLed immediately so
        // the LED case's own "orphaned LED" self-heal (below) doesn't read this as untracked and
        // instantly flip it back to MOUNTED with the horse standing right there.
        if (traveller.mountState.getMountMode() != MountMode.LED && traveller.mountState.getMountMode() != MountMode.HITCHED
                && isMountTooHurtToRide(horse)) {
            com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(traveller, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.LOW_HEALTH);
            setMountMode(traveller, MountMode.LED);
            markTerrainFallbackLed(traveller);
            notifyMountLedFallback(traveller, com.jubitus.travellers.notify.NotificationDefs.MOUNT_LED_LOW_HEALTH);
        }

        // A traveller/raider/non-hired worker steps off as soon as it enters a village or lone
        // building's own grounds - narrow streets, gates and buildings are what the mounted
        // pathfinding/gate-routing traps above are made of, so simply not being mounted there avoids
        // the whole class of problem rather than solving each shape of it. A hired worker actively
        // escorting its owner is excluded: it needs to keep pace with a player who may itself be
        // riding through. Checked before nowMounted below so a currently-MOUNTED rider entering a
        // village dismounts this same tick, same as the low-health check above.
        // No player toast here - see notifyMountLedFallback's own doc: entering a village is a
        // routine, expected dismount (like a boat crossing), not a "the horse gave up" failure.
        // Riding into the village it is about to visit: tie the horse up right here, at the edge,
        // and go on foot. Checked first so it wins over the lead-through-the-village dismount below.
        if (traveller.mountState.getMountMode() != MountMode.HITCHED && traveller.ticksExisted % 10 == 0) {
            tryHitchAtVillageEdge(traveller, horse);
        }

        if (traveller.mountState.getMountMode() != MountMode.LED && traveller.mountState.getMountMode() != MountMode.HITCHED
                && isNearVillageRequiringLed(traveller)) {
            com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(traveller, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.VILLAGE);
            setMountMode(traveller, MountMode.LED);
            markTerrainFallbackLed(traveller);
        }

        boolean nowMounted = traveller.isMountedOnBondedMount();

        if (!nowMounted) {
            reconcileOnFoot(traveller, horse);
            return;
        }

        // Vanilla only ejects a rider once its OWN head goes underwater
        // (EntityLivingBase.onEntityUpdate -> shouldDismountInWater), which in practice means staying
        // in the saddle of a horse that is already swimming - reported as "he didn't get off until the
        // horse went under". Step off as soon as the horse itself is swimming instead. No mode change
        // is needed or wanted: the MOUNTED-on-foot branch of reconcileOnFoot already leashes the horse
        // and tows it, and canRemountHere puts the rider back once both are out of the water and the
        // horse is on solid ground.
        if (isSwimming(horse)) {
            traveller.dismountRidingEntity();
            setLeashed(horse, traveller);
            return;
        }

        traveller.mountState.setRemountTicks(0);
        setLeashed(horse, null);

        // The rider steers (AbstractHorse.travel() copies its rotationYaw and reads its moveForward),
        // and its own navigator plans the route sized for the horse - see PathBody. Nothing here has
        // to touch either. The rider's model is simply turned to face the way it is steering, since
        // a passenger generates no motion of its own for vanilla to derive a body angle from.
        traveller.renderYawOffset = traveller.rotationYaw;
        traveller.prevRenderYawOffset = traveller.rotationYaw;

        applyMountedSpeed(traveller, horse);
        releaseJumpYawOnLanding(traveller, horse);
        tickMountedGapJump(traveller, horse);
        tickMountedJump(traveller, horse);
        trySidestepIfWedged(traveller, horse);
        logHorseDiagnostic(traveller, horse);
    }

    /**
     * Nudges a jammed horse sideways when the jump system has nothing to offer - a tree trunk, a
     * low branch, anything that isn't a climbable ledge in front of it. {@link #tickMountedJump}
     * only ever tries going up; a horse pinned by something that was never a "step" in the first
     * place (traces showed this against tree canopies and even against a node that was <em>lower</em>
     * than the horse, where a jump makes no sense at all) just sat there rejecting jump attempts
     * every tick until the multi-second stuck-recovery ladder eventually intervened.
     *
     * <p>Deliberately gated on several consecutive wedged ticks ({@code
     * MOUNTS.mountedSidestepTriggerTicks}), not the first one - a single-tick
     * brush against something the horse was already clearing on its own should not trigger a shove.
     * The counter lives on the rider (see {@code EntityTraveller.mountWedgedTicks}), transient like
     * the mount jump yaw next to it - never persisted, reset the instant the horse is not wedged.
     *
     * <p><b>A repeated sidestep is not a fix, it is evidence the spot itself is bad.</b> A trace
     * caught a horse under dense canopy sidestepping the same direction every ~20 ticks for 400+
     * ticks straight, each time curving back into the exact same wedge - open ground to the side was
     * never the problem, so shoving it sideways over and over just re-enacted the same short loop.
     * {@code EntityTraveller.sidestepStreak} counts sidesteps that fire close together in time
     * ({@code MOUNTS.mountedSidestepGiveUpWindowTicks}); past {@code
     * MOUNTS.mountedSidestepGiveUpAfter} of them the horse stops trying to ride through this spot at
     * all and steps down to lead it on foot instead ({@link #setMountMode}) - the same LED mode every
     * other "the horse cannot path this" case already uses, with its own existing remount-on-progress
     * logic to get back in the saddle once clear of whatever this was.
     */
    private static void trySidestepIfWedged(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.mountedSidestepEnabled) return;

        if (!isMountWedged(rider)) {
            rider.mountState.setMountWedgedTicks(0);
            rider.mountState.setMountWedgeAnchorYaw(Float.NaN);
            return;
        }

        int wedgedTicks = rider.mountState.getMountWedgedTicks() + 1;
        rider.mountState.setMountWedgedTicks(wedgedTicks);
        // Latch the direction once, at the start of this wedge episode, and hold it - see the field
        // doc on EntityTraveller.mountWedgeAnchorYaw for why re-sampling every tick was the bug.
        if (Float.isNaN(rider.mountState.getMountWedgeAnchorYaw())) {
            rider.mountState.setMountWedgeAnchorYaw(horse.rotationYaw);
        }
        if (wedgedTicks < TravellersConfig.MOUNTS.mountedSidestepTriggerTicks) return;

        int ticksSinceLast = rider.ticksExisted - rider.mountState.getLastSidestepTick();
        int streak = (rider.mountState.getLastSidestepTick() > 0
                && ticksSinceLast <= TravellersConfig.MOUNTS.mountedSidestepGiveUpWindowTicks)
                ? rider.mountState.getSidestepStreak() + 1 : 1;

        if (streak >= TravellersConfig.MOUNTS.mountedSidestepGiveUpAfter) {
            rider.mountState.setSidestepStreak(0);
            rider.mountState.setMountWedgedTicks(0);
            rider.mountState.setMountWedgeAnchorYaw(Float.NaN);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                    "giving up on sidestepping this spot (" + streak + " tries) - leading horse on foot instead, "
                            + obstacleForTrace(rider, horse));
            com.jubitus.travellers.debug.profile.MovementProfiler.ledStarted(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.SIDESTEP_GAVE_UP);
            setMountMode(rider, MountMode.LED);
            markTerrainFallbackLed(rider);
            notifyMountLedFallback(rider, com.jubitus.travellers.notify.NotificationDefs.MOUNT_LED_SIDESTEP_GAVE_UP);
            return;
        }
        rider.mountState.setSidestepStreak(streak);
        rider.mountState.setLastSidestepTick(rider.ticksExisted);

        // Same latched yaw the trigger above captured, not a fresh sample - see the field doc on
        // EntityTraveller.mountWedgeAnchorYaw.
        double yaw = rider.mountState.getMountWedgeAnchorYaw() * 0.017453292F;
        double fwdX = -MathHelper.sin((float) yaw);
        double fwdZ = MathHelper.cos((float) yaw);
        // Perpendicular to the current heading - the two candidate sidestep directions.
        double rightX = fwdZ;
        double rightZ = -fwdX;

        double half = horse.width / 2.0D;
        double probe = half + 1.0D;
        // Alternate which side is tried first between attempts. A fixed "right first" preference is
        // exactly what produced the repeating loop above: the same side being clear every time just
        // re-walks the same little detour back into the same wedge.
        boolean tryRightFirst = (streak % 2) == 1;
        boolean primaryClear = lateralSpotClear(horse, rightX * probe * (tryRightFirst ? 1 : -1),
                rightZ * probe * (tryRightFirst ? 1 : -1));
        boolean secondaryClear = !primaryClear && lateralSpotClear(horse,
                rightX * probe * (tryRightFirst ? -1 : 1), rightZ * probe * (tryRightFirst ? -1 : 1));
        if (!primaryClear && !secondaryClear) return; // both sides blocked too - nothing a shove can fix here

        boolean wentRight = primaryClear == tryRightFirst;
        double sign = wentRight ? 1.0D : -1.0D;
        double impulse = TravellersConfig.MOUNTS.mountedSidestepImpulse;
        horse.motionX += rightX * sign * impulse;
        horse.motionZ += rightZ * sign * impulse;
        // A small forward component too, so the nudge actually clears the obstacle's corner instead
        // of just sliding along its face.
        horse.motionX += fwdX * impulse * 0.3D;
        horse.motionZ += fwdZ * impulse * 0.3D;

        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.MOUNT_SIDESTEP);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "sidestep %s (wedged %d ticks, streak %d) %s",
                        wentRight ? "right" : "left", wedgedTicks, streak, obstacleForTrace(rider, horse)));

        // Give the nudge a few ticks to actually move the horse before judging it wedged again -
        // isMountWedged would otherwise very likely still read true the very next tick (motion takes
        // a tick to show up in position), retriggering immediately and stacking impulses.
        rider.mountState.setMountWedgedTicks(TravellersConfig.MOUNTS.mountedSidestepTriggerTicks - 4);
    }

    /** Whether the horse's own body (not the rider's) would fit standing at its current height,
     * offset sideways by ({@code dx}, {@code dz}) - the one physical question a sidestep needs
     * answered, real continuous volume rather than a per-column approximation. */
    private static boolean lateralSpotClear(EntityHorse horse, double dx, double dz) {
        AxisAlignedBB box = horse.getEntityBoundingBox().offset(dx, 0.0D, dz);
        return !horse.world.collidesWithAnyBlock(box);
    }

    /**
     * Jumps a step the horse cannot walk up, when it is already pressed against it.
     *
     * <p>Jumping is the one thing vanilla never does for a non-player rider:
     * {@code AbstractHorse.setJumpPower(int)} is {@code @SideOnly(CLIENT)} - it exists to turn the
     * client's jump-bar input into a packet - so server AI must write the same impulse
     * {@code travel()} would, directly. The height comes from this individual horse's own
     * JUMP_STRENGTH attribute, so a weak horse never attempts what a strong one can clear.
     *
     * <p><b>Deliberately reactive, not planned.</b> It fires only once the horse is actually in
     * contact with the obstacle ({@code collidedHorizontally}) and the steering is asking for a
     * height it cannot step to. Teaching the pathfinder to route over walls instead would mean
     * committing to jumps before knowing whether the landing works out, and a route that assumes a
     * jump the horse then fails strands it somewhere worse than where it started. Here the worst case
     * is a jump that does not clear, after which nothing has changed and the ordinary stuck
     * escalation continues. This is the case the traces kept showing: a horse driving into a 2-block
     * face and stopping there, when the horse under it could have hopped it.
     *
     * <p>No cooldown is needed. Vanilla clears {@code isHorseJumping()} on landing (in the steered
     * branch of {@code travel()}), so at most one jump is in flight at a time.
     */
    private static void releaseJumpYawOnLanding(EntityTraveller rider, EntityHorse horse) {
        // Back on the ground: hand steering back (see TravellerMoveHelper).
        // `horse.onGround` alone is not enough - a trace caught it reading true for one tick while
        // the horse was still clearly climbing (posY rising for several more ticks after), releasing
        // the freeze mid-ascent and letting the capped-turn steering drag the whole arc into a circle
        // (the orbit slowForTurn's own doc describes) before it ever actually landed. Require the
        // vertical motion to have stopped rising too, so a stale/spurious onGround reading mid-jump
        // can't end the freeze early.
        if (horse.onGround && horse.motionY <= 0.0D && !Float.isNaN(rider.mountState.getMountJumpYaw())) {
            rider.mountState.setMountJumpYaw(Float.NaN);
        }
    }

    private static void tickMountedJump(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.mountedJumpEnabled) return;
        if (!TravellersConfig.MOUNTS.mountedReactiveJumpEnabled) return;
        if (!horse.onGround || horse.isHorseJumping()) return;
        if (!horse.collidedHorizontally || rider.moveForward <= 0.02F) return;

        double feet = horse.getEntityBoundingBox().minY;
        com.jubitus.travellers.pathing.MountTerrainProfile.ClimbTarget target = obstacleTopAhead(rider, horse, feet);
        if (target == null) {
            traceJumpRejection(rider, "no climbable column within lookahead (open ground ahead, or the "
                    + "nearest solid thing is out of reach)");
            return;
        }

        double rise = target.landingY - feet;
        if (rise <= horse.stepHeight + 1.0E-3D) return;

        // The obstacle scan only ever validates the LANDING column - it says nothing about the space
        // directly above where the horse is standing right now, which is exactly what the jump's own
        // ascent has to pass through. A horse standing under low branches has room to stand but not to
        // rise: a trace caught this firing the same doomed jump every few ticks forever, bouncing
        // straight up into the canopy and falling back onto the exact same spot, net progress zero,
        // because nothing here asked "is there room to go UP from here" separately from "is there room
        // to land over there". Checked with the horse's own real body, not a single point.
        AxisAlignedBB current = horse.getEntityBoundingBox();
        AxisAlignedBB liftoffPath = new AxisAlignedBB(current.minX, current.minY, current.minZ,
                current.maxX, current.minY + rise + JUMP_CLEARANCE_MARGIN, current.maxZ);
        if (horse.world.collidesWithAnyBlock(liftoffPath)) {
            traceJumpRejection(rider, "canopy or overhang directly overhead blocks the jump's own ascent, "
                    + "even though the landing beyond it is clear");
            return;
        }

        // Sized from the obstacle just cleared, not the horse's full strength - a player clearing a
        // 2-block step doesn't jump the same as one clearing a 4-block one, and neither should this.
        // requiredJumpVelocity runs the same discrete physics jumpHeightOf itself relies on, so the
        // planned rise and the executed impulse can never disagree about what this horse can do.
        double impulse = requiredJumpVelocity(rise + JUMP_CLEARANCE_MARGIN, horse.getHorseJumpStrength());
        horse.motionY = impulse;
        // The same forward kick vanilla gives a jumping horse, so it lands on top of the obstacle
        // rather than sliding back down its face.
        float sin = MathHelper.sin(horse.rotationYaw * 0.017453292F);
        float cos = MathHelper.cos(horse.rotationYaw * 0.017453292F);
        horse.motionX += -0.4F * sin;
        horse.motionZ += 0.4F * cos;
        horse.setHorseJumping(true);
        horse.isAirBorne = true;
        rider.mountState.setMountJumpYaw(horse.rotationYaw);

        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT,
                        "jump rise=%.2f impulse=%.2f capability=%.2f strength=%.2f columnsAhead=%d",
                        rise, impulse, jumpHeightOf(horse), horse.getHorseJumpStrength(), target.columnsAhead));
    }

    /**
     * Jumps a hole or stream the planner routed across ({@code TravellerNodeProcessor.addMountGapCandidates}):
     * fires once the column under the horse's leading edge has no dry ground and the steering target
     * lies beyond dry ground within {@code MOUNTS.mountedMaxGapWidth}. Cardinal only, like the planner,
     * so the scanned line is the flown line. The impulse is sized from vanilla's airborne physics
     * (gravity, 0.91 horizontal drag) to land the whole body on the far rim.
     */
    private static void tickMountedGapJump(EntityTraveller rider, EntityHorse horse) {
        int maxGap = TravellersConfig.MOUNTS.mountedMaxGapWidth;
        if (!TravellersConfig.MOUNTS.mountedJumpEnabled || maxGap <= 0) return;
        if (!horse.onGround || horse.isHorseJumping() || horse.isInWater()) return;
        if (rider.moveForward <= 0.02F || !rider.getMoveHelper().isUpdating()) return;

        double toX = rider.getMoveHelper().getX() - horse.posX;
        double toZ = rider.getMoveHelper().getZ() - horse.posZ;
        boolean alongX = Math.abs(toX) >= Math.abs(toZ);
        double toAlong = alongX ? toX : toZ;
        if (Math.abs(alongX ? toZ : toX) > GAP_TARGET_MAX_SIDE_OFFSET) return;
        int sg = toAlong > 0 ? 1 : -1;
        double along = alongX ? horse.posX : horse.posZ;
        double side = alongX ? horse.posZ : horse.posX;
        double half = horse.width / 2.0D;
        // A planned jump steers at a node beyond the gap; ordinary walking steers one cell ahead.
        if (Math.abs(toAlong) < half + 1.5D) return;
        double feet = horse.getEntityBoundingBox().minY;

        // One column read per tick on ordinary ground: the one under the middle of the chest.
        int first = MathHelper.floor(along + sg * (half + 0.3D));
        double fx = alongX ? first + 0.5D : side;
        double fz = alongX ? side : first + 0.5D;
        if (!Double.isNaN(com.jubitus.travellers.pathing.MountTerrainProfile.groundYNear(
                horse.world, fx, fz, feet, horse.stepHeight, 1.0D, 2))) {
            return;
        }

        for (int g = 1; g <= maxGap; g++) {
            int col = first + sg * g;
            double near = rimSurface(horse.world, alongX, col, side, half, feet, 1.0D);
            if (Double.isNaN(near)) continue;
            double far = rimSurface(horse.world, alongX, col + sg, side, half, feet, 1.0D);
            if (Double.isNaN(far)) return;
            double landY = Math.max(near, far);
            double landAlong = (sg > 0 ? col : col + 1) + sg * (half + 0.2D);
            double distance = Math.abs(landAlong - along);
            if (Math.abs(toAlong) < distance - half) return; // steering target is not across the gap

            AxisAlignedBB box = horse.getEntityBoundingBox();
            double floor = Math.max(feet, landY) + 0.3D;
            double top = floor + com.jubitus.travellers.pathing.PathBody.clearanceHeight(rider);
            double lo = Math.min(along, landAlong) - half;
            double hi = Math.max(along, landAlong) + half;
            AxisAlignedBB flight = alongX
                    ? new AxisAlignedBB(lo, floor, box.minZ, hi, top, box.maxZ)
                    : new AxisAlignedBB(box.minX, floor, lo, box.maxX, top, hi);
            if (horse.world.collidesWithAnyBlock(flight)) {
                traceJumpRejection(rider, "gap ahead but the flight path is blocked");
                return;
            }

            double strength = horse.getHorseJumpStrength();
            double drop = landY - feet;
            double vy = requiredJumpVelocity(Math.max(0.0D, drop) + GAP_JUMP_APEX, strength);
            double vh = gapJumpHorizontalSpeed(distance, airTicks(vy, drop));
            if (vh > GAP_JUMP_MAX_SPEED) {
                vy = strength;
                vh = gapJumpHorizontalSpeed(distance, airTicks(vy, drop));
                if (vh > GAP_JUMP_MAX_SPEED) {
                    traceJumpRejection(rider, "gap too wide for this horse");
                    return;
                }
            }

            horse.motionY = vy;
            horse.motionX = alongX ? sg * vh : 0.0D;
            horse.motionZ = alongX ? 0.0D : sg * vh;
            horse.setHorseJumping(true);
            horse.isAirBorne = true;
            float yaw = alongX ? (sg > 0 ? -90.0F : 90.0F) : (sg > 0 ? 0.0F : 180.0F);
            horse.rotationYaw = yaw;
            rider.rotationYaw = yaw;
            rider.mountState.setMountJumpYaw(yaw);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                    String.format(java.util.Locale.ROOT, "gap jump width=%d dist=%.2f drop=%.2f vy=%.2f vh=%.2f",
                            g, distance, drop, vy, vh));
            return;
        }
    }

    /**
     * The dry surface under one cross-section of the horse ({@code col} along the axis, its width
     * across), or NaN when any part of it has nothing to stand on between one block below
     * {@code feet} and {@code rise} above.
     */
    private static double rimSurface(World world, boolean alongX, int col, double side, double half,
                                     double feet, double rise) {
        double best = Double.NaN;
        for (double s = -half + 0.05D; s <= half - 0.05D + 1.0E-6D; s += half - 0.05D) {
            double x = alongX ? col + 0.5D : side + s;
            double z = alongX ? side + s : col + 0.5D;
            double y = com.jubitus.travellers.pathing.MountTerrainProfile.groundYNear(world, x, z, feet, rise, 1.0D, 2);
            if (Double.isNaN(y)) return Double.NaN;
            if (Double.isNaN(best) || y > best) best = y;
        }
        return best;
    }

    /** Ticks airborne for a launch at {@code vy} landing {@code drop} blocks above takeoff (negative = below). */
    private static int airTicks(double vy, double drop) {
        double y = 0.0D;
        for (int t = 1; t <= 60; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
            if (vy < 0.0D && y <= drop) return t;
        }
        return 60;
    }

    /** Launch speed covering {@code distance} in {@code ticks} under the 0.91 airborne drag. */
    private static double gapJumpHorizontalSpeed(double distance, int ticks) {
        return distance * (1.0D - 0.91D) / (1.0D - Math.pow(0.91D, ticks));
    }

    /** How far beside the jump axis the steering target may sit. */
    private static final double GAP_TARGET_MAX_SIDE_OFFSET = 1.5D;
    /** Arc height above the higher rim - enough to clear the far edge with the whole body. */
    private static final double GAP_JUMP_APEX = 0.8D;
    /** Fastest launch allowed, blocks/tick - a full-strength horse at a gallop. */
    private static final double GAP_JUMP_MAX_SPEED = 0.9D;

    /**
     * Records why a horse pressed against something did NOT jump - the reactive side of the mount
     * jump had no visibility into its own rejections until this existed, which left "why didn't it
     * jump" to guesswork whenever a trace showed zero jump events across a stall. Cheap to call
     * unconditionally: {@code TravellerTracer.isTraced} is a UUID set lookup, and this only runs at
     * all once the horse is already collided and trying to move (see the call sites above).
     */
    private static void traceJumpRejection(EntityTraveller rider, String reason) {
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(rider.getUniqueID())) return;
        Entity horse = rider.getRidingEntity();
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount", "jump rejected: " + reason
                + (horse != null ? " " + obstacleForTrace(rider, horse) : ""));
    }

    /** What the horse is pressed against, for a trace line; empty for an untraced rider. */
    private static String obstacleForTrace(EntityTraveller rider, Entity horse) {
        if (!com.jubitus.travellers.debug.trace.TravellerTracer.isTraced(rider.getUniqueID())) return "";
        float yaw = Float.isNaN(rider.mountState.getMountWedgeAnchorYaw())
                ? horse.rotationYaw : rider.mountState.getMountWedgeAnchorYaw();
        return com.jubitus.travellers.debug.trace.MountObstacle.probe(horse, yaw).describe();
    }

    /**
     * The nearest column ahead of the horse a jump could land on, or {@code null} if none qualifies.
     *
     * <p>Measured from the obstacle rather than from the steering target, and that distinction is the
     * whole method. The steering target takes its height from a path node that can be several blocks
     * further along, so on a climb it reads higher the more the route continues to rise - a horse
     * caught against a 2-block step was refused its jump because the node beyond that step sat 3
     * blocks up, one twentieth of a block over its capability. What decides whether a jump helps is
     * the thing in the way, and nothing else.
     *
     * <p>Delegates to {@link com.jubitus.travellers.pathing.MountTerrainProfile}, walking up to
     * {@code MOUNTS.mountedJumpLookaheadColumns} steps ahead rather than only the one column directly
     * in front - a single column reads an overhang or a multi-cell-deep stepped face as "nothing
     * here", even while the horse is visibly jammed against it. Sampled across the horse's width at
     * each step, not just down its centre line, because the case that matters is precisely the
     * asymmetric one: {@code Entity.move} clips each axis independently and never pushes a body
     * sideways, so a single cell that is one block higher under one shoulder stops the whole horse
     * while its centre line reads clear.
     *
     * <p>Scans along the wedge-episode's latched yaw ({@code EntityTraveller.mountWedgeAnchorYaw})
     * when one is active, rather than the horse's live {@code rotationYaw} - the live yaw swings with
     * the bearing to the steering target as the horse's own tiny position jitter shifts it, and a scan
     * direction that changes tick to tick can miss the same obstacle it just found. Falls back to the
     * live yaw when there is no active episode (the ordinary case: a first-contact jump attempt).
     */
    private static com.jubitus.travellers.pathing.MountTerrainProfile.ClimbTarget obstacleTopAhead(
            EntityTraveller rider, EntityHorse horse, double feet) {
        float yawDeg = Float.isNaN(rider.mountState.getMountWedgeAnchorYaw()) ? horse.rotationYaw : rider.mountState.getMountWedgeAnchorYaw();
        double yaw = yawDeg * 0.017453292F;
        double dirX = -MathHelper.sin((float) yaw);
        double dirZ = MathHelper.cos((float) yaw);
        double half = horse.width / 2.0D;
        double reach = half + 0.35D;

        return com.jubitus.travellers.pathing.MountTerrainProfile.scanClimbColumns(
                horse.world, horse.posX, horse.posZ, dirX, dirZ, half, reach, feet, jumpHeightOf(horse),
                JUMP_CLEARANCE_MARGIN, TravellersConfig.MOUNTS.mountedJumpLookaheadColumns, 2);
    }

    /**
     * How high this specific horse actually gets from a standing jump, by running vanilla's own
     * vertical physics (gravity 0.08 then 0.98 drag, every tick) from its JUMP_STRENGTH until the
     * arc turns over. A vanilla roll spans roughly 1.6 to 5 blocks, so "can a horse jump two blocks"
     * has no single answer - it has to be asked of the individual.
     */
    /** Height a jump must beat the obstacle by, so the arc carries over it instead of grazing it. */
    private static final double JUMP_CLEARANCE_MARGIN = 0.2D;

    public static double jumpHeightOf(EntityHorse horse) {
        return jumpHeightForVelocity(horse.getHorseJumpStrength());
    }

    /** The discrete physics {@link #jumpHeightOf} runs, factored out so {@link #requiredJumpVelocity}
     * can probe it at velocities other than the horse's own full strength. */
    private static double jumpHeightForVelocity(double v0) {
        double vy = v0;
        double y = 0.0D;
        for (int t = 0; t < 60 && vy > 0.0D; t++) {
            y += vy;
            vy = (vy - 0.08D) * 0.98D;
        }
        return y;
    }

    /**
     * The smallest initial vertical velocity that clears {@code targetRise} blocks under the same
     * discrete physics {@link #jumpHeightOf} uses - found by bisecting on {@link
     * #jumpHeightForVelocity}, since that recurrence has no clean closed-form inverse.
     *
     * <p>This is the whole fix for a jump over a 2-block step looking identical to one over a
     * 4-block step: every jump used to launch at {@code getHorseJumpStrength()} regardless of what it
     * was actually clearing, which looks like a player mashing the jump key at full charge for a
     * kerb. A real rider, and this method, size the jump to the obstacle.
     *
     * <p>Capped at {@code maxStrength} - a target beyond what this horse can ever reach still gets
     * its best (and only) attempt rather than nothing. Callers already refuse the jump entirely
     * before this is reached when the obstacle is out of reach (see {@code
     * MountTerrainProfile.scanClimbColumns}'s own reach limit), so the cap is a safety floor, not the
     * normal path.
     */
    public static double requiredJumpVelocity(double targetRise, double maxStrength) {
        if (targetRise <= 0.0D) return 0.0D;
        double lo = 0.0D;
        double hi = maxStrength;
        for (int i = 0; i < 24; i++) {
            double mid = (lo + hi) / 2.0D;
            if (jumpHeightForVelocity(mid) < targetRise) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.min(hi, maxStrength);
    }

    /**
     * This rider's bonded horse if it is loaded right now, else {@code null}. A plain UUID lookup with
     * no grace period or snapshot rebuild - callers that only want to read the horse's live state
     * (distance, position) when it happens to be present, not to reconcile the bond.
     */
    @Nullable
    public static EntityHorse getBondedHorse(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return null;
        Entity found = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
        return (found instanceof EntityHorse && !found.isDead) ? (EntityHorse) found : null;
    }

    /** Consecutive ticks the bonded horse may be absent before it is considered genuinely lost. Long
     * enough to ride out a chunk boundary or a one-tick removal/re-add by another mod. */
    private static final int MOUNT_MISSING_GRACE_TICKS = 60;

    /**
     * Finds this rider's bonded horse, and - the point of the whole method - refuses to accept
     * "it isn't there" as final. A horse can vanish for reasons that have nothing to do with dying:
     * another mod removing entities, a chunk that failed to save, a {@code spawnEntity} someone else
     * cancelled. A real death goes through {@link #onBondedMountDied} instead, which clears the bond
     * outright, so anything reaching the grace period here is by definition a loss rather than a
     * death and is rebuilt from the snapshot, same UUID and all.
     */
    @Nullable
    private static EntityHorse resolveMount(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        WorldServer world = (WorldServer) rider.world;

        Entity found = world.getEntityFromUuid(mountUUID);
        if (found instanceof EntityHorse && !found.isDead) {
            rider.mountState.setMountMissingTicks(0);
            return (EntityHorse) found;
        }

        int missing = rider.mountState.getMountMissingTicks() + 1;
        rider.mountState.setMountMissingTicks(missing);
        if (missing < MOUNT_MISSING_GRACE_TICKS) return null;

        NBTTagCompound snapshot = rider.mountState.getMountSnapshotNbt();
        if (snapshot == null || !TravellersConfig.MOUNTS.restoreLostMounts) {
            rider.mountState.setMountMissingTicks(0);
            rider.setMountUUID(null);
            rider.mountState.setMountSnapshotNbt(null);
            return null;
        }

        // A horse missing from the tracked entity list is not necessarily gone: if its last known
        // spot sits in a chunk that simply isn't loaded right now (it fell behind on a leash, was
        // parked HITCHED somewhere the rider then walked away from, or was left on the far bank of a
        // boat crossing), the real horse is still there on disk. Rebuilding regardless would spawn a
        // SECOND EntityHorse with the identical bonded UUID the moment that chunk loads again and the
        // original reappears - a duplicate-identity bug, not a rescue, and the exact shape of "a horse
        // with no rider drags a leash toward wherever the real rider ended up". Only rebuild once that
        // chunk is actually loaded and still doesn't have the horse in it - the one case that is
        // genuinely a loss (another mod removed it, a save got corrupted). Never force-loads the
        // chunk to check (see the class doc's "nothing force-loads chunks" rule) - if it stays
        // unloaded, this just keeps waiting.
        if (!isSnapshotChunkLoaded(world, snapshot)) return null;
        rider.mountState.setMountMissingTicks(0);

        FMLLog.log(Level.INFO, "[Travellers/Mounts] %s's bonded horse went missing without dying - rebuilding it",
                rider.getName());
        return spawnMountFromNbt(rider, snapshot, null);
    }

    /**
     * Puts a horse held as data back into the world. Tied at a village post: only there, and only once
     * that ground is loaded and ticking (so it simply "is there" when a player comes near). Otherwise
     * (the rider untied it to ride on): beside the rider.
     */
    private static void tickParkedMount(EntityTraveller rider) {
        if (rider.ticksExisted % 20 != 0) return;
        WorldServer world = (WorldServer) rider.world;
        // The copy that was just removed can linger for a tick under the same identity.
        if (rider.getMountUUID() != null && world.getEntityFromUuid(rider.getMountUUID()) != null) return;
        NBTTagCompound nbt = rider.mountState.getPendingMountNbt();
        BlockPos post = rider.mountState.getPendingMountPos();
        boolean tied = post != null && rider.mountState.getMountMode() == MountMode.HITCHED && rider.getVillageHitchStop() != null;
        if (tied && (!world.isBlockLoaded(post) || com.jubitus.travellers.virtual.VirtualTravellerManager
                .isFrozenByVanillaAt(world, post.getX() + 0.5D, post.getZ() + 0.5D))) {
            return;
        }
        // Cleared first: the join check drops any copy while the rider still holds the horse as data.
        rider.mountState.setParkedMount(null, null);
        spawnMountFromNbt(rider, nbt, tied ? post : null);
    }

    /** Whether the chunk holding a captured mount snapshot's last known position is currently
     * loaded - see {@link #resolveMount}'s doc on why a rebuild must never happen while it isn't. */
    private static boolean isSnapshotChunkLoaded(WorldServer world, NBTTagCompound snapshot) {
        net.minecraft.nbt.NBTTagList pos = snapshot.getTagList("Pos", 6);
        if (pos.tagCount() < 3) return true; // no usable position on record - don't block a rescue on bad data
        double x = pos.getDoubleAt(0);
        double z = pos.getDoubleAt(2);
        return world.getChunkProvider().chunkExists(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4);
    }

    /** Keeps the rebuild-if-lost snapshot current (health, gear, growth) without paying for a full
     * NBT write every tick.
     *
     * <p>Never overwrites the snapshot with a horse mid-death (health {@code <= 0}): vanilla runs a
     * ~20-tick death animation before actually removing the entity ({@code Entity.isDead} only flips
     * once that finishes), so a periodic refresh landing in that window would otherwise persist an
     * already-dying horse as "the last known good state" - the exact snapshot {@link #resolveMount}
     * would later resurrect a corpse from, on a loop, if the death event itself somehow never clears
     * the bond (see {@code HorseEvents}'s doc). Skipping the write here just means the
     * previous, still-healthy snapshot survives instead.
     */
    private static void refreshMountSnapshot(EntityTraveller rider, EntityHorse horse) {
        if (horse.getHealth() <= 0.0F) return;
        if (rider.ticksExisted % 200 != 0 && rider.mountState.getMountSnapshotNbt() != null) return;
        rider.mountState.setMountSnapshotNbt(HorseSnapshots.capture(horse));
    }

    /**
     * Everything that happens while the rider is on foot with its horse still bonded. All three cases
     * end in the same guarantee - the pair stays together - and differ only in how:
     * MOUNTED wants back in the saddle, LED walks the horse along on a leash, HITCHED leaves it be.
     */
    private static void reconcileOnFoot(EntityTraveller rider, EntityHorse horse) {
        double distSq = horse.getDistanceSq(rider);
        double leadMax = TravellersConfig.MOUNTS.leadMaxDistance;

        switch (rider.mountState.getMountMode()) {
            case HITCHED:
                setLeashed(horse, null);
                BlockPos hitchStop = rider.getVillageHitchStop();
                if (hitchStop != null) {
                    // Tied at a village edge while its rider visits: never recalled, the rider comes
                    // back for it. A visit that ended some other way (the trip moved on, a reload
                    // elsewhere) hands the horse back to the normal walk-over-and-remount.
                    if (!rider.isVisitingVillage() && !hitchStop.equals(rider.getUpcomingVisitStop())) {
                        rider.setVillageHitchStop(null);
                        setMountMode(rider, MountMode.MOUNTED);
                    }
                    return;
                }
                // Standing where it was left is the whole point, so it is only ever recalled when
                // the rider has gone so far that the pair would otherwise be separated for good.
                if (distSq > (leadMax * 2.0D) * (leadMax * 2.0D)) teleportMountNear(rider, horse, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_RECALL_HITCHED);
                return;

            case LED:
                setLeashed(horse, rider);
                driveLedHorse(rider, horse, distSq);
                if (distSq > leadMax * leadMax) teleportMountNear(rider, horse, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_RECALL_LED);
                else if (considerLedStuckRecall(rider, horse, distSq)) return;
                // Orphaned LED: the terrain-fallback recovery timer isn't armed. For a traveller LED
                // is *only* ever a transient fallback (terrain, or the water-dismount below), so an
                // untracked one must not be left forever (seen in the field after a reload: the mode
                // persisted, its transient timer did not, and the rider led its horse around with the
                // horse standing right next to it). Flip back to MOUNTED and let that branch's own
                // sustained safe-to-remount gate decide when; arm tracking if the horse is far.
                if (rider.mountState.getLedFallbackSinceTick() < 0 && !rider.isRiding()) {
                    double remountDist = TravellersConfig.MOUNTS.remountDistance;
                    if (distSq <= remountDist * remountDist * 4.0D && !isMountTooHurtToRide(horse)
                            && !isNearVillageRequiringLed(rider)) {
                        rider.mountState.setMountModeRaw(MountMode.MOUNTED);
                    } else {
                        markTerrainFallbackLed(rider);
                    }
                    return;
                }
                considerTerrainFallbackRemount(rider, horse);
                return;

            case MOUNTED:
            default:
                // Someone else is already in the saddle (a player briefly slipping past HorseTheftGuard,
                // or one tick of race before it cancels the interaction) - never force-mount over an
                // existing rider. Entity.startRiding(force=true) below bypasses the single-passenger
                // check entirely and would just stack the bonded rider on as a second passenger instead
                // of evicting the first, which read as the player getting mounted then instantly kicked
                // off. Wait it out; the guard's own EntityMountEvent cancel is what actually stops this
                // from happening in the first place.
                if (horse.isBeingRidden()) return;

                // Wants to ride but isn't: it stepped off in water, or its horse is waiting where it
                // was hitched. Leash it so it walks over on its own and climb back on only once the
                // pair has been dry, grounded and close together for a sustained window - a single
                // tick of the horse touching a shelving river bed must not trigger a remount that
                // dunks it straight back under (the on-and-off flicker at the shoreline).
                setLeashed(horse, rider);
                driveLedHorse(rider, horse, distSq);

                double remountDist = TravellersConfig.MOUNTS.remountDistance;
                boolean remountReady = distSq <= remountDist * remountDist && canRemountHere(rider, horse);
                int readyTicks = remountReady ? rider.mountState.getRemountReadyTicks() + 1 : 0;
                rider.mountState.setRemountReadyTicks(readyTicks);
                if (readyTicks >= TravellersConfig.MOUNTS.remountStableTicks) {
                    rider.mountState.setRemountReadyTicks(0);
                    rider.mountState.setRemountTicks(0);
                    setLeashed(horse, null);
                    rider.startRiding(horse, true);
                    return;
                }

                // The timeout only means "the horse can't get here by itself" - a horse already
                // standing (or swimming) right next to a rider that simply can't mount yet is not
                // stuck, and teleporting it on the spot would be pure noise.
                int waited = rider.mountState.getRemountTicks() + 1;
                rider.mountState.setRemountTicks(waited);
                boolean horseIsFarAway = distSq > 8.0D * 8.0D;
                if ((waited > TravellersConfig.MOUNTS.remountTimeoutTicks && horseIsFarAway)
                        || distSq > leadMax * leadMax) {
                    rider.mountState.setRemountTicks(0);
                    teleportMountNear(rider, horse, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_RECALL_REMOUNT);
                }
        }
    }

    /**
     * The single driver of a led horse - vanilla's own leash steering is cancelled outright for
     * bonded mounts (see {@code MixinEntityCreature}). Before that cancel existed, vanilla re-pathed
     * the horse toward its holder <em>every tick</em> under 6 blocks and added spring impulses
     * between 6 and 10, in parallel with this method's own navigation - three steering sources
     * fighting over one body was the "erratic led horse" report.
     *
     * <p><b>Single-file trail follow, steered every tick.</b> The horse is driven straight at a
     * point on the rider's own recent footsteps ({@link EntityTraveller#leadTrailPointBehind}),
     * {@code ledFollowLeadGapBlocks} back - so it treads exactly the ground the rider just cleared
     * and never cuts a corner or weaves. The old model - the horse's own A* re-planned to the
     * rider's <em>current</em> position on a ~10-tick throttle - made it chase a point stale by up
     * to that many ticks, arriving under its own feet and pivoting to the next stale target: the
     * reported staircase / zig-zag / spinning.
     *
     * <p><b>Pace mirrors the rider's own current move-speed</b> ({@code getAIMoveSpeed()}, smoothed),
     * divided back out of the horse's fixed MOVEMENT_SPEED attribute so the two run identical
     * moveForward and therefore identical terminal speed, whatever task is driving the rider. A
     * bounded catch-up factor ({@code ledFollowCatchupMaxSpeedFactor}) on top only for closing a
     * real gap.
     *
     * <p>The horse's own pathfinder is the fallback, not the default: a genuine jam against terrain
     * the straight step cannot clear ({@code collidedHorizontally} and going nowhere for
     * {@code ledFollowObstacleTicks}) hands it to A* for {@code ledFollowPathfindTicks}, then it
     * resumes stepping. The straight-line tow is a second exception - only past
     * {@code ledFollowTowDistance} and only with no ground route (swimming, or behind a boat).
     */
    private static void driveLedHorse(EntityTraveller rider, EntityHorse horse, double distSq) {
        double dist = Math.sqrt(distSq);

        net.minecraft.entity.ai.attributes.IAttributeInstance attr =
                horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        double base = TravellersConfig.SPEED.movementSpeed;
        if (Math.abs(attr.getBaseValue() - base) > 1.0E-4D) attr.setBaseValue(base);
        double attrVal = Math.max(attr.getAttributeValue(), 1.0E-3D);

        // Mirror the rider's own current move-speed (moveForward-level, i.e. the same half of the
        // speed product a navigator-driven horse also runs on) into the horse's move-helper speed
        // param, smoothed - horse and rider run identical ground physics, so identical moveForward
        // gives identical terminal speed whatever task is driving the rider. Using the rider's raw
        // per-tick displacement here instead would be off by the ground-friction factor.
        double riderMove = Math.max(rider.getAIMoveSpeed(), 0.0F);
        double smoothed = rider.mountState.getLedPacerSmoothedRiderSpeed() * 0.8D + riderMove * 0.2D;
        rider.mountState.setLedPacerSmoothedRiderSpeed(smoothed);

        double stop = TravellersConfig.MOUNTS.ledFollowStopDistance;
        double leadGap = TravellersConfig.MOUNTS.ledFollowLeadGapBlocks;
        // A capped catch-up factor for closing a gap; a walk-pace floor so a lagging horse still
        // makes ground even when the rider itself has slowed to a crawl.
        double pace = Math.max(MIN_MOUNTED_SPEED, smoothed / attrVal);
        if (dist > stop + leadGap) {
            double over = dist - (stop + leadGap);
            pace = Math.max(pace, 1.0D)
                    * Math.min(1.0D + over * 0.15D, TravellersConfig.MOUNTS.ledFollowCatchupMaxSpeedFactor);
        }

        // Deep water / no ground to tread: tow it straight (behind a swimming rider or a boat).
        if (dist > TravellersConfig.MOUNTS.ledFollowTowDistance
                && (isSwimming(horse) || horse.getNavigator().noPath())) {
            double dx = (rider.posX - horse.posX) / dist;
            double dy = (rider.posY - horse.posY) / dist;
            double dz = (rider.posZ - horse.posZ) / dist;
            horse.motionX += dx * Math.abs(dx) * 0.4D;
            horse.motionY += dy * Math.abs(dy) * 0.4D;
            horse.motionZ += dz * Math.abs(dz) * 0.4D;
            haltLedHorseMoveHelper(horse);
            return;
        }

        // At heel: stand still, no jitter. The move helper keeps its last MOVE_TO order until told
        // otherwise (it never reverts to WAIT on its own), so command the stop explicitly or the
        // horse drifts on to the stale aim and orbits it.
        if (dist <= stop) {
            if (!horse.getNavigator().noPath()) horse.getNavigator().clearPath();
            haltLedHorseMoveHelper(horse);
            rider.mountState.setLedPacerObstacleTicks(0);
            return;
        }

        // Obstacle fallback window: let the horse's own pathfinder route around whatever the
        // straight step is jammed against.
        //
        // Budgeted on the world-wide mount row of PathBudget. Without this, a horse whose search keeps failing (routine on rough
        // terrain, exactly where LED triggers most) re-ran a full A* EVERY tick for the whole
        // ledFollowPathfindTicks window - with many mounted followers jamming at once on hard
        // terrain, that is the single biggest cost in a large mounted group (profiled: tens of
        // percent of server tick time). A denied tick just skips the search and retries next tick;
        // the horse stands still that one tick rather than spending it on a doomed search.
        if (rider.ticksExisted < rider.mountState.getLedPacerNavUntilTick()) {
            if (horse.getNavigator().noPath()
                    && com.jubitus.travellers.pathing.PathBudget.tryAcquire(horse.world, com.jubitus.travellers.pathing.PathBudget.Client.MOUNT, rider.getEntityId(), 1)) {
                horse.getNavigator().tryMoveToEntityLiving(rider, pace);
            }
            return;
        }

        // Normal case: step straight after the rider along its own footsteps.
        Vec3d aim = rider.mountState.leadTrailPointBehind(leadGap);
        double ax = aim != null ? aim.x : rider.posX;
        double ay = aim != null ? aim.y : rider.posY;
        double az = aim != null ? aim.z : rider.posZ;
        if (!horse.getNavigator().noPath()) horse.getNavigator().clearPath();
        horse.getMoveHelper().setMoveTo(ax, ay, az, pace);

        double horseDispSq = (horse.posX - horse.prevPosX) * (horse.posX - horse.prevPosX)
                + (horse.posZ - horse.prevPosZ) * (horse.posZ - horse.prevPosZ);
        if (horse.collidedHorizontally && horseDispSq < 0.0016D) {
            int t = rider.mountState.getLedPacerObstacleTicks() + 1;
            rider.mountState.setLedPacerObstacleTicks(t);
            if (t >= TravellersConfig.MOUNTS.ledFollowObstacleTicks) {
                rider.mountState.setLedPacerObstacleTicks(0);
                // Arm the window regardless of whether a search is actually granted this exact tick -
                // a denied search here is picked up by the budgeted retry above on a later tick, the
                // jam signal itself must not be lost.
                rider.mountState.setLedPacerNavUntilTick(rider.ticksExisted + TravellersConfig.MOUNTS.ledFollowPathfindTicks);
                if (com.jubitus.travellers.pathing.PathBudget.tryAcquire(horse.world, com.jubitus.travellers.pathing.PathBudget.Client.MOUNT, rider.getEntityId(), 1)) {
                    horse.getNavigator().tryMoveToEntityLiving(rider, pace);
                }
                com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LED_HORSE_JAMMED);
                com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                        "led horse jammed - pathfinding around");
            }
        } else if (rider.mountState.getLedPacerObstacleTicks() > 0) {
            rider.mountState.setLedPacerObstacleTicks(rider.mountState.getLedPacerObstacleTicks() - 1);
        }
    }

    /** Commands a full stop on a led horse's move helper - it never reverts to WAIT on its own, so a
     * stale MOVE_TO order left in place keeps it walking (and then orbiting) the last aim point. */
    private static void haltLedHorseMoveHelper(EntityHorse horse) {
        horse.getMoveHelper().setMoveTo(horse.posX, horse.posY, horse.posZ, 0.0D);
    }

    /**
     * Quick reunite for a rider leading on foot whose horse is snagged on terrain well behind it but
     * not yet at the full {@code leadMaxDistance} recall gap. Without this the rider - which now only
     * ever slows, never stops, to lead (see {@link MountedLeadController}) - walks its whole route
     * with the horse stuck {@code ledStuckRecallDistance}..{@code leadMaxDistance} blocks back: too
     * far for the horse to catch up on its own, too near to trigger the leadMax recall, so the pair
     * only reunites when some unrelated AI drags the rider far enough. Progress is measured as the
     * gap actually shrinking; mere jostling doesn't reset it. The recall drops the horse back onto
     * the rider's own trail a step behind it (see {@link #resolveMountSpawnPos}). Returns
     * {@code true} when it recalled the horse this tick.
     */
    private static boolean considerLedStuckRecall(EntityTraveller rider, EntityHorse horse, double distSq) {
        double recallDist = TravellersConfig.MOUNTS.ledStuckRecallDistance;
        if (distSq <= recallDist * recallDist) {
            rider.mountState.setLedHorseStuckTicks(0);
            rider.mountState.setLedHorseBestGapSq(distSq);
            return false;
        }
        double best = rider.mountState.getLedHorseBestGapSq();
        if (distSq < best - 1.0D) {            // gap genuinely closing
            rider.mountState.setLedHorseBestGapSq(distSq);
            rider.mountState.setLedHorseStuckTicks(0);
            return false;
        }
        int stuck = rider.mountState.getLedHorseStuckTicks() + 1;
        rider.mountState.setLedHorseStuckTicks(stuck);
        if (stuck < TravellersConfig.MOUNTS.ledStuckRecallTicks) return false;

        rider.mountState.setLedHorseStuckTicks(0);
        rider.mountState.setLedHorseBestGapSq(Double.MAX_VALUE);
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LED_HORSE_SNAG_RECALL);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                "led horse snagged behind and not catching up - bringing it to the rider");
        teleportMountNear(rider, horse, null);
        return true;
    }

    /**
     * Genuinely swimming, as opposed to merely standing in shallow water: a horse fording an
     * ankle-deep stream stays on the ground and keeps carrying its rider, which is what a real one
     * does. {@code isInWater()} alone is true for both, hence the second half.
     */
    private static boolean isSwimming(EntityHorse horse) {
        return horse.isInWater()
                && (!horse.onGround || horse.isInsideOfMaterial(Material.WATER));
    }

    /** Whether this horse is hurt enough that its rider should be leading it, not riding it - see
     * {@code MOUNTS.mountLowHealthLedThresholdPercent}. 0 turns the whole feature off. */
    private static boolean isMountTooHurtToRide(EntityHorse horse) {
        int thresholdPercent = TravellersConfig.MOUNTS.mountLowHealthLedThresholdPercent;
        if (thresholdPercent <= 0) return false;
        float max = horse.getMaxHealth();
        return max > 0 && horse.getHealth() / max < (thresholdPercent / 100.0F);
    }

    /** Hitches the horse where it stands once its rider is inside the grounds of the settlement it is
     * about to visit - never inside or on a building, never in water or mid-jump (it just retries). */
    private static void tryHitchAtVillageEdge(EntityTraveller rider, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.hitchMountInVillages || rider.isInCombat()) return;
        BlockPos stop = rider.getUpcomingVisitStop();
        if (stop == null) return;
        com.jubitus.travellers.village.VillageVisitIndex.Settlement s =
                com.jubitus.travellers.village.VillageVisitIndex.get(rider.world, stop);
        if (s == null) return;
        double dx = rider.posX - (s.center.getX() + 0.5D);
        double dz = rider.posZ - (s.center.getZ() + 0.5D);
        double r = Math.max(s.radius, TravellersConfig.VILLAGE_VISIT.arrivalRadius);
        if (dx * dx + dz * dz > r * r) return;
        if (!horse.onGround || horse.isInWater() || isSwimming(horse)) return;
        if (com.jubitus.travellers.village.MillFootprints.isForbiddenLanding(rider.world,
                horse.posX, horse.posY, horse.posZ)) return;
        setMountMode(rider, MountMode.HITCHED);
        rider.setVillageHitchStop(stop);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "hitched at village edge %.1f,%.1f,%.1f", horse.posX, horse.posY, horse.posZ));
    }

    /** Ticks between two settlement-proximity rescans - see {@code nextVillageProximityCheckTick}. */
    private static final int VILLAGE_PROXIMITY_CHECK_INTERVAL = 10;

    /** Whether this rider should currently be leading its horse rather than riding it because it is
     * on a village or lone building's own grounds - see {@code MOUNTS.mountedDismountNearVillage}.
     * A hired worker actively escorting its owner is exempt: it needs to keep pace with a player who
     * may itself be mounted, and villages are exactly where an escort would otherwise fall behind.
     * Throttled per rider ({@link EntityTraveller#getNextVillageProximityCheckTick()}) since the
     * settlement scan itself is too heavy to redo unconditionally every tick for every mounted rider
     * in the world, mounted or not, near a village or not. */
    private static boolean isNearVillageRequiringLed(EntityTraveller rider) {
        if (!TravellersConfig.MOUNTS.mountedDismountNearVillage) return false;
        if (rider instanceof EntityTravellerWorker && ((EntityTravellerWorker) rider).isHiredFollower()) {
            return false;
        }
        if (rider.ticksExisted < rider.mountState.getNextVillageProximityCheckTick()) {
            return rider.mountState.isCachedNearVillage();
        }
        rider.mountState.setNextVillageProximityCheckTick(rider.ticksExisted + VILLAGE_PROXIMITY_CHECK_INTERVAL);
        boolean near = com.jubitus.travellers.village.MillSettlements.isInside(
                rider.world, rider.getPosition());
        rider.mountState.setCachedNearVillage(near);
        return near;
    }

    /**
     * The instantaneous half of the remount test (the other half is the sustained-tick window in
     * {@code reconcileOnFoot}'s MOUNTED branch). Both the rider and the horse must be on solid ground
     * and clear of water: vanilla ejects a rider whose head goes underwater, and remounting a horse
     * still standing in the river it hasn't climbed out of just dunks the rider again. "Dry on the
     * far bank" is the whole rule the user asked for - never in the water, never while the horse is
     * still on the bank it left.
     */
    private static boolean canRemountHere(EntityTraveller rider, EntityHorse horse) {
        if (rider.isRiding()) return false; // already in something else (a boat crossing)
        if (!rider.onGround || rider.isInWater()) return false;
        return horse.onGround && !horse.isInWater();
    }

    /**
     * Attaches or detaches the vanilla leash. Detaching goes through a guard flag because
     * {@code MixinEntityLiving} otherwise refuses every {@code clearLeashed} on a bonded mount - that
     * refusal is what keeps vanilla's 10-block leash snap (and the lead item it drops) from ever
     * separating a horse from its rider. Passing {@code null} detaches.
     */
    static void setLeashed(EntityHorse horse, @Nullable EntityTraveller holder) {
        if (holder == null) {
            if (!horse.getLeashed()) return;
            allowUnleash = true;
            try {
                horse.clearLeashed(true, false);
            } finally {
                allowUnleash = false;
            }
            return;
        }
        if (horse.getLeashed() && horse.getLeashHolder() == holder) return;
        if (horse.getLeashed()) setLeashed(horse, null);
        horse.setLeashHolder(holder, true);
    }

    /** Set only while this class is deliberately detaching a leash - see {@link #setLeashed}. */
    private static boolean allowUnleash;

    /** Whether vanilla is currently allowed to break this horse's leash (only when we asked for it,
     * or once it is dead and the leash has nothing left to hold). */
    public static boolean mayUnleash(Entity horse) {
        return allowUnleash || horse.isDead;
    }

    /** Brings a horse back beside its rider, on a spot that is actually clear - the fallback every
     * mode escalates to rather than ever letting the pair drift apart permanently. */
    private static void teleportMountNear(EntityTraveller rider, EntityHorse horse,
                                          @Nullable com.jubitus.travellers.debug.profile.MovementAnomaly reason) {
        MountSpawnPos pos = resolveMountSpawnPos(rider, horse);
        if (TravellersConfig.MOUNTS.debugMounts) {
            FMLLog.log(Level.INFO, "[Travellers/Mounts] %s recalling its horse from %.1f blocks away (mode=%s)",
                    rider.getName(), Math.sqrt(horse.getDistanceSq(rider)), rider.mountState.getMountMode());
        }
        com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, com.jubitus.travellers.debug.profile.MovementAnomaly.HORSE_TELEPORT_RECALL);
        // The snag recall counts itself (LED_HORSE_SNAG_RECALL) before calling here.
        if (reason != null) com.jubitus.travellers.debug.profile.MovementProfiler.anomaly(rider, reason);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "recall horse from %.1f blocks (mode=%s)",
                        Math.sqrt(horse.getDistanceSq(rider)), rider.mountState.getMountMode()));
        horse.motionX = 0;
        horse.motionY = 0;
        horse.motionZ = 0;
        horse.setPositionAndUpdate(pos.x, pos.y, pos.z);
    }

    /**
     * Called from {@code EntityTraveller.setPositionAndUpdate} so a led horse follows its rider
     * through the mod's rescue/catch-up hops, exactly as a ridden one already does. Skipped when the
     * horse is nowhere near - that means the pair is already being reconciled by distance, and
     * dragging it in from far away would turn an ordinary short hop into a visible recall.
     */
    public static void teleportLedMountWith(EntityTraveller rider, double x, double y, double z) {
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return;
        Entity mount = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
        if (!(mount instanceof EntityHorse) || mount.isDead) return;
        if (mount.getDistanceSq(rider) > 32.0D * 32.0D) return;
        mount.motionX = 0;
        mount.motionY = 0;
        mount.motionZ = 0;
        mount.setPositionAndUpdate(x, y, z);
    }

    /**
     * Tells the owning player that their worker's horse gave up trying to ride through and is being
     * led on foot instead - the dismount is otherwise silent to the player, logged only to the debug
     * log and trace file. Called from the two places that switch to {@link MountMode#LED} because the
     * horse genuinely cannot make it, not from the routine cases (boats, villages, camps) where a
     * dismount is expected and not worth a toast.
     *
     * <p>Hired workers only: travellers/raiders have no owning player to tell.
     */
    public static void notifyMountLedFallback(EntityTraveller rider,
                                                com.jubitus.travellers.notify.NotificationDef def) {
        if (!TravellersConfig.MOUNTS.notifyOnMountLedFallback) return;
        if (!(rider instanceof EntityTravellerWorker) || !(rider.world instanceof WorldServer)) return;
        UUID ownerId = ((EntityTravellerWorker) rider).getFollowOwnerId();
        if (ownerId == null) return;
        com.jubitus.travellers.notify.NotificationService.notifyOffline(
                ((WorldServer) rider.world).getMinecraftServer(), ownerId, def, rider.getName());
    }

    /**
     * Sets what the rider wants to do with its horse. The transition itself (getting off onto solid
     * ground, leashing, climbing back on) is left to {@link #tickMount}, which runs every tick and
     * can wait for the right moment - a mode change asked for mid-river or mid-jump would otherwise
     * have to either fail or drop the rider somewhere silly.
     */
    public static void setMountMode(EntityTraveller rider, MountMode mode) {
        if (rider.mountState.getMountMode() == mode) return;
        if (TravellersConfig.MOUNTS.debugMounts) {
            FMLLog.log(Level.INFO, "[Travellers/Mounts] %s mount mode %s -> %s (riding=%b)",
                    rider.getName(), rider.mountState.getMountMode(), mode, rider.isMountedOnBondedMount());
        }
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                "mode " + rider.mountState.getMountMode() + " -> " + mode);
        if (rider.mountState.getMountMode() == MountMode.LED) com.jubitus.travellers.debug.profile.MovementProfiler.ledEnded(rider);
        rider.mountState.setMountModeRaw(mode);
        rider.mountState.setRemountTicks(0);
        rider.mountState.setRemountReadyTicks(0);
        // Leaving LED for any reason retires the terrain-fallback self-heal timer, whatever armed
        // it - a stale timer/position surviving into a later, unrelated LED spell (say, a boat
        // crossing towing the horse alongside) would otherwise fire instantly on old data. See
        // markTerrainFallbackLed/considerTerrainFallbackRemount.
        if (mode != MountMode.LED) {
            rider.mountState.setLedFallbackSinceTick(-1);
        }
        // The led-stuck quick-recall tracker and the trail-pacer state only mean anything while
        // leading on foot.
        rider.mountState.setLedHorseStuckTicks(0);
        rider.mountState.setLedHorseBestGapSq(Double.MAX_VALUE);
        rider.mountState.setLedPacerObstacleTicks(0);
        rider.mountState.setLedPacerNavUntilTick(0);
        rider.mountState.setLedPacerSmoothedRiderSpeed(0.0D);
        if (mode != MountMode.MOUNTED && rider.isMountedOnBondedMount()) {
            // Vanilla already places a dismounting rider beside a horse rather than inside it
            // (EntityLivingBase.dismountEntity has a dedicated AbstractHorse branch that steps off to
            // the side and lifts out of any block it would land in).
            rider.dismountRidingEntity();
        }
    }

    /**
     * Arms the shared time+distance self-heal back to {@link MountMode#MOUNTED}, checked every tick
     * from {@link #reconcileOnFoot}. Call right after switching to {@link MountMode#LED} for a "the
     * horse genuinely cannot make it through here" reason - not for a routine LED (a boat crossing
     * towing the horse alongside), which restores its own mode explicitly once the crossing ends and
     * has no need of this.
     *
     * <p>Two separate places dismount a rider for terrain reasons: a footprint-blocked route
     * ({@code EntityAITravel.considerTerrainDismount}) and giving up on sidesteps against a wedge
     * ({@link #trySidestepIfWedged}, above). Both call this. A rider left permanently on foot after
     * giving up on a wedge - no self-heal at all - was a real bug: only the route-check path used to
     * arm any recovery, so a rider that ended up in LED via the other one simply never got back on.
     */
    public static void markTerrainFallbackLed(EntityTraveller rider) {
        rider.mountState.setLedFallbackSinceTick(rider.ticksExisted);
        rider.mountState.setLedFallbackPos(rider.posX, rider.posZ);
    }

    /**
     * Climbs back on once the pair is clear of whatever forced them off, judged by time <em>and</em>
     * distance covered rather than by re-testing whether a mounted route exists - re-testing right
     * next to the obstacle would keep answering "no" at the exact spot the rider just walked clear
     * of. Having both conditions is what stops the pair from mounting and dismounting on alternating
     * attempts; if the terrain ahead is still too tight, the next failed route or wedge simply arms
     * this again.
     */
    private static void considerTerrainFallbackRemount(EntityTraveller rider, EntityHorse horse) {
        int since = rider.mountState.getLedFallbackSinceTick();
        if (since < 0) return;
        if (rider.ticksExisted - since < TravellersConfig.MOUNTS.terrainRemountAfterTicks) return;
        // Only ever remount onto dry, solid footing - not while the rider is still swimming a river
        // or mid-jump. The MOUNTED reconcile's own canRemountHere covers this once the mode flips,
        // but flipping the mode mid-water first drops a tick of the rider standing on the water.
        if (!rider.onGround || rider.isInWater()) return;
        // A horse led for being badly wounded must stay led regardless of elapsed time/distance -
        // those two alone are the terrain-recovery signal, not "has this horse healed".
        if (isMountTooHurtToRide(horse)) return;
        // Likewise: still on a village's own grounds means still not remounting, whatever the
        // elapsed time/distance since dismount says - a village bigger than terrainRemountDistance
        // would otherwise remount the rider mid-street.
        if (isNearVillageRequiringLed(rider)) return;

        double dx = rider.posX - rider.mountState.getLedFallbackX();
        double dz = rider.posZ - rider.mountState.getLedFallbackZ();
        double need = TravellersConfig.MOUNTS.terrainRemountDistance;
        if (dx * dx + dz * dz < need * need) return;

        setMountMode(rider, MountMode.MOUNTED);
        com.jubitus.travellers.debug.trace.TravellerTracer.event(rider, "mount",
                "clear of the tight ground - remounting");
    }

    /**
     * A bonded horse outlives its rider as an ordinary tamed horse: leash off, speed back to normal,
     * bond marker gone so the anti-theft guard stops protecting it and a player can claim it. Its own
     * vanilla AI is not rebuilt here (there is no public way to re-run {@code initEntityAI}) - it
     * comes back on its own the next time the horse is loaded from disk, since
     * {@link MountBondLoadHandler} will no longer recognise it as bonded.
     */
    public static void releaseMountOnRiderDeath(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) rider.world;
        Entity mount = world.getEntityFromUuid(mountUUID);
        rider.setMountUUID(null);
        rider.mountState.setMountSnapshotNbt(null);
        if (!(mount instanceof EntityHorse) || mount.isDead) {
            if (rider.mountState.getPendingMountNbt() != null) {
                // Held as data (tied at an unloaded post): it goes with its rider, like offscreen.
                rider.mountState.setParkedMount(null, null);
                return;
            }
            // The horse sits in an unloaded chunk: release (or remove) it the moment it loads again.
            MountReleaseLedger.get(world).add(mountUUID, TravellersConfig.MOUNTS.mountDiesWithRider);
            return;
        }
        if (TravellersConfig.MOUNTS.mountDiesWithRider) {
            killWithRider((EntityHorse) mount);
            return;
        }

        // Uniform for every rider type (plain traveller/raider, non-hired worker, hired worker): the
        // horse just becomes an ordinary loose, orphaned horse exactly where its rider died - never
        // teleported/respawned elsewhere (a nearby groupmate, an owner's Stable). Any nearby recruited
        // worker can notice and bond to it on its own (EntityAIClaimLooseMount), same as a player can
        // by simply walking up and taming it - see OrphanMountCleanup for the despawn rules.
        detachBondFromHorse((EntityHorse) mount);
    }

    /** A rider gone for good (died offscreen) whose horse was not held as data: the horse goes loose
     * where it stands, or when its chunk next loads. */
    public static void releaseHorseOfGoneRider(WorldServer world, UUID horseId) {
        Entity mount = world.getEntityFromUuid(horseId);
        boolean dies = TravellersConfig.MOUNTS.mountDiesWithRider;
        if (mount instanceof EntityHorse && !mount.isDead) {
            if (dies) killWithRider((EntityHorse) mount);
            else detachBondFromHorse((EntityHorse) mount);
        } else {
            MountReleaseLedger.get(world).add(horseId, dies);
        }
    }

    /** MOUNTS.mountDiesWithRider: an ordinary death (animation, saddle/armor/loot drops), bond cleared first. */
    private static void killWithRider(EntityHorse horse) {
        setLeashed(horse, null);
        HorseMarkers.clearBondedOwner(horse);
        horse.attackEntityFrom(net.minecraft.util.DamageSource.GENERIC, Float.MAX_VALUE);
        if (horse.getHealth() > 0.0F && !horse.isDead) horse.onKillCommand();
    }

    /** Vanilla's mid-range horse speed roll, used when a horse stops being a bonded mount. */
    private static final double DEFAULT_HORSE_SPEED = 0.225D;

    /** Leash off, speed back to vanilla default, bond marker gone, orphan-cleanup timer started -
     * the horse survives as an ordinary tamed horse with nobody to go to. Used by
     * {@link #releaseMountOnRiderDeath} (rider died, no groupmate/Stable to give it to). */
    static void detachBondFromHorse(EntityHorse horse) {
        setLeashed(horse, null);
        HorseMarkers.clearBondedOwner(horse);
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .setBaseValue(DEFAULT_HORSE_SPEED);
        HorseMarkers.markOrphaned(horse, horse.world.getTotalWorldTime());
        HorseAI.restoreVanilla(horse);
    }

    /**
     * Hard, immediate teardown for an explicit debug wipe ({@code /travellersdebug reset[ workers]}):
     * unlike a natural death, there is no grace period to give a nearby player a chance to notice -
     * the whole point of the command is a clean slate right now. Killing the mount here (instead of
     * leaving it bonded to a rider {@code setDead()} is about to remove without ever running
     * {@link #releaseMountOnRiderDeath}) is what stops a debug reset from leaving orphaned horses
     * behind that the command's own summary claims were "removed".
     */
    public static void detachAndKillMountForDebugWipe(EntityTraveller rider) {
        UUID mountUUID = rider.getMountUUID();
        rider.setMountUUID(null);
        rider.mountState.setMountSnapshotNbt(null);
        boolean heldAsData = rider.mountState.getPendingMountNbt() != null;
        rider.mountState.setParkedMount(null, null);
        if (heldAsData) return;
        if (mountUUID == null || !(rider.world instanceof WorldServer)) return;
        Entity mount = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
        if (!(mount instanceof EntityHorse) || mount.isDead) {
            MountReleaseLedger.get(rider.world).add(mountUUID, true);
            return;
        }
        setLeashed((EntityHorse) mount, null);
        mount.setDead();
    }

    /**
     * Reconciles a bonded horse found in a chunk that is unloading, when its rider is NOT part of the
     * same unload (see {@code ChunkUnloadVirtualizeHandler}) - the one gap that let a horse become
     * permanently abandoned: its own chunk unloading on a different tick than its rider's silently
     * removes it from {@code World.entitiesByUuid} with nobody having captured it, and
     * {@code captureMountIfBonded}'s own duplicate-avoidance rule then refuses to ever rebuild it (see
     * that method's doc). Called instead of letting the chunk simply save the horse and forget it:
     * <ul>
     *   <li>rider still loaded (elsewhere): {@link #captureMount}+{@link #restoreMount} it right next
     *   to the rider - deliberately NOT a live teleport of the same entity object. By the time this
     *   event fires, {@code Chunk.onUnload()} has already queued every entity that was in this chunk's
     *   own entity lists into {@code World.unloadedEntityList} (see that method), and the next entity
     *   pass removes them from {@code entitiesByUuid} by their chunk membership at that moment -
     *   repositioning the object changes nothing. Killing it (which does work - the exact mechanism
     *   {@link ChunkUnloadVirtualizeHandler}'s own class doc relies on for travellers) and spawning a
     *   fresh one from the same NBT is the only path that actually keeps the pair together.</li>
     *   <li>rider gone virtual: capture the horse's NBT straight into that record, mirroring
     *   {@code VirtualTravellerManager.captureMountIfBonded} exactly, then remove the horse - the
     *   record picks it back up whenever it next materializes.</li>
     *   <li>rider genuinely gone from both (should not normally happen - a real death already goes
     *   through {@link #releaseMountOnRiderDeath}): treat it the same as an unclaimed dead-rider mount
     *   rather than leaving it bonded to nothing forever.</li>
     * </ul>
     */
    public static void handleBondedHorseChunkUnload(WorldServer world, EntityHorse horse) {
        UUID riderUUID = HorseMarkers.getBondedOwner(horse);
        if (riderUUID == null) return;

        // A horse mid-death-animation (health already <= 0, vanilla's own removal still ~20 ticks
        // away) is not an unload-orphan, it is a death that should already have cleared the bond -
        // see HorseEvents.onLivingDeath's doc. Capturing its NBT here would freeze a corpse into the record
        // exactly like captureMount's own guard prevents; just clear the bond and let the chunk save
        // the dying horse as-is (deathTime is persisted, so it resumes and finishes dying on its own
        // once the chunk reloads).
        if (horse.getHealth() <= 0.0F) {
            onBondedMountDied(horse);
            return;
        }

        Entity rider = world.getEntityFromUuid(riderUUID);
        if (rider instanceof EntityTraveller && !rider.isDead
                && ((EntityTraveller) rider).mountState.getMountMode() == MountMode.HITCHED
                && ((EntityTraveller) rider).getVillageHitchStop() != null) {
            // Tied at a village post while its rider visits: it stays at its post, as data on the
            // rider, and reappears there when that ground is loaded again (tickParkedMount).
            EntityTraveller r = (EntityTraveller) rider;
            setLeashed(horse, null);
            NBTTagCompound snapshot = HorseSnapshots.capture(horse);
            BlockPos post = horse.getPosition();
            horse.setDead();
            r.mountState.setParkedMount(snapshot, post);
            com.jubitus.travellers.debug.trace.TravellerTracer.event(r, "mount", "horse at the post kept as data (its chunk unloaded)");
            return;
        }
        if (rider instanceof EntityTraveller && !rider.isDead) {
            EntityTraveller r = (EntityTraveller) rider;
            boolean wasRidden = horse.isBeingRidden();
            if (wasRidden) r.dismountRidingEntity();
            setLeashed(horse, null);
            NBTTagCompound snapshot = HorseSnapshots.capture(horse);
            // Same-tick capture-then-respawn (unlike an ordinary materialize, which restores from a
            // snapshot taken ticks or minutes earlier): the dying horse would still linger in
            // entitiesByUuid for a moment, so the new one must not reuse its UUID (see CLAUDE.md's
            // "respawned live snapshot" trap) - strip it and let spawnMountFromNbt mint + re-link one.
            HorseSnapshots.stripIdentity(snapshot);
            horse.setDead();
            r.setMountUUID(null);
            restoreMount(r, snapshot);
            return;
        }

        com.jubitus.travellers.virtual.VirtualTravellerManager vtm =
                com.jubitus.travellers.virtual.VirtualTravellerManager.get(world);
        if (vtm.isVirtual(riderUUID)) {
            setLeashed(horse, null);
            vtm.captureOrphanedMount(riderUUID, HorseSnapshots.capture(horse));
            horse.setDead();
            return;
        }

        detachBondFromHorse(horse);
    }

    /**
     * The one event that genuinely ends a bond. Everything else - a missing horse, an unloaded chunk,
     * a rider on foot - is recoverable and must never clear it, or the rider silently loses a
     * companion it could have got back (see {@link #resolveMount}).
     */
    public static void onBondedMountDied(EntityHorse horse) {
        UUID ownerUUID = HorseMarkers.getBondedOwner(horse);
        if (ownerUUID == null || !(horse.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) horse.world;

        Entity owner = world.getEntityFromUuid(ownerUUID);
        if (owner instanceof EntityTraveller) {
            EntityTraveller rider = (EntityTraveller) owner;
            // The horse isn't actually gone yet (LivingDeathEvent fires ~20 ticks before setDead()),
            // and the rider is still isRiding() until told otherwise. Left alone, isMountedOnBondedMount()
            // flips false the instant mountUUID clears below while isRiding() stays true - every
            // mount-aware system (pathing, setPositionAndUpdate's mount-carry logic) then starts
            // treating the rider as on-foot and moving it, while vanilla's own updatePassenger() keeps
            // snapping its position back onto the dying horse every tick. That fight is what read as
            // "teleports far away, then snaps right back". Dismounting here first ends it immediately.
            if (rider.isRiding()) rider.dismountRidingEntity();
            rider.setMountUUID(null);
            rider.mountState.setMountSnapshotNbt(null);
            rider.mountState.setMountModeRaw(MountMode.MOUNTED);
            return;
        }
        // Rider is offscreen: clear the same thing on its virtual record instead, or it would
        // materialize later still expecting a horse that no longer exists.
        com.jubitus.travellers.virtual.VirtualTravellerManager.get(world)
                .clearRecordMount(ownerUUID);
    }

    /**
     * Horse-side counterpart to {@code TravellerNavigateGround.logMountedSteer} (which only ever saw
     * the rider). Everything here answers one specific question that code reading alone could not
     * settle about the "erratic after materialize / after a world reload" report:
     *
     * <ul>
     *   <li>{@code saddled} - {@code AbstractHorse.travel()} gates its whole ridden branch on this.
     *       False means the rider's steering is not read at all, whatever the rest of the line says.</li>
     *   <li>{@code hTasks}/{@code hNoPath} - is the horse's OWN vanilla AI alive and pathing in
     *       parallel with the rider's steering? A horse reconstructed from chunk NBT ({@code new
     *       EntityHorse(world)} + {@code initEntityAI()}) comes back with its full task list, which
     *       is what {@link MountBondLoadHandler} now strips again on join.</li>
     *   <li>{@code onGround}/{@code airBorne}/{@code motionY} - a horse whose own navigator keeps
     *       calling {@code getJumpHelper().setJumping()} self-jumps every few ticks. That jump lands
     *       BEFORE {@code AbstractHorse.travel()} in {@code onLivingUpdate}, so the ridden branch's
     *       overwrite of moveForward/strafe/yaw never cancels it, and the resulting ground/air
     *       alternation swings the friction term between 0.546 and 0.91 tick to tick.</li>
     *   <li>{@code dxz} vs {@code attr} - real per-tick displacement against the ceiling vanilla
     *       ground physics can produce for that attribute ({@code attr / (1 - 0.546)}). Above it,
     *       something is moving the horse more than once per tick.</li>
     *   <li>{@code hTicks}/{@code rTicks} - the direct double-tick check: these must advance in
     *       lockstep. A widening gap means one of the two is in {@code loadedEntityList} twice.</li>
     *   <li>{@code rFwd}/{@code rSpeed} - what the rider's AI is actually asking for. The horse's
     *       attribute is only half the product ({@code AbstractHorse.travel} feeds the rider's own
     *       moveForward into it), so a speed change with a flat attribute has to show up here.</li>
     *   <li>{@code pass} - passenger count, to rule out a duplicated rider riding the same horse.</li>
     * </ul>
     */
    private static void logHorseDiagnostic(EntityTraveller traveller, EntityHorse horse) {
        if (!TravellersConfig.MOUNTS.debugMounts) return;

        double dx = horse.posX - horse.prevPosX;
        double dz = horse.posZ - horse.prevPosZ;
        double attr = horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();

        FMLLog.log(Level.INFO,
                "[Travellers/MountDiag] %s saddled=%b hTasks=%d hNoPath=%b hColl=%b onGround=%b airBorne=%b motionY=%.3f "
                        + "dxz=%.3f attr=%.4f ceiling=%.3f rFwd=%.3f rSpeed=%.2f rNoPath=%b pass=%d "
                        + "hTicks=%d rTicks=%d",
                traveller.getName(),
                horse.isHorseSaddled(),
                horse.tasks.taskEntries.size(),
                horse.getNavigator().noPath(),
                // Separates "the planner approved a node the horse can't fit through" from "the
                // steering went off-path": a stall with hColl=true is a real body collision.
                horse.collidedHorizontally,
                horse.onGround,
                horse.isAirBorne,
                horse.motionY,
                Math.sqrt(dx * dx + dz * dz),
                attr,
                attr / 0.454D,
                traveller.moveForward,
                traveller.getMoveHelper().getSpeed(),
                traveller.getNavigator().noPath(),
                horse.getPassengers().size(),
                horse.ticksExisted,
                traveller.ticksExisted);

        // applyBondInvariants is supposed to leave exactly one task (EntityAISwimming). A real log
        // showed two on every bonded horse, so something re-adds one after the strip - with 69 mods
        // loaded, most likely another mod's own EntityJoinWorldEvent handler. Names it rather than
        // guessing, throttled since it never changes tick to tick.
        if (horse.tasks.taskEntries.size() != 1 && traveller.ticksExisted % 200 == 0) {
            StringBuilder names = new StringBuilder();
            for (EntityAITasks.EntityAITaskEntry entry : horse.tasks.taskEntries) {
                if (names.length() > 0) names.append(", ");
                names.append(entry.priority).append(':').append(entry.action.getClass().getName());
            }
            FMLLog.log(Level.INFO, "[Travellers/MountDiag] %s's horse has unexpected AI tasks: %s",
                    traveller.getName(), names);
        }
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
    public static void applyMountedGroundSpeed(EntityHorse horse) {
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(TravellersConfig.SPEED.movementSpeed);
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
    public static void applyBondInvariants(EntityHorse horse) {
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
    private static final int SADDLE_SLOT = 400;

    /**
     * The volume that must actually be clear for a mounted rider to be placed with its horse's feet
     * at {@code groundY} - horse body, rider on top, and the widest of the two.
     *
     * <p>Every rescue/catch-up teleport in this mod validates its landing spot with
     * {@code mob.getEntityBoundingBox().offset(...)}, which mounted is the <em>rider's</em> box, half
     * a body above the ground and barely half a block wide. Checked that way, a one-block niche in a
     * cliff face reads as free: the horse is then materialised inside solid rock, suffocates, and -
     * having nowhere to path out of - triggers a second rescue on top of the first. Observed exactly
     * that, twice in one trace, ending with the horse dead.
     *
     * <p>Returns {@code null} when the rider is not mounted, so callers keep their own box.
     */
    @Nullable
    public static AxisAlignedBB mountedLandingBox(EntityTraveller rider, double cx, double groundY, double cz) {
        if (!rider.isMountedOnBondedMount()) return null;
        Entity horse = rider.getRidingEntity();
        if (horse == null) return null;
        return landingBox(horse, rider, cx, groundY, cz);
    }

    /**
     * The horse body standing at {@code (cx, groundY, cz)}, plus the rider on the saddle when
     * {@code rider} is not null (whichever is wider / taller). Works before either is mounted.
     */
    public static AxisAlignedBB landingBox(Entity horse, @Nullable Entity rider, double cx, double groundY, double cz) {
        double half = Math.max(horse.width, rider != null ? rider.width : 0.0D) / 2.0D;
        double top = groundY + horse.height;
        if (rider != null) {
            // Where AbstractHorse.updatePassenger would actually put the rider once it lands there.
            double riderFeet = groundY + horse.getMountedYOffset() + rider.getYOffset();
            top = Math.max(top, riderFeet + rider.height);
        }
        return new AxisAlignedBB(cx - half, groundY, cz - half, cx + half, top, cz + half);
    }

    /**
     * True while a mounted rider's horse is pressed against something and going nowhere despite its
     * rider asking to move forward. The single definition of "wedged", shared by the sidestep below
     * and by the follow AI - whose own obstacle probes are all sized from the rider's narrow hitbox
     * and are therefore blind to anything only the horse collides with.
     */
    public static boolean isMountWedged(EntityTraveller rider) {
        if (!rider.isMountedOnBondedMount()) return false;
        Entity ride = rider.getRidingEntity();
        if (ride == null) return false;
        if (!ride.collidedHorizontally || rider.moveForward <= 0.02F) return false;
        double dx = ride.posX - ride.prevPosX;
        double dz = ride.posZ - ride.prevPosZ;
        return dx * dx + dz * dz <= 0.0016D;
    }

    /** Never leave the attribute at zero: a horse with no move speed cannot be led or recalled
     * either, and the rider simply commands moveForward = 0 when it wants to stand still anyway. */
    private static final double MIN_MOUNTED_SPEED = 0.05D;

    /**
     * Mounted ground speed is a <b>product</b>, and getting that wrong is where every past
     * "much too fast" / "no faster than walking" report came from.
     *
     * <p>{@code EntityMoveHelper} sets an AI mob's speed as {@code speed * MOVEMENT_SPEED}, and
     * {@code EntityLiving.setAIMoveSpeed} writes that same value into {@code moveForward}. So an
     * unmounted traveller's per-tick acceleration is {@code moveForward x its own AI move speed} -
     * the attribute appears twice. {@code AbstractHorse.travel()} then feeds the rider's
     * {@code moveForward} into {@code EntityLivingBase.travel()} but supplies the second factor from
     * the horse's <em>raw</em> attribute ({@code setAIMoveSpeed(getAttributeValue(MOVEMENT_SPEED))}),
     * with none of the per-task multiplier applied. Copying the mod's base walking speed onto the
     * horse therefore did not reproduce walking speed at all - it reproduced it divided by whatever
     * multiplier the current AI task happened to use.
     *
     * <p>Mirroring the rider's own current AI move speed makes the two products identical, so a
     * mounted traveller moves at exactly its on-foot speed for every task, and the multiplier
     * becomes the single honest knob on top of that. Two knobs, by context: a rider crossing the
     * world alone ({@code EntityTraveller.mountState.isTravelSpeedContext()}, maintained by EntityAITravel)
     * rides at the open-road pace, everything else (formation follow, combat, idle) at the follow
     * pace - one shared multiplier made followers speed up whenever the travel pace was raised.
     */
    private static void applyMountedSpeed(EntityTraveller rider, EntityHorse horse) {
        // Workers answer "am I travelling" through their own per-tick flag rather than the travel
        // task's lifecycle: shouldBeUsingTravelAI() reasserts it every tick across missions, go
        // orders and jobs, including the documented worker trap where the travel TASK itself is
        // yanked on/off tick to tick while the mission continues - a lifecycle-only flag left
        // mission-riding workers at the follow pace.
        boolean travelling = rider.mountState.isTravelSpeedContext()
                || (rider instanceof EntityTravellerWorker && ((EntityTravellerWorker) rider).isUsingTravelAI());
        double mult = travelling
                ? TravellersConfig.MOUNTS.mountedTravelSpeedMultiplier
                : TravellersConfig.MOUNTS.mountedFollowSpeedMultiplier;
        double target = Math.max(MIN_MOUNTED_SPEED, rider.getAIMoveSpeed() * mult);
        net.minecraft.entity.ai.attributes.IAttributeInstance attr =
                horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED);
        // Only write on a real change: setBaseValue flags the attribute for a client resync, and
        // this runs every tick for every mounted rider.
        if (Math.abs(attr.getBaseValue() - target) > 1.0E-4D) {
            attr.setBaseValue(target);
        }
    }

    /**
     * Captures a bonded mount's full NBT (identity, tame/saddle, attributes, age, color) and removes
     * it from the world, so a rider going virtual takes its horse with it instead of leaving it
     * standing in a chunk nobody will visit again.
     *
     * <p><b>Works whether the horse is being ridden, led or hitched</b> - it is found by UUID when the
     * rider is on foot. Only capturing a ridden one is what used to strand a camping rider's parked
     * horse in the world permanently, with {@code bondNewMount} then refusing to ever give that rider
     * another one. Dismounts first when ridden: {@code Entity.writeToNBT} serializes the passenger
     * list recursively, and capturing while still mounted would embed a full duplicate copy of the
     * rider's own NBT inside the horse's record, which a later {@link #restoreMount} would then spawn
     * as a second, unbonded rider. Returns {@code null} if there is no bonded horse to capture.
     *
     */
    @Nullable
    public static NBTTagCompound captureMount(EntityTraveller rider) {
        Entity ride = null;
        if (rider.isMountedOnBondedMount()) {
            ride = rider.getRidingEntity();
        } else {
            // A bonded horse standing next to its rider (led or hitched) must be captured too -
            // otherwise virtualizing an on-foot rider leaves its horse behind in the world with
            // nobody to reclaim it, and bondNewMount refuses to ever give that rider another one.
            UUID mountUUID = rider.getMountUUID();
            if (mountUUID != null && rider.world instanceof WorldServer) {
                Entity found = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
                if (found instanceof EntityHorse && !found.isDead) ride = found;
            }
        }
        if (ride == null) return null;

        // `!isDead` alone reads a horse mid-death-animation (health already <= 0, vanilla's ~20-tick
        // delay before it is actually removed - see HorseEvents.onLivingDeath's doc) as "present and capturable".
        // Writing its NBT and hard-`setDead()`-ing it here would silently finish the removal WITHOUT
        // ever going through onDeath()/LivingDeathEvent, so onBondedMountDied never runs: the bond
        // stays set and the captured NBT is a corpse snapshot that a later restore would resurrect,
        // dying again the same way - the exact loop this class exists to prevent. Route through the
        // one real "this bond is over" handler instead of inventing a second copy of it here.
        if (ride instanceof EntityHorse && ((EntityHorse) ride).getHealth() <= 0.0F) {
            onBondedMountDied((EntityHorse) ride);
            return null;
        }

        if (rider.isMountedOnBondedMount()) rider.dismountRidingEntity();
        if (ride instanceof EntityHorse) setLeashed((EntityHorse) ride, null);
        NBTTagCompound mountNbt = HorseSnapshots.capture((EntityHorse) ride);
        ride.setDead();
        return mountNbt;
    }

    /**
     * Symmetric counterpart to {@link #captureMount}: restores the exact same horse (same UUID, same
     * tame/saddle/attributes/age/color) from previously-captured NBT next to {@code rider} and
     * re-mounts it. Returns {@code false} (and clears the rider's {@code mountUUID}, since it would
     * otherwise dangle forever - see {@link #bondNewMount}) if the horse fails to spawn, e.g.
     * cancelled by another mod's claim/protection system.
     */
    public static boolean restoreMount(EntityTraveller rider, NBTTagCompound mountNbt) {
        return spawnMountFromNbt(rider, mountNbt, null) != null;
    }

    /**
     * Rebuilds a bonded horse from stored NBT beside its rider and leaves {@link #tickMount} to put
     * the pair back into whatever {@link MountMode} the rider is in - the same single transition path
     * a mode change goes through, so a restore can't produce a state no other code path can.
     * A rider whose horse was parked outside its camp shelter ({@link #resolveMountSpawnPos}) is
     * switched to {@link MountMode#HITCHED} rather than being yanked out of its shelter onto it.
     * Returns {@code null} (and clears the bond, which would otherwise dangle forever - see
     * {@link #bondNewMount}) if the horse fails to spawn, e.g. cancelled by another mod.
     *
     * <p>The single choke point for every caller that rebuilds a horse from stored NBT
     * ({@link #resolveMount}'s "went missing without dying" rebuild, {@link #restoreMount}'s
     * chunk-unload/materialize restore) - refusing a corpse snapshot here covers both instead of
     * needing the same check at each call site. A corpse snapshot comes from a capture during vanilla's death animation.
     */
    @Nullable
    private static EntityHorse spawnMountFromNbt(EntityTraveller rider, NBTTagCompound mountNbt, @Nullable BlockPos parkAt) {
        World world = rider.world;
        if (!(world instanceof WorldServer)) return null;

        if (HorseSnapshots.isCorpse(mountNbt)) {
            rider.setMountUUID(null);
            rider.mountState.setMountSnapshotNbt(null);
            return null;
        }

        EntityHorse horse = new EntityHorse(world);
        horse.readFromNBT(mountNbt);
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID != null) horse.setUniqueId(mountUUID);
        MountSpawnPos pos = parkAt != null ? parkedSpawnPos((WorldServer) world, horse, parkAt) : resolveMountSpawnPos(rider, horse);
        horse.setLocationAndAngles(pos.x, pos.y, pos.z, rider.rotationYaw, 0.0F);
        horse.motionX = 0;
        horse.motionY = 0;
        horse.motionZ = 0;
        stampNewGeneration(rider, horse);
        applyMountedGroundSpeed(horse);
        applyBondInvariants(horse);

        // Linked before the spawn (a no-op for the ordinary restore, load-bearing when the caller
        // cleared mountUUID to avoid a same-tick UUID collision, see handleBondedHorseChunkUnload):
        // the join check (MountBondLoadHandler) must see the rider claim this exact horse.
        rider.setMountUUID(horse.getUniqueID());
        if (!world.spawnEntity(horse)) {
            rider.setMountUUID(null);
            return null;
        }

        if (pos.parked && pos.camping && rider.mountState.getMountMode() == MountMode.MOUNTED) {
            rider.mountState.setMountModeRaw(MountMode.HITCHED);
        } else if (!pos.parked && rider.mountState.getMountMode() == MountMode.MOUNTED
                && horse.getDistanceSq(rider) <= 4.0D
                && rider.onGround && !rider.isInWater()
                && horse.onGround && !horse.isInWater()) {
            // The horse fit right where the rider stands (a materialize / restore resolved the pair
            // to horse-sized ground): climb on now instead of a tick of "rider on foot beside its
            // horse" while reconcileOnFoot's sustained remount gate counts up.
            setLeashed(horse, null);
            rider.startRiding(horse, true);
            // Seated now, not on the horse's next tick: a horse on non-ticking ground never moves its rider,
            // and the pair would be saved split across a chunk border.
            horse.updatePassenger(rider);
        }
        return horse;
    }
}
