package com.jubitus.travellers.unit;

import com.jubitus.travellers.mount.MountMode;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import javax.annotation.Nullable;

/**
 * A rider's runtime state around its bonded horse (the bond itself is {@code EntityTraveller.getMountUUID}).
 * Read and written by {@code TravellerMountSupport} and the movement code; only the mode, the
 * generation and the held horse data are persisted (by the unit's own NBT).
 */
public final class TravellerMountState {

    private final EntityTraveller unit;

    TravellerMountState(EntityTraveller unit) {
        this.unit = unit;
    }

    private int mountJumpCooldownTicks;

    // The bonded horse held as data (tied at an unloaded post) - see getPendingMountNbt().
    private NBTTagCompound pendingMountNbt;

    // What this rider currently wants to do with its bonded horse (ride it, lead it, leave it
    // standing). Persisted: an AI task that put the horse down must find it in the same state after a
    // reload, or the reconciler would silently re-mount a rider its own AI had deliberately put on
    // foot. Server-only - the client derives everything it needs from isRiding().
    private MountMode mountMode = MountMode.MOUNTED;

    // Last known full NBT of the bonded horse, refreshed while it's around. This is what makes
    // "a rider never loses its horse except by death" actually true rather than nearly true: if the
    // horse ever disappears without dying, it is rebuilt from here, identical, UUID included.
    @Nullable private NBTTagCompound mountSnapshotNbt;

    // Transient reconciler counters (see TravellerMountSupport.tickMount): consecutive ticks the
    // bonded horse could not be found in the world, and ticks spent trying to walk back to it.
    private int mountMissingTicks;
    private int remountTicks;

    // Transient (never persisted): mirrors isMountedOnBondedMount() from the previous tick, purely so
    // TravellerMountSupport.tickMount can detect the exact tick a bonded mount stops being
    // ridden and check, once, whether that was because the horse actually died (clear mountUUID so a
    // future mount roll/GUI assignment isn't blocked by a dangling reference) versus a deliberate
    // dismount that keeps the bond (planned future feature) - never inferred from "not riding" alone.
    private boolean wasRidingBondedMountLastTick;

    public boolean wasRidingBondedMountLastTick() {
        return wasRidingBondedMountLastTick;
    }

    public void setWasRidingBondedMountLastTick(boolean value) {
        wasRidingBondedMountLastTick = value;
    }


    public int getMountJumpCooldownTicks() {
        return this.mountJumpCooldownTicks;
    }

    public void setMountJumpCooldownTicks(int ticks) {
        this.mountJumpCooldownTicks = ticks;
    }

    /** The bonded horse held as data while no live copy exists (tied at a post whose chunk unloaded);
     * written only through {@link #setParkedMount}. Moved into the record when the rider goes offscreen. */
    @Nullable
    public NBTTagCompound getPendingMountNbt() {
        return pendingMountNbt;
    }

    /**
     * Never set this directly from an AI task - go through
     * {@code TravellerMountSupport.setMountMode}, which also performs the transition (mounting,
     * leashing, stepping off onto solid ground) instead of just recording the wish.
     */
    public MountMode getMountMode() {
        return mountMode;
    }

    public void setMountModeRaw(MountMode mode) {
        this.mountMode = mode;
    }

    // Where the horse held as data in pendingMountNbt stands (tied at a village post); null = beside
    // the rider. See TravellerMountSupport.tickParkedMount.
    @Nullable
    private BlockPos pendingMountPos;

    @Nullable
    public BlockPos getPendingMountPos() {
        return pendingMountPos;
    }

    /** Holds the horse as data, parked at {@code pos} (null = with the rider), or clears it. */
    public void setParkedMount(@Nullable NBTTagCompound nbt, @Nullable BlockPos pos) {
        this.pendingMountNbt = nbt;
        this.pendingMountPos = nbt == null ? null : pos;
    }

    // Bumped every time this rider's horse is (re)created from data: any physical copy carrying an older
    // number is a stale twin (see MountBondLoadHandler).
    private int mountGeneration;

    public int getMountGeneration() {
        return mountGeneration;
    }

