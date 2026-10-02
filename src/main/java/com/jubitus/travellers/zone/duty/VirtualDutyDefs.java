package com.jubitus.travellers.zone.duty;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Static table of {@link VirtualDutyBehavior}, one per duty kind - mirrors {@link DutyDefs} for the
 * offscreen side. Adding a future duty's offline simulation is one new entry here, or none at all
 * if that duty has nothing worth simulating while unloaded.
 *
 * <p>The Stable duty's pool progression (taming/saddling/breeding) is driven by
 * {@code StableCoordinator} against the zone's own pool (see {@code StableWork}), not per-record
 * from {@code VirtualTravellerManager.refreshGoal}. The one per-record piece the offscreen side still
 * needs is {@link com.jubitus.travellers.zone.stable.StableHandVirtualDuty}: a palfrenier that
 * arrives on duty while virtualized must surrender its own bonded mount into the pool, exactly as the
 * loaded {@code StableHandTask} does on a live arrival.
 */
public final class VirtualDutyDefs {

    private VirtualDutyDefs() {
    }

    private static final Map<String, VirtualDutyBehavior> BY_KIND = buildTable();

    private static Map<String, VirtualDutyBehavior> buildTable() {
        Map<String, VirtualDutyBehavior> m = new HashMap<>();
        m.put(DutyDefs.STABLE_HAND,
                new com.jubitus.travellers.zone.stable.StableHandVirtualDuty());
        return Collections.unmodifiableMap(m);
    }

    public static VirtualDutyBehavior get(String kind) {
        return kind == null ? null : BY_KIND.get(kind);
    }
}
