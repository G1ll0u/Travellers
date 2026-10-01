package com.jubitus.travellers.world.zone.stable;

import java.util.UUID;

/**
 * Per-worker transient state for {@link StableHandTask}'s chore sequencing (approach, feed,
 * mount/dismount ritual, chest visit, breeding, ambient interaction, walking to bed at night).
 * Deliberately not persisted: the durable pool progress lives in {@link StableHorseEntry} /
 * {@link StableChestMirror}. Losing this on a save/reload or a materialize just makes the palfrenier
 * re-decide its next action from scratch, invisibly - the horse it was half-way to taming keeps its
 * accrued temper on the entry either way.
 */
final class PalfrenierState {

    /** Top-level rhythm: the palfrenier alternates timed WORKING sessions (chores, the {@link Phase}
     * machine below) with ON_BREAK sessions (ambles the pen, looks around, starts no chores). */
    enum Session {WORKING, ON_BREAK}

    enum Phase {
        DECIDE,
        FETCH_FEED,
        MOVE_TO_HORSE,
        FEED_HORSE,
        MOUNT_HORSE,
        RIDE_HORSE,
        DISMOUNT_PAUSE,
        MOVE_TO_CHEST_FOR_SADDLE,
        OPEN_CHEST_TAKE_SADDLE,
        CARRY_SADDLE_TO_HORSE,
        APPLY_SADDLE,
        FETCH_CARROTS,
        MOVE_TO_PAIR,
        FEED_PAIR,
        AMBIENT_PET_HORSE,
        AMBIENT_IDLE_PAUSE,
        GO_TO_BED,
        SLEEP
    }

    // -------------------------------------------------------------------------
    // Session rhythm (work vs break)
    // -------------------------------------------------------------------------
    Session session = Session.WORKING;
    /** World tick the current session ends: a break begins (WORKING) / chores resume (ON_BREAK).
     * 0 = not yet initialised - the first tick seeds a WORKING session. */
    long sessionUntilTick = 0L;
    /** ON_BREAK only: next tick to pick a fresh amble spot / look direction. */
    long breakNextMoveTick = 0L;
    /** ON_BREAK only: the entity the palfrenier is watching for the current amble leg, or null to
     * look at the horizon direction in {@code idleLookX/Z} instead. */
    UUID breakLookUUID;

    Phase phase = Phase.DECIDE;
    /** The horse currently being worked (taming/saddling/ambient-pet target). */
    UUID targetHorseUUID;
    /** The chest currently being visited for the saddle-fetch. */
    net.minecraft.util.math.BlockPos targetChest;
    /** Generic phase-local timer: ticks remaining, or the world tick a wait ends at, depending on use. */
    long phaseEndTick;
    /** Throttle for re-running "what should I do next" when there's nothing pressing. */
    long nextDecideTick;
    /** Earliest tick the palfrenier will next consider starting a deliberate breeding - mirrors
     * {@code STABLES.breedingCheckIntervalTicks} so it doesn't attempt it every decide. */
    long nextBreedCheckTick;
    /** Whether the chest currently open for this sequence still needs a close call before moving on. */
    boolean chestOpen;
    /** Fixed horizontal look direction for {@code AMBIENT_IDLE_PAUSE}, mirroring vanilla's own
     * {@code EntityAILookIdle} - rolled once when the pause starts, then re-applied every tick. */
    double idleLookX;
    double idleLookZ;
    /** When the walk-to-bed approach started, for the "lie down anyway" fallback timeout. */
    long bedApproachStartTick = -1L;
    /** When the current walk-to-a-work-spot (horse / chest) approach started, for the snap-hop
     * watchdog - a palfrenier that can't route into a walled structure must never stay stuck. -1 =
     * not currently approaching anything. */
    long approachStartTick = -1L;
    /** World tick the current work target was picked - a palfrenier that can't get to its horse
     * within a cap abandons it and briefly avoids it (below) so it tries another. -1 = no target. */
    long targetSince = -1L;
    /** A horse this palfrenier gave up reaching, and the tick that avoidance expires - so it doesn't
     * immediately re-pick the same unreachable animal. Not cleared on {@link StableHandTask}'s
     * {@code reset}; it self-expires. */
    UUID avoidHorseUUID;
    long avoidUntil;
    /** The two pool horses (by {@code poolId}) picked for a deliberate breeding, and how many feed
     * beats have been delivered so far. */
    UUID breedPoolIdA;
    UUID breedPoolIdB;
    long breedFeedBeats;
}
