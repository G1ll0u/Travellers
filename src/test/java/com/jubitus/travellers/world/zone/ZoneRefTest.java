package com.jubitus.travellers.world.zone;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneRefTest {

    private static NBTTagCompound legacyHome(int homeType) {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setInteger("HomeX", 10);
        nbt.setInteger("HomeY", 64);
        nbt.setInteger("HomeZ", -20);
        nbt.setInteger("HomeDim", 0);
        nbt.setString("HomeName", "Old barrack");
        nbt.setInteger("HomeType", homeType);
        return nbt;
    }

    @Test
    void legacyBarrackHomeReadsAsUnresolvedBarrack() {
        ZoneRef ref = ZoneRef.read(legacyHome(2));
        assertNotNull(ref);
        assertNull(ref.id);
        assertEquals(ZoneType.BARRACK, ref.type);
        assertEquals(new BlockPos(10, 64, -20), ref.center);
        assertEquals("Old barrack", ref.name);
    }

    @Test
    void removedMillenaireBarracksHomeReadsAsNone() {
        assertNull(ZoneRef.read(legacyHome(1)));
    }

    @Test
    void legacyDutyWinsOverLegacyHome() {
        NBTTagCompound nbt = legacyHome(2);
        nbt.setInteger("DutyX", 100);
        nbt.setInteger("DutyY", 70);
        nbt.setInteger("DutyZ", 100);
        nbt.setInteger("DutyDim", 0);
        ZoneRef ref = ZoneRef.read(nbt);
        assertNotNull(ref);
        assertEquals(ZoneType.STABLE, ref.type);
        assertEquals(new BlockPos(100, 70, 100), ref.center);
    }

    @Test
    void currentTagsRoundTripAndWinOverLegacy() {
        UUID id = UUID.randomUUID();
        NBTTagCompound nbt = legacyHome(2);
        new ZoneRef(id, new BlockPos(1, 2, 3), -1, "Stable", ZoneType.STABLE).write(nbt);
        ZoneRef ref = ZoneRef.read(nbt);
        assertNotNull(ref);
        assertEquals(id, ref.id);
        assertEquals(new BlockPos(1, 2, 3), ref.center);
        assertEquals(-1, ref.dim);
        assertEquals(ZoneType.STABLE, ref.type);
    }

    @Test
    void matchesByIdOnceResolvedAndByCentreBefore() {
        PlayerZoneRecord zone = new PlayerZoneRecord(UUID.randomUUID(), "Z", new BlockPos(5, 5, 5), 4, 4, 2, ZoneType.BARRACK);
        PlayerZoneRecord sameSpotRecreated = new PlayerZoneRecord(zone.ownerUUID, "Z", new BlockPos(5, 5, 5), 4, 4, 2, ZoneType.BARRACK);

        ZoneRef legacy = new ZoneRef(null, new BlockPos(5, 5, 5), 0, "", ZoneType.BARRACK);
        assertTrue(legacy.matches(zone));
        assertTrue(legacy.matches(sameSpotRecreated));

        ZoneRef resolved = new ZoneRef(zone.id, new BlockPos(5, 5, 5), 0, "", ZoneType.BARRACK);
        assertTrue(resolved.matches(zone));
        assertFalse(resolved.matches(sameSpotRecreated));
    }

    @Test
    void clearRemovesCurrentAndLegacyTags() {
        NBTTagCompound nbt = legacyHome(2);
        nbt.setInteger("DutyX", 1);
        new ZoneRef(UUID.randomUUID(), BlockPos.ORIGIN, 0, "", ZoneType.BARRACK).write(nbt);
        ZoneRef.clear(nbt);
        assertNull(ZoneRef.read(nbt));
    }
}
