package com.jubitus.travellers.entity.entityAI.travel.mount;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.entity.travellers.EntityTraveller;
import com.jubitus.travellers.entity.travellers.EntityTravellerRaider;
import com.jubitus.travellers.entity.travellers.EntityTravellerWorker;
import com.jubitus.travellers.utils.world.camp.CampOccupant;
import com.jubitus.travellers.utils.world.camp.CampsiteManager;
import com.jubitus.travellers.utils.world.pathing.GapJumpEvaluator;
import com.jubitus.travellers.utils.world.pathing.GapJumpResult;
import com.jubitus.travellers.utils.world.pathing.offscreen.MaterializePositionResolver;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.entity.passive.EntityHorse;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
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
 * <p>Steering needs no custom code: while a traveller rides an {@link EntityHorse}, vanilla already
 * reads the rider's own {@code moveForward}/{@code moveStrafing}/{@code rotationYaw} - the exact
 * fields this mod's own AI tasks and {@code TravellerNavigateGround} already compute every tick - and
 * applies them using the horse's own speed/jump attributes ({@code AbstractHorse.travel()},
 * {@code canBeSteered()} accepts any {@code EntityLivingBase}, not just a player). This mirrors the
 * elytra flight controller's "physics stays vanilla, we only steer" approach. The one thing vanilla
 * never does for a non-player rider is jump: {@code AbstractHorse.setJumpPower(int)} is
 * {@code @SideOnly(CLIENT)} (client input charging a jump bar, sent to the server by a packet) and
 * must never be called from server-side AI - {@link #tickMount} instead applies the same
 * vertical/horizontal impulse {@code travel()} would, directly, exactly like this mod's own
 * pedestrian gap-jump ({@code TravellerNavigateGround.commitJump}) already does for the rider on foot.
 */
public final class TravellerMountSupport {

    private TravellerMountSupport() {
    }

    private static final String NBT_BONDED_OWNER = "TravellersMountOwner";

    public static boolean isBondedMount(Entity e) {
        return e instanceof EntityHorse && e.getEntityData().hasUniqueId(NBT_BONDED_OWNER);
    }

    @Nullable
    public static UUID getBondedOwner(Entity e) {
        if (!(e instanceof EntityHorse)) return null;
        NBTTagCompound data = e.getEntityData();
        return data.hasUniqueId(NBT_BONDED_OWNER) ? data.getUniqueId(NBT_BONDED_OWNER) : null;
    }

    /**
     * Spawns a fresh horse next to the traveller and bonds it, ignoring the config chance roll -
     * used by the debug command. Returns false if mounts are disabled or the traveller already has
     * one.
     */
    public static boolean bondNewMount(EntityTraveller traveller) {
        if (!TravellersConfig.MOUNTS.enabled) return false;
        if (traveller.getMountUUID() != null) return false;
        World world = traveller.world;
        if (!(world instanceof WorldServer)) return false;

        MountSpawnPos pos = resolveMountSpawnPos(traveller);
        EntityHorse horse = new EntityHorse(world);
        horse.setLocationAndAngles(pos.x, pos.y, pos.z, traveller.rotationYaw, 0.0F);
        horse.onInitialSpawn(world.getDifficultyForLocation(new BlockPos(traveller)), null);
        horse.setGrowingAge(0);
        horse.setHorseTamed(true);
        horse.getEntityData().setUniqueId(NBT_BONDED_OWNER, traveller.getUniqueID());
        applyMountedGroundSpeed(horse);
        applyBondInvariants(horse);

        if (!world.spawnEntity(horse)) return false;

        traveller.setMountUUID(horse.getUniqueID());
        if (pos.parked) {
            traveller.setMountModeRaw(MountMode.HITCHED);
        } else {
            traveller.setMountModeRaw(MountMode.MOUNTED);
            traveller.startRiding(horse, true);
        }
        return true;
    }

    /** Where a mount should appear next to its rider, and whether it should stay standing there
     * rather than being auto-mounted. */
    private static final class MountSpawnPos {
        final double x, y, z;
        /** True when this spot was deliberately picked away from the rider (camping) - the rider must
         * stay in its shelter, not get yanked onto the horse the instant it spawns. */
        final boolean parked;

        MountSpawnPos(double x, double y, double z, boolean parked) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parked = parked;
        }
    }

    /** How far (in blocks) {@link #resolveMountSpawnPos} is willing to look for a spot outside a
     * camping rider's shelter before giving up and falling back to the rider's own position. */
    private static final int MOUNT_SPOT_SEARCH_RADIUS = 6;

    /**
     * Normally a mount just appears exactly where its rider is. The one exception is a rider currently
     * camping with an active shelter footprint ({@link CampOccupant#getShelterBounds()}): naively
     * co-locating there is exactly what caused a materializing horse to appear embedded inside a
     * camp's tent walls. In that case, search for a clear standing spot just outside the shelter
     * instead, and flag the result as "parked" so the caller knows not to auto-mount the rider onto it
     * (that would yank a sleeping/camping traveller out of its shelter the instant the horse spawns).
     * Falls back to the rider's own position if no such spot is found nearby, or if the rider isn't
     * camping at all - never blocks the mount from appearing.
     */
    private static MountSpawnPos resolveMountSpawnPos(EntityTraveller rider) {
        MountSpawnPos atRider = new MountSpawnPos(rider.posX, rider.posY, rider.posZ, false);
        if (!(rider.world instanceof WorldServer)) return atRider;
        WorldServer world = (WorldServer) rider.world;

        CampOccupant occ = CampsiteManager.get(world).findOccupant(rider.getUniqueID());
        AxisAlignedBB shelterBounds = occ != null ? occ.getShelterBounds() : null;
        if (shelterBounds == null) return atRider;

        BlockPos spot = MaterializePositionResolver.findClearMountSpot(
                world, rider.posX, rider.posY, rider.posZ, shelterBounds, MOUNT_SPOT_SEARCH_RADIUS);
        if (spot == null) return atRider;
        return new MountSpawnPos(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, true);
    }

    /**
     * Rolls the per-type config chance and bonds a mount on success. Called once per fresh spawn.
     *
     * <p><b>Not yet wired into the real population/group spawn path</b> (deliberately - v1 scope is
     * pathfinding only, see TravellerMountSupport class doc). When it is: the roll must happen once
     * per <em>group</em>, not once per member, and on success every member of that group gets a
     * mount - a leader/follower group with only some of its riders mounted looks broken and would
     * also desync formation slot speed (mounted vs on-foot members can't keep pace with each other).
     */
    public static void maybeBondMount(EntityTraveller traveller) {
        if (!TravellersConfig.MOUNTS.enabled) return;
        if (traveller.getMountUUID() != null) return;
        int chance = chanceFor(traveller);
        if (chance <= 0 || traveller.world.rand.nextInt(100) >= chance) return;
        bondNewMount(traveller);
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
            traveller.setWasRidingBondedMountLastTick(false);
            return;
        }

        EntityHorse horse = resolveMount(traveller);
        boolean nowMounted = traveller.isMountedOnBondedMount();
        traveller.setWasRidingBondedMountLastTick(nowMounted);
        if (horse == null) return;

        refreshMountSnapshot(traveller, horse);

        // Self-heal the AI strip. A log named the culprit: another mod adds an EntityAIAvoidEntity to
        // horses on EntityJoinWorldEvent, after applyBondInvariants has already run, and that is a
        // pathing task - it would give the horse its own navigation to fight the steering.
        // The size test is O(1), so this costs nothing on the overwhelming majority of ticks and
        // survives whatever any mod adds, whenever it adds it.
        if (horse.tasks.taskEntries.size() != 1) applyBondInvariants(horse);

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
            traveller.setMountSpeedRamp(0.0f);
            return;
        }

        traveller.setRemountTicks(0);
        setLeashed(horse, null);

        // The horse walks itself now (see MixinAbstractHorse): the rider's AI hands its destination
        // to the horse's navigator through EntityTraveller.getNavigator(), and everything vanilla
        // sizes from "the entity asking for a path" is finally sized from the body that has to fit.
        // The rider's own navigator and move helper must be kept quiet, and its body turned to match
        // the horse, since travel() no longer copies the rider's yaw onto it.
        traveller.suspendOwnMovement();
        traveller.renderYawOffset = horse.rotationYaw;
        traveller.prevRenderYawOffset = horse.rotationYaw;

        tickSpeedRamp(traveller, horse);
        logHorseDiagnostic(traveller, horse);
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
            rider.setMountMissingTicks(0);
            return (EntityHorse) found;
        }

        int missing = rider.getMountMissingTicks() + 1;
        rider.setMountMissingTicks(missing);
        if (missing < MOUNT_MISSING_GRACE_TICKS) return null;
        rider.setMountMissingTicks(0);

        NBTTagCompound snapshot = rider.getMountSnapshotNbt();
        if (snapshot == null || !TravellersConfig.MOUNTS.restoreLostMounts) {
            rider.setMountUUID(null);
            return null;
        }

        FMLLog.log(Level.INFO, "[Travellers/Mounts] %s's bonded horse went missing without dying - rebuilding it",
                rider.getName());
        return spawnMountFromNbt(rider, snapshot);
    }

    /** Keeps the rebuild-if-lost snapshot current (health, gear, growth) without paying for a full
     * NBT write every tick. */
    private static void refreshMountSnapshot(EntityTraveller rider, EntityHorse horse) {
        if (rider.ticksExisted % 200 != 0 && rider.getMountSnapshotNbt() != null) return;
        NBTTagCompound snapshot = new NBTTagCompound();
        // Dismounted first for the same reason captureMount does it: writeToNBT serializes the
        // passenger list recursively, and a snapshot taken while ridden would carry a full duplicate
        // copy of the rider inside it, which a later rebuild would spawn as a second traveller.
        if (horse.isBeingRidden()) {
            NBTTagCompound riderless = new NBTTagCompound();
            horse.writeToNBT(riderless);
            riderless.removeTag("Passengers");
            snapshot = riderless;
        } else {
            horse.writeToNBT(snapshot);
        }
        rider.setMountSnapshotNbt(snapshot);
    }

    /**
     * Everything that happens while the rider is on foot with its horse still bonded. All three cases
     * end in the same guarantee - the pair stays together - and differ only in how:
     * MOUNTED wants back in the saddle, LED walks the horse along on a leash, HITCHED leaves it be.
     */
    private static void reconcileOnFoot(EntityTraveller rider, EntityHorse horse) {
        double distSq = horse.getDistanceSq(rider);
        double leadMax = TravellersConfig.MOUNTS.leadMaxDistance;

        switch (rider.getMountMode()) {
            case HITCHED:
                setLeashed(horse, null);
                // Standing where it was left is the whole point, so it is only ever recalled when
                // the rider has gone so far that the pair would otherwise be separated for good.
                if (distSq > (leadMax * 2.0D) * (leadMax * 2.0D)) teleportMountNear(rider, horse);
                return;

            case LED:
                setLeashed(horse, rider);
                driveLedHorse(rider, horse, distSq);
                if (distSq > leadMax * leadMax) teleportMountNear(rider, horse);
                return;

            case MOUNTED:
            default:
                // Wants to ride but isn't: either it just got off in water, or its horse is waiting
                // somewhere it was hitched. Leash it so it walks over on its own (vanilla's own leash
                // following, no custom AI) and climb on as soon as it's close and on solid footing.
                setLeashed(horse, rider);
                driveLedHorse(rider, horse, distSq);

                double remountDist = TravellersConfig.MOUNTS.remountDistance;
                if (distSq <= remountDist * remountDist && canRemountHere(rider, horse)) {
                    rider.setRemountTicks(0);
                    setLeashed(horse, null);
                    rider.startRiding(horse, true);
                    return;
                }

                // The timeout only means "the horse can't get here by itself" - a horse already
                // standing (or swimming) right next to a rider that simply can't mount yet is not
                // stuck, and teleporting it on the spot would be pure noise.
                int waited = rider.getRemountTicks() + 1;
                rider.setRemountTicks(waited);
                boolean horseIsFarAway = distSq > 8.0D * 8.0D;
                if ((waited > TravellersConfig.MOUNTS.remountTimeoutTicks && horseIsFarAway)
                        || distSq > leadMax * leadMax) {
                    rider.setRemountTicks(0);
                    teleportMountNear(rider, horse);
                }
        }
    }

    /** Past this, a led horse is falling behind rather than walking alongside, and is allowed to
     * trot to catch up. Matches the distance vanilla's own leash handling stops walking the animal
     * to its holder and starts merely dragging it. */
    private static final double LEAD_CATCHUP_DISTANCE = 6.0D;

    /**
     * Keeps a led horse actually next to its rider. Vanilla's leash handling
     * ({@code EntityCreature.updateLeashedState}) only walks the animal to its holder under 6 blocks
     * and, past that, does nothing but drag it in a straight line - which snags on the first tree or
     * wall. So beyond that range this steers the horse the way anything else in this mod moves:
     * through its navigator, at a speed that lets it close a gap instead of trailing forever at
     * exactly its rider's pace.
     *
     * <p>The straight-line pull is still applied past 10 blocks, where vanilla would have snapped the
     * leash instead (see {@code MixinEntityLiving}) - that is what tows a horse behind a swimming
     * rider or a boat, where there is no ground to path along at all.
     */
    private static void driveLedHorse(EntityTraveller rider, EntityHorse horse, double distSq) {
        boolean behind = distSq > LEAD_CATCHUP_DISTANCE * LEAD_CATCHUP_DISTANCE;

        double base = TravellersConfig.SPEED.movementSpeed;
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(
                behind ? base * TravellersConfig.MOUNTS.mountedGroundSpeedMultiplier : base);

        if (behind && rider.ticksExisted % 10 == 0) {
            horse.getNavigator().tryMoveToEntityLiving(rider, 1.0D);
        }

        if (distSq <= 10.0D * 10.0D) return;
        double dist = Math.sqrt(distSq);
        double dx = (rider.posX - horse.posX) / dist;
        double dy = (rider.posY - horse.posY) / dist;
        double dz = (rider.posZ - horse.posZ) / dist;
        horse.motionX += dx * Math.abs(dx) * 0.4D;
        horse.motionY += dy * Math.abs(dy) * 0.4D;
        horse.motionZ += dz * Math.abs(dz) * 0.4D;
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

    /**
     * Climbing back on is refused while either of them is swimming: vanilla ejects a rider whose head
     * goes underwater ({@code EntityLivingBase.onEntityUpdate}), so remounting there would mount and
     * eject on alternating ticks forever. Waiting for solid ground is also simply what the rider
     * would do - it fords the water on foot with the horse in tow and gets back on once across.
     */
    private static boolean canRemountHere(EntityTraveller rider, EntityHorse horse) {
        if (rider.isRiding()) return false; // already in something else (a boat crossing)
        if (rider.isInsideOfMaterial(Material.WATER)) return false;
        return horse.onGround && !horse.isInsideOfMaterial(Material.WATER);
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
    private static void teleportMountNear(EntityTraveller rider, EntityHorse horse) {
        MountSpawnPos pos = resolveMountSpawnPos(rider);
        if (TravellersConfig.MOUNTS.debugMounts) {
            FMLLog.log(Level.INFO, "[Travellers/Mounts] %s recalling its horse from %.1f blocks away (mode=%s)",
                    rider.getName(), Math.sqrt(horse.getDistanceSq(rider)), rider.getMountMode());
        }
        com.jubitus.travellers.utils.debug.trace.TravellerTracer.event(rider, "mount",
                String.format(java.util.Locale.ROOT, "recall horse from %.1f blocks (mode=%s)",
                        Math.sqrt(horse.getDistanceSq(rider)), rider.getMountMode()));
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
     * Sets what the rider wants to do with its horse. The transition itself (getting off onto solid
     * ground, leashing, climbing back on) is left to {@link #tickMount}, which runs every tick and
     * can wait for the right moment - a mode change asked for mid-river or mid-jump would otherwise
     * have to either fail or drop the rider somewhere silly.
     */
    public static void setMountMode(EntityTraveller rider, MountMode mode) {
        if (rider.getMountMode() == mode) return;
        if (TravellersConfig.MOUNTS.debugMounts) {
            FMLLog.log(Level.INFO, "[Travellers/Mounts] %s mount mode %s -> %s (riding=%b)",
                    rider.getName(), rider.getMountMode(), mode, rider.isMountedOnBondedMount());
        }
        com.jubitus.travellers.utils.debug.trace.TravellerTracer.event(rider, "mount",
                "mode " + rider.getMountMode() + " -> " + mode);
        rider.setMountModeRaw(mode);
        rider.setRemountTicks(0);
        if (mode != MountMode.MOUNTED && rider.isMountedOnBondedMount()) {
            // Vanilla already places a dismounting rider beside a horse rather than inside it
            // (EntityLivingBase.dismountEntity has a dedicated AbstractHorse branch that steps off to
            // the side and lifts out of any block it would land in).
            rider.dismountRidingEntity();
        }
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
        Entity mount = ((WorldServer) rider.world).getEntityFromUuid(mountUUID);
        rider.setMountUUID(null);
        rider.setMountSnapshotNbt(null);
        if (!(mount instanceof EntityHorse) || mount.isDead) return;

        EntityHorse horse = (EntityHorse) mount;
        setLeashed(horse, null);
        horse.getEntityData().removeTag(NBT_BONDED_OWNER + "Most");
        horse.getEntityData().removeTag(NBT_BONDED_OWNER + "Least");
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
                .setBaseValue(DEFAULT_HORSE_SPEED);
    }

    /** Vanilla's mid-range horse speed roll, used when a horse stops being a bonded mount. */
    private static final double DEFAULT_HORSE_SPEED = 0.225D;

    /**
     * The one event that genuinely ends a bond. Everything else - a missing horse, an unloaded chunk,
     * a rider on foot - is recoverable and must never clear it, or the rider silently loses a
     * companion it could have got back (see {@link #resolveMount}).
     */
    public static void onBondedMountDied(EntityHorse horse) {
        UUID ownerUUID = getBondedOwner(horse);
        if (ownerUUID == null || !(horse.world instanceof WorldServer)) return;
        WorldServer world = (WorldServer) horse.world;

        Entity owner = world.getEntityFromUuid(ownerUUID);
        if (owner instanceof EntityTraveller) {
            EntityTraveller rider = (EntityTraveller) owner;
            rider.setMountUUID(null);
            rider.setMountSnapshotNbt(null);
            rider.setMountModeRaw(MountMode.MOUNTED);
            return;
        }
        // Rider is offscreen: clear the same thing on its virtual record instead, or it would
        // materialize later still expecting a horse that no longer exists.
        com.jubitus.travellers.utils.world.pathing.offscreen.VirtualTravellerManager.get(world)
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
     * <p>Sets the walking baseline here (the ramp-at-rest value {@link #tickSpeedRamp} would compute
     * anyway) rather than jumping straight to full mounted speed - a horse that just spawned/materialized
     * hasn't been moving yet, so there is nothing to "warm up" from. Called at bond time and whenever a
     * captured mount is restored on materialize (see {@code VirtualTravellerManager.restoreOrBondMount}).
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
     * AncientWarfare 2's {@code NpcAIRideHorse} makes for its own mounted NPCs. A bonded mount is a
     * permanent companion here (see the class doc), so there is no unbond path needing them restored.
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

    /** Below this much ground left to cover, a mounted rider gets no speed bonus at all - it is
     * adjusting its position, not going anywhere. */
    private static final double MOUNTED_WALK_DISTANCE = 2.5D;

    /** Past this, the steering target is stale or unset rather than genuinely far - see below. */
    private static final double STALE_MOVE_TARGET_DISTANCE = 128.0D;

    /**
     * How much of the mounted speed bonus the rider's current movement actually justifies, 0..1.
     *
     * <p>The point is that a horse must not gallop for a two-block correction. This used to be a
     * plain "does the navigator have a path" test, which was wrong in both directions: a formation
     * follower is steered straight through its move helper with no navigator path at all, so it never
     * got the bonus it was configured for (measured: the horse's speed attribute sat at the walking
     * baseline for 532 of 565 ticks) - while a traveller on a route got the full multiplier the whole
     * time, including while shuffling into place on arrival.
     *
     * <p>Both cases are covered by asking the same question - how far is there still to go - from
     * whichever of the two steering channels is actually in use. Deliberately O(1): the path's own
     * end point, not a walk of its nodes.
     */
    private static float mountedSpeedDemand(EntityTraveller rider) {
        double remaining;
        net.minecraft.pathfinding.Path path = rider.getNavigator().getPath();
        if (path != null && !path.isFinished()) {
            net.minecraft.pathfinding.PathPoint end = path.getFinalPathPoint();
            remaining = end == null ? 0.0D : horizontalDistance(rider, end.x + 0.5D, end.z + 0.5D);
        } else {
            net.minecraft.entity.ai.EntityMoveHelper moveHelper = rider.getMoveHelper();
            remaining = horizontalDistance(rider, moveHelper.getX(), moveHelper.getZ());
        }

        double full = Math.max(MOUNTED_WALK_DISTANCE + 1.0D, TravellersConfig.MOUNTS.mountedFullSpeedDistance);
        // A move helper that has never been given a target still reads (0,0,0), which from anywhere
        // in the world looks like an enormous distance still to cover. Nothing legitimate is ever
        // that far: a path is bounded by FOLLOW_RANGE and a direct steer target is a few blocks away.
        if (remaining <= MOUNTED_WALK_DISTANCE || remaining > STALE_MOVE_TARGET_DISTANCE) return 0.0f;
        return (float) Math.min(1.0D, (remaining - MOUNTED_WALK_DISTANCE) / (full - MOUNTED_WALK_DISTANCE));
    }

    private static double horizontalDistance(EntityTraveller rider, double x, double z) {
        double dx = x - rider.posX;
        double dz = z - rider.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Real horse riding has no equivalent of this (a player's horse converges on its target velocity
     * within a handful of ticks via vanilla's own friction physics, same as ours would with a flat
     * target) - added because a snappy, un-ramped mounted speed reads as unnatural once the target
     * speed itself is well above vanilla's usual range. Ramps the horse's own MOVEMENT_SPEED attribute
     * between the walking baseline and the full configured mounted speed over
     * {@code mountedAccelerationTicks}, tracked as a simple 0..1 fraction on the rider
     * ({@link EntityTraveller#getMountSpeedRamp()}, transient - a fresh materialize/bond correctly
     * starts back at a stand-still "hasn't warmed up yet" state). Ramps up while the rider's navigator
     * has an active path (the same "is anything actually trying to move me" signal every AI task here
     * already funnels through), down otherwise - so stopping to fight, wait, or arrive settles the
     * horse back down instead of leaving it stuck at gallop speed while standing still.
     */
    private static void tickSpeedRamp(EntityTraveller traveller, EntityHorse horse) {
        float demand = mountedSpeedDemand(traveller);
        int accelTicks = Math.max(1, TravellersConfig.MOUNTS.mountedAccelerationTicks);
        float step = 1.0f / accelTicks;
        float ramp = traveller.getMountSpeedRamp();
        ramp = ramp < demand ? Math.min(demand, ramp + step) : Math.max(demand, ramp - step);
        traveller.setMountSpeedRamp(ramp);

        double base = TravellersConfig.SPEED.movementSpeed;
        double target = base * TravellersConfig.MOUNTS.mountedGroundSpeedMultiplier;
        double value = base + (target - base) * ramp;
        horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).setBaseValue(value);

        // Very spammy (every tick, every mounted rider) - only for diagnosing a reported "much too
        // fast" regression that wasn't reproducible from code reading alone. Logs the actual applied
        // attribute value (not just the config inputs) so a bug in how it's computed/persisted shows
        // up directly instead of being inferred.
        if (TravellersConfig.MOUNTS.debugMounts && traveller.ticksExisted % 20 == 0) {
            double actual = horse.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED).getAttributeValue();
            FMLLog.log(Level.INFO,
                    "[Travellers/Mounts] %s speed ramp=%.2f base=%.4f target=%.4f setValue=%.4f actualAttrValue=%.4f",
                    traveller.getName(), ramp, base, target, value, actual);
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
     * <p>Also resets the rider's speed ramp ({@link EntityTraveller#setMountSpeedRamp}) to zero. That
     * field is only ever zero "for free" on a brand new {@code EntityTraveller} object (a real
     * materialize), never reset otherwise - a boat crossing keeps the exact same rider object alive
     * throughout, so without this it would still read "fully warmed up" (often 1.0, cruise speed)
     * from before boarding. The restored horse itself always starts at the walking baseline (see
     * {@link #restoreMount}), so a stale ramp of 1.0 would apply the full mounted-speed multiplier
     * from tick one - a sudden, teleport-like burst of speed the instant the crossing ends, not an
     * actual desync (this is what a first look would call "erratic movement after leaving a boat").
     */
    @Nullable
    public static NBTTagCompound captureMount(EntityTraveller rider) {
        Entity ride = null;
        if (rider.isMountedOnBondedMount()) {
            ride = rider.getRidingEntity();
            rider.dismountRidingEntity();
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

        if (ride instanceof EntityHorse) setLeashed((EntityHorse) ride, null);
        NBTTagCompound mountNbt = new NBTTagCompound();
        ride.writeToNBT(mountNbt);
        ride.setDead();
        rider.setMountSpeedRamp(0.0f);
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
        return spawnMountFromNbt(rider, mountNbt) != null;
    }

    /**
     * Rebuilds a bonded horse from stored NBT beside its rider and leaves {@link #tickMount} to put
     * the pair back into whatever {@link MountMode} the rider is in - the same single transition path
     * a mode change goes through, so a restore can't produce a state no other code path can.
     * A rider whose horse was parked outside its camp shelter ({@link #resolveMountSpawnPos}) is
     * switched to {@link MountMode#HITCHED} rather than being yanked out of its shelter onto it.
     * Returns {@code null} (and clears the bond, which would otherwise dangle forever - see
     * {@link #bondNewMount}) if the horse fails to spawn, e.g. cancelled by another mod.
     */
    @Nullable
    private static EntityHorse spawnMountFromNbt(EntityTraveller rider, NBTTagCompound mountNbt) {
        World world = rider.world;
        if (!(world instanceof WorldServer)) return null;

        MountSpawnPos pos = resolveMountSpawnPos(rider);
        EntityHorse horse = new EntityHorse(world);
        horse.readFromNBT(mountNbt);
        UUID mountUUID = rider.getMountUUID();
        if (mountUUID != null) horse.setUniqueId(mountUUID);
        horse.setLocationAndAngles(pos.x, pos.y, pos.z, rider.rotationYaw, 0.0F);
        horse.motionX = 0;
        horse.motionY = 0;
        horse.motionZ = 0;
        applyMountedGroundSpeed(horse);
        applyBondInvariants(horse);

        if (!world.spawnEntity(horse)) {
            rider.setMountUUID(null);
            return null;
        }

        if (pos.parked && rider.getMountMode() == MountMode.MOUNTED) {
            rider.setMountModeRaw(MountMode.HITCHED);
        }
        return horse;
    }
}