    public void setMountGeneration(int generation) {
        this.mountGeneration = generation;
    }

    @Nullable
    public NBTTagCompound getMountSnapshotNbt() {
        return mountSnapshotNbt;
    }

    public void setMountSnapshotNbt(@Nullable NBTTagCompound nbt) {
        this.mountSnapshotNbt = nbt;
    }

    public int getMountMissingTicks() {
        return mountMissingTicks;
    }

    public void setMountMissingTicks(int ticks) {
        this.mountMissingTicks = ticks;
    }

    /** True while EntityAITravel is executing - the one discriminator
     * {@code TravellerMountSupport.applyMountedSpeed} needs to pick between the travel and follow
     * mounted speed multipliers (a rider crossing the world alone rides faster than one holding
     * formation). Transient, maintained by EntityAITravel's startExecuting/resetTask. */
    private boolean travelSpeedContext;

    public boolean isTravelSpeedContext() {
        return travelSpeedContext;
    }

    public void setTravelSpeedContext(boolean value) {
        this.travelSpeedContext = value;
    }

    /** Yaw the current mounted jump was launched along, or NaN when not mid-jump. Transient: a jump
     * never outlives a tick boundary that matters. See TravellerMoveHelper. */
    private float mountJumpYaw = Float.NaN;

    public float getMountJumpYaw() {
        return mountJumpYaw;
    }

    public void setMountJumpYaw(float yaw) {
        this.mountJumpYaw = yaw;
    }

    /** Consecutive ticks {@code TravellerMountSupport.isMountWedged} has read true in a row. Transient
     * (never persisted, reset the instant the horse is no longer wedged) - see
     * TravellerMountSupport.trySidestepIfWedged. */
    private int mountWedgedTicks = 0;

    public int getMountWedgedTicks() {
        return mountWedgedTicks;
    }

    public void setMountWedgedTicks(int ticks) {
        this.mountWedgedTicks = ticks;
    }

    /**
     * Yaw captured at the moment the current wedge episode began, {@code NaN} while not wedged.
     * Transient, never persisted.
     *
     * <p>The sidestep and reactive-jump direction used to be recomputed every tick from the horse's
     * live {@code rotationYaw} - but that yaw is itself derived from the bearing to the steering
     * target, which swings noticeably from just the small position jitter a stuck horse still has
     * (a trace caught a ~19 degree swing from under a block of z-jitter). Chasing that live bearing
     * made the escalation reverse itself mid-attempt instead of committing to one side. Freezing the
     * yaw for the whole episode fixes that; see TravellerMountSupport.trySidestepIfWedged.
     */
    private float mountWedgeAnchorYaw = Float.NaN;

    public float getMountWedgeAnchorYaw() {
        return mountWedgeAnchorYaw;
    }

    public void setMountWedgeAnchorYaw(float yaw) {
        this.mountWedgeAnchorYaw = yaw;
    }

    /**
     * Tick {@code TravellerMountSupport} last put this rider on foot for a "the horse genuinely
     * cannot make it through here" reason - {@code -1} while not. Transient, never
     * persisted. Because it doesn't survive a save, {@code writeEntityToNBT} deliberately never
     * persists {@code MountMode.LED} either (it writes {@code MOUNTED}) - otherwise a rider reloaded
     * as LED with this timer reset to {@code -1} would be stuck on foot forever, since the
     * time+distance heal below only runs while the timer is armed.
     *
     * <p>Two separate call sites land in this same fallback ({@code MountMode.LED}): a
     * footprint-blocked route, and giving up on sidesteps against a wedge. Both need the same
     * time+distance self-heal back to {@code MOUNTED}, done once in {@code TravellerMountSupport}'s
     * {@code reconcileOnFoot} rather than duplicated per call site - see
     * {@code TravellerMountSupport.markTerrainFallbackLed}.
     */
    private int ledFallbackSinceTick = -1;
    private double ledFallbackX, ledFallbackZ;

    public int getLedFallbackSinceTick() {
        return ledFallbackSinceTick;
    }

    public void setLedFallbackSinceTick(int tick) {
        this.ledFallbackSinceTick = tick;
    }

