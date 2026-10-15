package com.jubitus.travellers.mount;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.unit.EntityTraveller;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
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
 *
 * <p>The work lives in {@link MountBonding}, {@link MountRiding}, {@link LedHorseDriver}, {@link MountModes},
 * {@link MountLifecycle} and {@link MountPersistence}; this class keeps {@link #tickMount}, the leash and the
 * public entry points other packages call.
 */
public final class TravellerMountSupport {

    private TravellerMountSupport() {
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
            MountPersistence.tickParkedMount(traveller);
            return;
        }

        EntityHorse horse = MountPersistence.resolveMount(traveller);
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

        MountPersistence.refreshMountSnapshot(traveller, horse);

        // Self-heal the AI strip. A log named the culprit: another mod adds an EntityAIAvoidEntity to
        // horses on EntityJoinWorldEvent, after applyBondInvariants has already run, and that is a
        // pathing task - it would give the horse its own navigation to fight the steering.
        // The size test is O(1), so this costs nothing on the overwhelming majority of ticks and
        // survives whatever any mod adds, whenever it adds it.
        if (horse.tasks.taskEntries.size() != 1) MountBonding.applyBondInvariants(horse);

        // A badly wounded mount gets led rather than ridden, in or out of combat - checked before
        // nowMounted below so a rider currently in the saddle actually dismounts this same tick, not
        // next time reconcileOnFoot happens to run. Armed with markTerrainFallbackLed immediately so
        // the LED case's own "orphaned LED" self-heal (below) doesn't read this as untracked and
        // instantly flip it back to MOUNTED with the horse standing right there.
        if (traveller.mountState.getMountMode() != MountMode.LED && traveller.mountState.getMountMode() != MountMode.HITCHED
                && MountModes.isMountTooHurtToRide(horse)) {
            MountModes.enterFallbackLed(traveller, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.LOW_HEALTH,
                    com.jubitus.travellers.notify.NotificationDefs.MOUNT_LED_LOW_HEALTH);
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
            MountModes.tryHitchAtVillageEdge(traveller, horse);
        }

        if (traveller.mountState.getMountMode() != MountMode.LED && traveller.mountState.getMountMode() != MountMode.HITCHED
                && MountModes.isNearVillageRequiringLed(traveller)) {
            MountModes.enterFallbackLed(traveller, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason.VILLAGE, null);
        }

        boolean nowMounted = traveller.isMountedOnBondedMount();

        if (!nowMounted) {
            MountModes.reconcileOnFoot(traveller, horse);
            return;
        }

        // Vanilla only ejects a rider once its OWN head goes underwater
        // (EntityLivingBase.onEntityUpdate -> shouldDismountInWater), which in practice means staying
        // in the saddle of a horse that is already swimming - reported as "he didn't get off until the
        // horse went under". Step off as soon as the horse itself is swimming instead. No mode change
        // is needed or wanted: the MOUNTED-on-foot branch of reconcileOnFoot already leashes the horse
        // and tows it, and canRemountHere puts the rider back once both are out of the water and the
        // horse is on solid ground.
        if (MountModes.isSwimming(horse)) {
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

        MountRiding.applyMountedSpeed(traveller, horse);
        MountRiding.releaseJumpYawOnLanding(traveller, horse);
        MountRiding.tickMountedGapJump(traveller, horse);
        MountRiding.tickMountedJump(traveller, horse);
        MountRiding.trySidestepIfWedged(traveller, horse);
        logHorseDiagnostic(traveller, horse);
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

    // --- Public entry points (implemented in the mount package classes) ---

    /** See {@link MountBonding#bondNewMount}. */
    public static boolean bondNewMount(EntityTraveller traveller) {
        return MountBonding.bondNewMount(traveller);
    }

    /** See {@link MountBonding#bondSpecificMount}. */
    public static boolean bondSpecificMount(EntityTraveller rider, NBTTagCompound horseNbt) {
        return MountBonding.bondSpecificMount(rider, horseNbt);
    }

    /** See {@link MountBonding#bondToLiveHorse}. */
    public static boolean bondToLiveHorse(EntityTraveller rider, EntityHorse horse) {
        return MountBonding.bondToLiveHorse(rider, horse);
    }

    /** See {@link MountBonding#rollGroupMount}. */
    public static boolean rollGroupMount(java.util.Random rand, String entityClass) {
        return MountBonding.rollGroupMount(rand, entityClass);
    }

    /** See {@link MountBonding#applyMountedGroundSpeed}. */
    public static void applyMountedGroundSpeed(EntityHorse horse) {
        MountBonding.applyMountedGroundSpeed(horse);
    }

    /** See {@link MountBonding#applyBondInvariants}. */
    public static void applyBondInvariants(EntityHorse horse) {
        MountBonding.applyBondInvariants(horse);
    }

    /** See {@link MountRiding#jumpHeightOf}. */
    public static double jumpHeightOf(EntityHorse horse) {
        return MountRiding.jumpHeightOf(horse);
    }

    /** See {@link MountRiding#requiredJumpVelocity}. */
    public static double requiredJumpVelocity(double targetRise, double maxStrength) {
        return MountRiding.requiredJumpVelocity(targetRise, maxStrength);
    }

    /** See {@link MountRiding#isMountWedged}. */
    public static boolean isMountWedged(EntityTraveller rider) {
        return MountRiding.isMountWedged(rider);
    }

    /** See {@link MountModes#setMountMode}. */
    public static void setMountMode(EntityTraveller rider, MountMode mode) {
        MountModes.setMountMode(rider, mode);
    }

    /** See {@link MountLifecycle#dropBondFromHorse}. */
    public static void dropBondFromHorse(EntityHorse horse) {
        MountLifecycle.dropBondFromHorse(horse);
    }

    /** See {@link MountLifecycle#releaseLiveMount}. */
    @Nullable
    public static EntityHorse releaseLiveMount(EntityTraveller rider) {
        return MountLifecycle.releaseLiveMount(rider);
    }

    /** See {@link MountLifecycle#releaseMountOnRiderDeath}. */
    public static void releaseMountOnRiderDeath(EntityTraveller rider) {
        MountLifecycle.releaseMountOnRiderDeath(rider);
    }

    /** See {@link MountLifecycle#releaseHorseOfGoneRider}. */
    public static void releaseHorseOfGoneRider(WorldServer world, UUID horseId) {
        MountLifecycle.releaseHorseOfGoneRider(world, horseId);
    }

    /** See {@link MountLifecycle#detachAndKillMountForDebugWipe}. */
    public static void detachAndKillMountForDebugWipe(EntityTraveller rider) {
        MountLifecycle.detachAndKillMountForDebugWipe(rider);
    }

    /** See {@link MountLifecycle#handleBondedHorseChunkUnload}. */
    public static void handleBondedHorseChunkUnload(WorldServer world, EntityHorse horse) {
        MountLifecycle.handleBondedHorseChunkUnload(world, horse);
    }

    /** See {@link MountLifecycle#onBondedMountDied}. */
    public static void onBondedMountDied(EntityHorse horse) {
        MountLifecycle.onBondedMountDied(horse);
    }

    /** See {@link MountPersistence#captureMount}. */
    @Nullable
    public static NBTTagCompound captureMount(EntityTraveller rider) {
        return MountPersistence.captureMount(rider);
    }

    /** See {@link MountPersistence#restoreMount}. */
    public static boolean restoreMount(EntityTraveller rider, NBTTagCompound mountNbt) {
        return MountPersistence.restoreMount(rider, mountNbt);
    }

    /** See {@link MountModes#enterFallbackLed}. */
    public static void enterFallbackLed(EntityTraveller rider, com.jubitus.travellers.debug.profile.MovementAnomaly.LedReason reason,
                                        @Nullable com.jubitus.travellers.notify.NotificationDef notice) {
        MountModes.enterFallbackLed(rider, reason, notice);
    }
}
