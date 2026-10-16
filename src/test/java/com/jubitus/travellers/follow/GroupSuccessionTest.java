package com.jubitus.travellers.follow;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroupSuccessionTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void bestEquippedWins() {
        Map<UUID, Double> scores = new HashMap<>();
        scores.put(A, 3.0D);
        scores.put(B, 21.0D);
        scores.put(C, 8.0D);
        assertEquals(B, GroupSuccession.pickSuccessor(scores));
    }

    @Test
    void tieGoesToSmallestUuidWhateverTheOrder() {
        Map<UUID, Double> scores = new HashMap<>();
        scores.put(C, 5.0D);
        scores.put(B, 5.0D);
        scores.put(A, 1.0D);
        assertEquals(B, GroupSuccession.pickSuccessor(scores));
    }

    @Test
    void singleMemberLeads() {
        Map<UUID, Double> scores = new HashMap<>();
        scores.put(C, 0.0D);
        assertEquals(C, GroupSuccession.pickSuccessor(scores));
    }
}
