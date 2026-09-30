package com.jubitus.travellers.world.pathing.terrain;

import com.jubitus.travellers.world.pathing.terrain.TerrainProfileImageExport.Mode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Only the pure token parsing - the render path needs a live server and is checked in-world. */
class TerrainProfileImageExportTest {

    @Test
    void allTokenExpandsToEveryMode() {
        List<Mode> modes = TerrainProfileImageExport.parseModes("all");
        assertEquals(Mode.values().length, modes.size());
        for (Mode m : Mode.values()) assertTrue(modes.contains(m));
    }

    @Test
    void nullTokenIsTreatedAsAll() {
        assertEquals(Mode.values().length, TerrainProfileImageExport.parseModes(null).size());
    }

    @Test
    void namedTokenResolvesCaseInsensitively() {
        assertEquals(Collections.singletonList(Mode.HEIGHT), TerrainProfileImageExport.parseModes("HeIgHt"));
        assertEquals(Collections.singletonList(Mode.SOURCE), TerrainProfileImageExport.parseModes("source"));
    }

    @Test
    void unknownTokenYieldsEmptyList() {
        assertTrue(TerrainProfileImageExport.parseModes("zzz").isEmpty());
    }
}
