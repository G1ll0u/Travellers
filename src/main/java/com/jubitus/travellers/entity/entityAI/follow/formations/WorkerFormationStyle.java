package com.jubitus.travellers.entity.entityAI.follow.formations;

public enum WorkerFormationStyle {
    COMPACT,
    LINE,
    // Kept only for cave/tunnel single-file geometry (FollowFormationCoordinator.resolveCaveStyle)
    // and for deserializing pre-existing saves - no longer a selectable surface style, see next().
    COLUMN,
    WEDGE,
    ADAPTIVE;

    private static final WorkerFormationStyle[] SELECTABLE = {COMPACT, LINE, WEDGE, ADAPTIVE};

    public WorkerFormationStyle next() {
        for (int i = 0; i < SELECTABLE.length; i++) {
            if (SELECTABLE[i] == this) {
                return SELECTABLE[(i + 1) % SELECTABLE.length];
            }
        }
        return SELECTABLE[0];
    }
}