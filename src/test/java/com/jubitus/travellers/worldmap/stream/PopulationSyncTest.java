package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate.Entry;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate.Kind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PopulationSyncTest {

    private static Entry unit(UUID id, int x, int z, String label, boolean virtual) {
        Entry e = new Entry();
        e.dim = 0;
        e.pos = new BlockPos(x, 64, z);
        e.uuid = id;
        e.kind = Kind.TRAVELLER;
        e.virtual = virtual;
        e.label = label;
        return e;
    }

    // What the client really receives: through the wire, then its id table.
    private static List<Entry> receive(MsgMapPopulationUpdate sent) {
        ByteBuf buf = Unpooled.buffer();
        sent.toBytes(buf);
        MsgMapPopulationUpdate got = new MsgMapPopulationUpdate();
        got.fromBytes(buf);
        return MsgMapPopulationUpdate.ClientHandler.resolve(got);
    }

    private static int wireSize(MsgMapPopulationUpdate m) {
        ByteBuf buf = Unpooled.buffer();
        m.toBytes(buf);
        return buf.readableBytes();
    }

    @Test
    void clientRebuildsEntriesAndLaterPacketsAreSmaller() {
        PopulationSync sync = new PopulationSync();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        List<Entry> first = Arrays.asList(unit(a, 10, 20, "Alice", false), unit(b, 30, 40, "Bob", true));
        MsgMapPopulationUpdate p1 = sync.encode(0, first);
        List<Entry> got = receive(p1);
        assertEquals(2, got.size());
        assertEquals("Alice", got.get(0).label);
        assertEquals(a, got.get(0).uuid);
        assertEquals(10, got.get(0).pos.getX());
        assertTrue(got.get(1).virtual);

        MsgMapPopulationUpdate p2 = sync.encode(0, Arrays.asList(unit(a, 11, 20, "Alice", false), unit(b, 31, 40, "Bob", true)));
        assertTrue(wireSize(p2) < wireSize(p1), "static facts are not resent");
        got = receive(p2);
        assertEquals("Bob", got.get(1).label);
        assertEquals(31, got.get(1).pos.getX());
    }

    @Test
    void labelChangeIsResentAndLeavingUnitComesBackWithItsFacts() {
        PopulationSync sync = new PopulationSync();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        receive(sync.encode(0, Arrays.asList(unit(a, 0, 0, "Old", false), unit(b, 5, 5, "Bob", false))));

        List<Entry> got = receive(sync.encode(0, Arrays.asList(unit(a, 0, 0, "New", false))));
        assertEquals("New", got.get(0).label);

        got = receive(sync.encode(0, Arrays.asList(unit(a, 0, 0, "New", false), unit(b, 6, 6, "Bob", false))));
        assertEquals(2, got.size());
        assertEquals("Bob", got.get(1).label);
    }

    @Test
    void reopenedMapStartsFromAFullPacket() {
        UUID a = UUID.randomUUID();
        PopulationSync first = new PopulationSync();
        receive(first.encode(0, Arrays.asList(unit(a, 0, 0, "Alice", false))));
        PopulationSync reopened = new PopulationSync(); // a new subscribe
        List<Entry> got = receive(reopened.encode(0, Arrays.asList(unit(a, 1, 1, "Alice", false))));
        assertEquals("Alice", got.get(0).label);
    }

    @Test
    void stablesAreSentWholeEveryTime() {
        PopulationSync sync = new PopulationSync();
        Entry stable = new Entry();
        stable.dim = 0;
        stable.pos = new BlockPos(1, 70, 2);
        stable.kind = Kind.STABLE;
        stable.label = "Stable\n2 horses";
        List<Entry> list = new ArrayList<>();
        list.add(stable);
        assertEquals("Stable\n2 horses", receive(sync.encode(0, list)).get(0).label);
        assertEquals("Stable\n2 horses", receive(sync.encode(0, list)).get(0).label);
    }
}
