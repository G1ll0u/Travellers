package com.jubitus.travellers.world.zone;

/** String keys for {@link PlayerZoneRecord#getRolePositions(String)} - one per kind of block a zone
 * type can require or use ("bed", "chest", "hitch", ...). Open-ended: a new zone type just picks a
 * new key here (or reuses an existing one) and pairs it with a {@link BlockRoleScanner}. */
public final class ZoneRoles {

    private ZoneRoles() {
    }

    public static final String CHEST = "chest";
    public static final String BED = "bed";
    public static final String HITCH = "hitch";
}
