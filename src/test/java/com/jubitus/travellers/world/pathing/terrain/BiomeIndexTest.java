package com.jubitus.travellers.world.pathing.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Name<->id bijection and on-disk table round-trip. Registry fold-in is not exercised (no MC here). */
class BiomeIndexTest {

    @Test
    void assignsStableIncrementingIds() {
        BiomeIndex idx = BiomeIndex.empty();
        short a = idx.idFor("minecraft:plains");
        short b = idx.idFor("minecraft:desert");
        short a2 = idx.idFor("minecraft:plains");
        assertEquals(0, a);
        assertEquals(1, b);
        assertEquals(a, a2, "same name -> same id");
        assertEquals("minecraft:plains", idx.nameFor(a));
        assertEquals("minecraft:desert", idx.nameFor(b));
        assertNull(idx.nameFor((short) 99));
        assertNull(idx.nameFor((short) -1));
    }

    @Test
    void tableRoundTripsThroughAFile(@TempDir Path dir) {
        BiomeIndex idx = BiomeIndex.empty();
        idx.idFor("minecraft:plains");
        idx.idFor("minecraft:ocean");
        idx.idFor("biomesoplenty:alps");
        File file = new File(dir.toFile(), "biome-index.tbl");
        idx.save(file);

        BiomeIndex loaded = BiomeIndex.fromFileForTest(file);
        assertEquals(3, loaded.size());
        assertEquals(0, loaded.idFor("minecraft:plains"));
        assertEquals(1, loaded.idFor("minecraft:ocean"));
        assertEquals(2, loaded.idFor("biomesoplenty:alps"));
        assertEquals("biomesoplenty:alps", loaded.nameFor((short) 2));
    }

    @Test
    void unknownVersionFileIsIgnoredNotFatal(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "biome-index.tbl");
        try (java.io.DataOutputStream out =
                     new java.io.DataOutputStream(new java.io.FileOutputStream(file))) {
            out.writeInt(0x54424958); // MAGIC
            out.writeInt(777);        // bogus version
            out.writeInt(1);
            out.writeUTF("minecraft:plains");
        }
        BiomeIndex loaded = BiomeIndex.fromFileForTest(file);
        assertEquals(0, loaded.size(), "unknown version -> empty table, no crash");
    }
}
