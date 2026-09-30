package com.jubitus.travellers.entity.entityai.travel.travelcommon;

public class TravellerHomeManager {

    private TravellerHomeManager() {}

    public enum HomeType {
        LONE_BARRACK; // player-created standalone barrack — the only home type

        /**
         * Stable serialized int, independent of enum ordinal.
         * Old saves used 0=CHEST and 1=BARRACKS, both removed.
         */
        public int toNbt() {
            return 2;
        }

        public static HomeType fromNbt(int o) {
            return LONE_BARRACK;
        }

        /** @deprecated use {@link #fromNbt(int)} */
        @Deprecated
        public static HomeType fromOrdinal(int o) {
            return fromNbt(o);
        }
    }
}