package com.jubitus.travellers.entity.travellers;

/**
 * A grounded, deterministic reason for an {@link EntityTraveller} to be on the road (identity-memory
 * plan, Part L) - rolled once at spawn and never re-rolled, never LLM-generated. See
 * {@code com.jubitus.travellers.server.dialogue.TravelMotiveDialogueContextProvider} for the weighted
 * roll and the phrasing shown to the model.
 */
public enum TravelMotive {
    TRADE_RUN,
    PILGRIMAGE,
    FAMILY_VISIT,
    SEEKING_WORK,
    HOMEWARD_RETURN,
    DELIVERING_NEWS,
    WANDERLUST,
    APPRENTICESHIP
}
