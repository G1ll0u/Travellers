package com.jubitus.travellers.entity.entityAI.travel.mount;

/**
 * What a rider currently wants to do with its bonded horse. This is an <em>intent</em>, set by AI
 * tasks (village roaming, camping, boat crossings) or a player-facing setting - never by the
 * reconciler itself, which only ever makes the world match it (see
 * {@link TravellerMountSupport#tickMount}).
 *
 * <p>The bond is unaffected by the mode: a rider stays paired with the same horse in all three,
 * for as long as both are alive.
 */
public enum MountMode {

    /** Riding it. The default, and what every other mode returns to when it ends. */
    MOUNTED,

    /** On foot, horse leashed and following at the rider's own pace. */
    LED,

    /** On foot, horse left standing where it is (no leash, so nothing drags it along). */
    HITCHED;

    public static MountMode byName(String name) {
        for (MountMode m : values()) {
            if (m.name().equals(name)) return m;
        }
        return MOUNTED;
    }
}