    public double getLedFallbackX() {
        return ledFallbackX;
    }

    public double getLedFallbackZ() {
        return ledFallbackZ;
    }

    public void setLedFallbackPos(double x, double z) {
        this.ledFallbackX = x;
        this.ledFallbackZ = z;
    }

    /**
     * Consecutive ticks the dismounted rider and its bonded horse have <em>both</em> been on dry
     * solid ground and close enough to remount (see {@code TravellerMountSupport.reconcileOnFoot}).
     * The remount only fires once this reaches {@code MOUNTS.remountStableTicks} - a shelving bank
     * where the horse touches bottom for a tick or two never reaches it. Transient, never persisted.
     */
    private int remountReadyTicks = 0;

    public int getRemountReadyTicks() {
        return remountReadyTicks;
    }

    public void setRemountReadyTicks(int ticks) {
        this.remountReadyTicks = ticks;
    }

    /** While this rider leads its horse on foot: consecutive ticks the horse has stayed past
     * {@code MOUNTS.ledStuckRecallDistance} without closing the gap. Past
     * {@code MOUNTS.ledStuckRecallTicks} the horse is brought to the rider - see
     * {@code TravellerMountSupport.reconcileOnFoot}. Transient, reset whenever the mode leaves LED. */
    private int ledHorseStuckTicks = 0;
    /** Smallest rider-horse gap (squared) seen this led-stuck episode, to tell "closing" from
     * "snagged". Transient. */
    private double ledHorseBestGapSq = Double.MAX_VALUE;

    public int getLedHorseStuckTicks() {
        return ledHorseStuckTicks;
    }

    public void setLedHorseStuckTicks(int ticks) {
        this.ledHorseStuckTicks = ticks;
    }

    public double getLedHorseBestGapSq() {
        return ledHorseBestGapSq;
    }

    public void setLedHorseBestGapSq(double gapSq) {
        this.ledHorseBestGapSq = gapSq;
    }

    /** Throttles the near-village LED-trigger scan (see {@code TravellerMountSupport.tickMount}) so
     * a bonded rider doesn't rescan the settlement list every single tick; {@link #cachedNearVillage}
     * holds the answer between rescans. Both transient. */
    private int nextVillageProximityCheckTick = 0;
    private boolean cachedNearVillage = false;

    public int getNextVillageProximityCheckTick() {
        return nextVillageProximityCheckTick;
    }

    public void setNextVillageProximityCheckTick(int tick) {
        this.nextVillageProximityCheckTick = tick;
    }

    public boolean isCachedNearVillage() {
        return cachedNearVillage;
    }

    public void setCachedNearVillage(boolean nearVillage) {
        this.cachedNearVillage = nearVillage;
    }

    // --- Lead trail: this rider's recent ground positions - a led horse follows in single file
    // instead of cutting straight at the rider and weaving (TravellerMountSupport.driveLedHorse), and
    // a mounted group's followers retrace it when their own straight-line steer fails on rough terrain
    // instead of guessing blind (EntityAIFollowLeader.tryFollowLeaderTrail, MountedFollowPacer) - the
    // leader already proved that ground passable a few ticks ago. Transient (loaded-only), maintained
    // once per tick whenever this rider has a bonded horse, mounted or leading it on foot alike;
    // cleared on a teleport hop or losing the bond.
    private static final int LEAD_TRAIL_MAX = 64;
    private final java.util.ArrayDeque<Vec3d> leadTrail = new java.util.ArrayDeque<>();
    private int ledPacerObstacleTicks = 0;
    private int ledPacerNavUntilTick = 0;
    private double ledPacerSmoothedRiderSpeed = 0.0;

    /** Appends the rider's current position to the lead trail (deduped, capped). */
    public void recordLeadTrailPoint() {
        Vec3d head = leadTrail.peekFirst();
        if (head != null) {
            double dx = head.x - unit.posX, dz = head.z - unit.posZ;
            if (dx * dx + dz * dz < 0.0036D) return; // < 0.06 blocks moved: nothing new
        }
        leadTrail.addFirst(new Vec3d(unit.posX, unit.posY, unit.posZ));
        while (leadTrail.size() > LEAD_TRAIL_MAX) leadTrail.removeLast();
    }

