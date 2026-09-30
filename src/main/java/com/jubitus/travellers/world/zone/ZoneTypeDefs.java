package com.jubitus.travellers.world.zone;

import com.jubitus.travellers.world.zone.stable.StableZoneData;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Static table of {@link ZoneTypeDef}s, one constant per {@link ZoneType}. See the class doc on
 * {@link ZoneTypeDef} for why new types are added here rather than by branching elsewhere. */
public final class ZoneTypeDefs {

    private ZoneTypeDefs() {
    }

    public static final ZoneTypeDef BARRACK = new ZoneTypeDef(
            ZoneType.BARRACK,
            "travellers.zone.barrack.title",
            "travellers.zone.barrack.description",
            Arrays.asList(
                    new BlockRoleRequirement(ZoneRoles.BED, 1, BlockRoleScanners.BED, "travellers.zone.error.no_bed"),
                    new BlockRoleRequirement(ZoneRoles.CHEST, 1, BlockRoleScanners.CHEST, "travellers.zone.error.no_chest")),
            ZoneRoles.BED,
            BarrackZoneData::new,
            null);

    public static final ZoneTypeDef STABLE = new ZoneTypeDef(
            ZoneType.STABLE,
            "travellers.zone.stable.title",
            "travellers.zone.stable.description",
            Arrays.asList(
                    new BlockRoleRequirement(ZoneRoles.CHEST, 1, BlockRoleScanners.CHEST, "travellers.zone.error.no_chest"),
                    new BlockRoleRequirement(ZoneRoles.HITCH, 1, BlockRoleScanners.HITCH, "travellers.zone.error.no_hitch"),
                    new BlockRoleRequirement(ZoneRoles.BED, 1, BlockRoleScanners.BED, "travellers.zone.error.no_bed")),
            ZoneRoles.BED,
            StableZoneData::new,
            "STABLE_HAND");

    private static final Map<ZoneType, ZoneTypeDef> BY_TYPE = buildTable();

    private static Map<ZoneType, ZoneTypeDef> buildTable() {
        Map<ZoneType, ZoneTypeDef> m = new EnumMap<>(ZoneType.class);
        m.put(ZoneType.BARRACK, BARRACK);
        m.put(ZoneType.STABLE, STABLE);
        return Collections.unmodifiableMap(m);
    }

    public static ZoneTypeDef get(ZoneType type) {
        ZoneTypeDef def = BY_TYPE.get(type);
        if (def == null) throw new IllegalStateException("No ZoneTypeDef registered for " + type);
        return def;
    }
}
