package com.jubitus.travellers.entity.entityAI.travel.travelcommon;

public class TravellerHomeManager {

    private TravellerHomeManager() {}

    public enum HomeType {
        BARRACKS,    // Millenaire custom barracks building
        LONE_BARRACK; // player-created standalone barrack

        /**
         * Stable serialized int, independent of enum ordinal.
         * Old saves used 0=CHEST (removed), 1=BARRACKS, 2=LONE_BARRACK.
         */
        public int toNbt() {
            switch (this) {
                case BARRACKS: return 1;
                case LONE_BARRACK: return 2;
                default: return 2;
            }
        }

        public static HomeType fromNbt(int o) {
            switch (o) {
                case 1: return BARRACKS;
                case 2: return LONE_BARRACK;
                default: return LONE_BARRACK; // 0 was legacy CHEST — no home
            }
        }

        /** @deprecated use {@link #fromNbt(int)} */
        @Deprecated
        public static HomeType fromOrdinal(int o) {
            return fromNbt(o);
        }
    }
}