    public void clearLeadTrail() {
        leadTrail.clear();
    }

    public int getLeadTrailSize() {
        return leadTrail.size();
    }

    /**
     * The point on the lead trail roughly {@code blocks} of walked distance behind the rider - what a
     * led horse aims at so it treads the same ground the rider just did. Returns the oldest recorded
     * point when the trail is shorter than that, or {@code null} when it is empty.
     */
    @Nullable
    public Vec3d leadTrailPointBehind(double blocks) {
        if (leadTrail.isEmpty()) return null;
        double acc = 0.0D;
        Vec3d prev = null;
        Vec3d last = null;
        for (Vec3d p : leadTrail) { // iterates newest -> oldest
            last = p;
            if (prev != null) {
                double dx = p.x - prev.x, dz = p.z - prev.z;
                acc += Math.sqrt(dx * dx + dz * dz);
                if (acc >= blocks) return p;
            }
            prev = p;
        }
        return last;
    }

    public int getLedPacerObstacleTicks() {
        return ledPacerObstacleTicks;
    }

    public void setLedPacerObstacleTicks(int ticks) {
        this.ledPacerObstacleTicks = ticks;
    }

    public int getLedPacerNavUntilTick() {
        return ledPacerNavUntilTick;
    }

    public void setLedPacerNavUntilTick(int tick) {
        this.ledPacerNavUntilTick = tick;
    }

    public double getLedPacerSmoothedRiderSpeed() {
        return ledPacerSmoothedRiderSpeed;
    }

    public void setLedPacerSmoothedRiderSpeed(double v) {
        this.ledPacerSmoothedRiderSpeed = v;
    }

    /** How many mount sidesteps have fired close together in time - see
     * TravellerMountSupport.trySidestepIfWedged. Transient, never persisted. */
    private int sidestepStreak = 0;
    /** {@code ticksExisted} the last sidestep fired at, or 0 before the first one. Transient. */
    private int lastSidestepTick = 0;

    public int getSidestepStreak() {
        return sidestepStreak;
    }

    public void setSidestepStreak(int streak) {
        this.sidestepStreak = streak;
    }

    public int getLastSidestepTick() {
        return lastSidestepTick;
    }

    public void setLastSidestepTick(int tick) {
        this.lastSidestepTick = tick;
    }

    public int getRemountTicks() {
        return remountTicks;
    }

    public void setRemountTicks(int ticks) {
        this.remountTicks = ticks;
    }

    // ---- persistence (the unit's own NBT; the bond UUID is written by the unit) ----

    void writeNbt(NBTTagCompound compound) {
        compound.setInteger("MountGen", mountGeneration);
        if (pendingMountPos != null) compound.setLong("PendingMountPos", pendingMountPos.toLong());
        if (pendingMountNbt != null) compound.setTag("PendingMountNbt", pendingMountNbt);
        // LED's own recovery tracking is transient, so a rider saved as LED would reload stuck on
        // foot: MOUNTED is persisted instead (the next failed path re-arms LED). HITCHED is kept.
        // INVARIANT: LED is never persisted.
        MountMode savedMode = (mountMode == MountMode.LED) ? MountMode.MOUNTED : mountMode;
        compound.setString("MountMode", savedMode.name());
        if (mountSnapshotNbt != null) compound.setTag("MountSnapshot", mountSnapshotNbt);
    }

    void readNbt(NBTTagCompound compound) {
        mountGeneration = compound.getInteger("MountGen");
        setParkedMount(compound.hasKey("PendingMountNbt") ? compound.getCompoundTag("PendingMountNbt") : null,
                compound.hasKey("PendingMountPos") ? BlockPos.fromLong(compound.getLong("PendingMountPos")) : null);
        mountMode = MountMode.byName(compound.getString("MountMode"));
        mountSnapshotNbt = compound.hasKey("MountSnapshot") ? compound.getCompoundTag("MountSnapshot") : null;
    }
}
