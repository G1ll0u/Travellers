package com.jubitus.travellers.worldmap.stream;

import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate.Def;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate.Dyn;
import com.jubitus.travellers.worldmap.net.MsgMapPopulationUpdate.Entry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What one map viewer's client already knows about the population: the id given to each unit and the
 * fixed facts last sent for it. A new instance (map just opened) makes the next packet a full one.
 * INVARIANT: ids absent from a packet are forgotten here exactly as the client forgets them.
 */
final class PopulationSync {

    private final Map<UUID, Integer> ids = new HashMap<>();
    private final Map<UUID, String> sentFacts = new HashMap<>();
    private int nextId = 1;
    private boolean full = true;

    MsgMapPopulationUpdate encode(int dim, List<Entry> visible) {
        List<Def> defs = new ArrayList<>();
        List<Dyn> dyns = new ArrayList<>(visible.size());
        List<Entry> extras = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();

        for (Entry e : visible) {
            if (e.uuid == null) {
                extras.add(e);
                continue;
            }
            Integer id = ids.get(e.uuid);
            if (id == null) {
                id = nextId++;
                ids.put(e.uuid, id);
            }
            String facts = e.kind + "|" + e.label;
            if (!facts.equals(sentFacts.get(e.uuid))) {
                defs.add(new Def(id, e.kind, e.uuid, e.label));
                sentFacts.put(e.uuid, facts);
            }
            dyns.add(new Dyn(id, e.pos, e.virtual));
            seen.add(e.uuid);
        }
        ids.keySet().retainAll(seen);
        sentFacts.keySet().retainAll(seen);

        boolean wasFull = full;
        full = false;
        return new MsgMapPopulationUpdate(dim, wasFull, defs, dyns, extras);
    }
}
