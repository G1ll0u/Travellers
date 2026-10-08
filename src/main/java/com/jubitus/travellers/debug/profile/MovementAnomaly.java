package com.jubitus.travellers.debug.profile;

public enum MovementAnomaly {
    TELEPORT_SEEN("Any", "teleported, any cause (seen by the sampler)"),
    TRAVEL_STUCK_REPLAN("Travel", "stuck: itinerary rebuilt from here"),
    TRAVEL_RESCUE_TP("Travel", "rescue teleport succeeded"),
    TRAVEL_RESCUE_FAILED("Travel", "rescue teleport found nowhere to go"),
    TRAVEL_STILLBORN("Travel", "stillborn route (goes nowhere)"),
    TRAVEL_DETOUR_WIDE("Travel", "wide detour found"),
    TRAVEL_DETOUR_WIDE_FAILED("Travel", "wide detour found nothing"),
    TRAVEL_NO_WAYPOINT("Travel", "no waypoint left (raw A* to the goal)"),
    FOLLOW_CAVE_RESCUE("Follow", "cave rescue"),
    MOUNT_SIDESTEP("Mount", "wedged horse sidestep"),
    LED_HORSE_JAMMED("Mount", "led horse jammed (horse A*)"),
    LED_HORSE_SNAG_RECALL("Mount", "led horse snagged, recalled onto trail"),
    HORSE_TELEPORT_RECALL("Mount", "horse teleported to its rider (any reason)"),
    HORSE_RECALL_HITCHED("Mount", "recall: hitched horse left too far behind"),
    HORSE_RECALL_LED("Mount", "recall: led horse beyond lead range"),
    HORSE_RECALL_REMOUNT("Mount", "recall: rider waiting to remount too long or too far"),
    MOUNT_ORPHAN_VEHICLE("Mount", "rider on a vanished horse (repaired)");

    public final String family;
    public final String label;

    MovementAnomaly(String family, String label) {
        this.family = family;
        this.label = label;
    }

    public enum LedReason {
        TERRAIN("terrain the horse cannot path"),
        LOW_HEALTH("horse badly hurt"),
        VILLAGE("entered a settlement"),
        MELEE("melee range"),
        SIDESTEP_GAVE_UP("wedged, gave up sidestepping"),
        BOAT("boat crossing");

        public final String label;

        LedReason(String label) {
            this.label = label;
        }
    }
}
