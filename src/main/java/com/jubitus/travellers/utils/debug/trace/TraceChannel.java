package com.jubitus.travellers.utils.debug.trace;

import java.util.EnumSet;
import java.util.Locale;

/**
 * The groups of per-tick data a trace can record. Each one is a self-contained {@code name:key=value}
 * section on a sample line, so turning one off shortens the line instead of shifting any column -
 * which is what lets a trace be read (and parsed) the same way whatever the selection was.
 */
public enum TraceChannel {
    /** Position, per-tick displacement, facing, ground/collision state. */
    MOVE,
    /** Navigator state: whether a path exists, how far along it is, the node and destination. */
    PATH,
    /** What the AI is actually asking for: move-helper target, its speed, the resulting moveForward. */
    STEER,
    /** Bonded horse: mode, speed ramp, its own collision state, and whether it is wedged. */
    MOUNT,
    /** Which AI tasks currently hold the entity - the single question logs could never answer before. */
    AI,
    /** Follow/formation specifics, including the steering-blocked counter that gates escalation to A*. */
    FOLLOW;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EnumSet<TraceChannel> defaults() {
        return EnumSet.of(MOVE, PATH, STEER, MOUNT, AI, FOLLOW);
    }

    /** Parses "move,path,mount" or "all". Returns null when a name is unknown, so the caller can report it. */
    public static EnumSet<TraceChannel> parse(String spec) {
        if (spec == null || spec.isEmpty()) return defaults();
        if ("all".equalsIgnoreCase(spec)) return EnumSet.allOf(TraceChannel.class);

        EnumSet<TraceChannel> out = EnumSet.noneOf(TraceChannel.class);
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            boolean found = false;
            for (TraceChannel c : values()) {
                if (c.name().equalsIgnoreCase(trimmed)) {
                    out.add(c);
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }
        return out.isEmpty() ? defaults() : out;
    }

    public static String join(EnumSet<TraceChannel> channels) {
        StringBuilder sb = new StringBuilder();
        for (TraceChannel c : channels) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.lower());
        }
        return sb.toString();
    }
}
