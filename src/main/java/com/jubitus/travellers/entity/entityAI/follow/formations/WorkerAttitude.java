package com.jubitus.travellers.entity.entityAI.follow.formations;

/**
 * Per-group combat posture, set from GuiWorkerFormationHub's "Attitude" button and persisted
 * alongside the rest of {@link WorkerFormationSettings}. Consulted by the melee/ranged combat AI
 * (see EntityAIAttackMeleeCustom, EntityAIRangedBowTravellerSafe) via
 * {@code EntityTravellerWorker.getCachedAttitude()} - never read the saved data directly from
 * per-tick combat code, that cache is the single cheap read path.
 */
public enum WorkerAttitude {
    /** Chase and engage freely - today's default behaviour. */
    ATTACK,
    /** Fight anything that comes close, but never leave the group to chase a distant target. */
    DEFEND,
    /**
     * Stand in formation - only breaks rank a short distance (see
     * {@code TravellersConfig.COMBAT.holdBreakRadius}) to strike something that got close, then
     * returns to its own slot once the fight ends. Not a hard "never move": a hard freeze the moment
     * a target is a hair out of strict melee reach reads as jittering in place, not holding position.
     */
    HOLD;

    public WorkerAttitude next() {
        WorkerAttitude[] vals = values();
        return vals[(this.ordinal() + 1) % vals.length];
    }
}
