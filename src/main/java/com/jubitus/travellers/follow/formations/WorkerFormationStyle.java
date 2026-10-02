package com.jubitus.travellers.follow.formations;

import java.util.Random;

public enum WorkerFormationStyle {
    COMPACT,
    LINE,
    // Kept only for cave/tunnel single-file geometry (FollowFormationCoordinator.resolveCaveStyle)
    // and for deserializing pre-existing saves - no longer a selectable surface style, see next().
    COLUMN,
    WEDGE,
    ADAPTIVE,
    // Fixed per-follower personal offset, not ordinal/rank based and not heading-relative - used
    // for traveller/raider groups only (no owning player, so never player-selectable here).
    RING;

    private static final WorkerFormationStyle[] SELECTABLE = {COMPACT, LINE, WEDGE, ADAPTIVE};

    // Every style a traveller/raider group can roll for itself, including RING - unlike
    // SELECTABLE (the player-facing worker-escort cycle button), a group has no GUI to leave a
    // style out of, so it draws from the full pool.
    private static final WorkerFormationStyle[] GROUP_POOL = {COMPACT, LINE, WEDGE, ADAPTIVE, RING};

    public WorkerFormationStyle next() {
        for (int i = 0; i < SELECTABLE.length; i++) {
            if (SELECTABLE[i] == this) {
                return SELECTABLE[(i + 1) % SELECTABLE.length];
            }
        }
        return SELECTABLE[0];
    }

    /** Picks a group's formation style once, at group creation - see the group-atomic rolling
     *  pattern this mirrors, {@code VirtualTravellerRecord.mounted}. */
    public static WorkerFormationStyle randomGroupStyle(Random rand) {
        return GROUP_POOL[rand.nextInt(GROUP_POOL.length)];
    }
}