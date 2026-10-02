package com.jubitus.travellers.zone;

/**
 * A player-created zone's type - what it requires to be built and what it lets workers do there.
 * See {@link ZoneTypeDef}/{@link ZoneTypeDefs} for the behavior each value carries.
 */
public enum ZoneType {
    BARRACK,
    STABLE;

    /** Pre-generalization saves have no persisted zone type at all - every existing record is a Barrack. */
    public static ZoneType fromNbt(int ordinal) {
        ZoneType[] values = values();
        return (ordinal >= 0 && ordinal < values.length) ? values[ordinal] : BARRACK;
    }
}
