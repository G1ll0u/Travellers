package com.jubitus.travellers.world.zone;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/**
 * Everything that makes a {@link ZoneType} behave differently: what it requires to be created, how
 * its creation GUI describes itself, what extra state it carries, and (if it has one) which worker
 * duty can be assigned there. Mirrors {@code NotificationDefs}' static-table idiom - adding a new
 * zone type is one new constant in {@link ZoneTypeDefs} plus one {@link ZoneTypeData} implementation,
 * nothing else needs to change.
 */
public final class ZoneTypeDef {

    public final ZoneType type;
    public final String titleKey;
    public final String descriptionKey;
    public final List<BlockRoleRequirement> requiredRoles;
    /** Role whose block count is this zone's worker capacity (like a Barrack's beds), or null if the
     * type has no such cap. */
    @Nullable public final String capacityRole;
    public final Supplier<ZoneTypeData> dataFactory;
    /** Worker duty kind assignable at this zone type (see the duty framework), or null if none. */
    @Nullable public final String dutyKind;

    public ZoneTypeDef(ZoneType type, String titleKey, String descriptionKey,
                        List<BlockRoleRequirement> requiredRoles, @Nullable String capacityRole,
                        Supplier<ZoneTypeData> dataFactory, @Nullable String dutyKind) {
        this.type = type;
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.requiredRoles = requiredRoles;
        this.capacityRole = capacityRole;
        this.dataFactory = dataFactory;
        this.dutyKind = dutyKind;
    }
}
