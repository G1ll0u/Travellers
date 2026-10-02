package com.jubitus.travellers.zone.duty;

import com.jubitus.travellers.zone.stable.StableHandTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Static table of {@link WorkerDutyBehavior}, one per duty kind - mirrors {@code NotificationDefs}'/
 * {@code ZoneTypeDefs}' idiom. Adding a future duty is one new constant here plus one behavior class;
 * nothing else (the AI task, the worker's fields, the assignment GUI's core) needs to change.
 */
public final class DutyDefs {

    private DutyDefs() {
    }

    /** Must match {@code ZoneTypeDefs.STABLE.dutyKind}. */
    public static final String STABLE_HAND = "STABLE_HAND";

    private static final Map<String, WorkerDutyBehavior> BY_KIND = buildTable();

    private static Map<String, WorkerDutyBehavior> buildTable() {
        Map<String, WorkerDutyBehavior> m = new HashMap<>();
        m.put(STABLE_HAND, new StableHandTask());
        return Collections.unmodifiableMap(m);
    }

    public static WorkerDutyBehavior get(String kind) {
        return kind == null ? null : BY_KIND.get(kind);
    }
}
