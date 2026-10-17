package com.jubitus.travellers.battle;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleGroupLinkTest {

    private static final UUID OWNER = new UUID(9, 9);

    private static UUID id(int n) {
        return new UUID(0, n);
    }

    @Test
    void escortMembersAreLinkedToTheirLeaderEvenWhenTheLeaderFollowsTheOwner() {
        BattleGroupLink leader = BattleGroupLink.hired(id(1), null, true, OWNER);
        BattleGroupLink member = BattleGroupLink.hired(id(2), id(1), true, OWNER);
        BattleGroupLink otherMember = BattleGroupLink.hired(id(3), id(1), true, OWNER);
        assertTrue(member.linkedTo(leader));
        assertTrue(leader.linkedTo(member));
        assertTrue(member.linkedTo(otherMember));
    }

    @Test
    void looseFollowersOfTheSameOwnerFormOneGroup() {
        assertTrue(BattleGroupLink.hired(id(1), null, true, OWNER).linkedTo(BattleGroupLink.hired(id(2), null, true, OWNER)));
    }

    @Test
    void anchoredWorkersAndOtherGroupsStayApart() {
        BattleGroupLink anchoredA = BattleGroupLink.hired(id(1), null, false, OWNER);
        BattleGroupLink anchoredB = BattleGroupLink.hired(id(2), null, false, OWNER);
        BattleGroupLink otherEscort = BattleGroupLink.hired(id(4), id(3), true, OWNER);
        assertFalse(anchoredA.linkedTo(anchoredB));
        assertFalse(anchoredA.linkedTo(otherEscort));
        assertFalse(BattleGroupLink.plain(id(5), null).linkedTo(BattleGroupLink.plain(id(6), id(7))));
    }

    @Test
    void travellerGroupMembersShareTheirLeadersKey() {
        BattleGroupLink leader = BattleGroupLink.plain(id(1), null);
        assertTrue(BattleGroupLink.plain(id(2), id(1)).linkedTo(leader));
        assertTrue(BattleGroupLink.plain(id(2), id(1)).linkedTo(BattleGroupLink.plain(id(3), id(1))));
    }

    @Test
    void anchorStaysAtTheFirstBlowUntilTheFightClearlyMoves() {
        BattleRecord b = new BattleRecord(id(1), 0, new BlockPos(0, 64, 0), 0L);
        b.recordBlow(10.5, 10.5, 5L);
        assertEquals(new BlockPos(10, 64, 10), b.anchor);
        assertEquals(5L, b.firstBlowTick);
        for (int i = 0; i < 5; i++) b.recordBlow(20.5, 10.5, 6L + i);
        assertEquals(new BlockPos(10, 64, 10), b.anchor, "a few blows nearby must not move the marker");
        assertEquals(10L, b.lastBlowTick);
    }
}
