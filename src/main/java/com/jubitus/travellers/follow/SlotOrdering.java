package com.jubitus.travellers.follow;

import com.jubitus.travellers.unit.EntityTravellerWorker;
import net.minecraft.entity.EntityLivingBase;

import java.util.*;

/** Orders a leader's members for slot assignment: escorts first (nearest, with hysteresis), then the rest. */
final class SlotOrdering {

    private static final double ESCORT_HYSTERESIS_DIST = 1.6D;

    private SlotOrdering() {
    }

    static List<EntityTravellerWorker> order(
            List<EntityTravellerWorker> members,
            int numEscorts,
            EntityLivingBase leader,
            Map<UUID, Integer> prevOrdinals) {

        if (numEscorts <= 0 || members.size() <= numEscorts) {
            List<EntityTravellerWorker> r = new ArrayList<>(members);
            r.sort(new StableMemberComparator(prevOrdinals));
            return r;
        }

        final double lx = leader.posX, lz = leader.posZ;

        // Precompute a stable score per worker: dist minus a bonus for workers already in escort.
        // Sorting by a single scalar is always transitive — avoids the "violates contract" crash.
        List<EntityTravellerWorker> byDist = new ArrayList<>(members);
        final Map<UUID, Double> escortScore = new HashMap<>();
        for (EntityTravellerWorker w : members) {
            double dx = w.posX - lx, dz = w.posZ - lz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            Integer prev = prevOrdinals.get(w.getUniqueID());
            double bonus = (prev != null && prev < numEscorts) ? ESCORT_HYSTERESIS_DIST : 0.0D;
            escortScore.put(w.getUniqueID(), dist - bonus);
        }
        byDist.sort((a, b) -> {
            int cmp = Double.compare(escortScore.get(a.getUniqueID()), escortScore.get(b.getUniqueID()));
            if (cmp != 0) return cmp;
            Integer oa = prevOrdinals.get(a.getUniqueID());
            Integer ob = prevOrdinals.get(b.getUniqueID());
            int ia = oa != null ? oa : Integer.MAX_VALUE;
            int ib = ob != null ? ob : Integer.MAX_VALUE;
            if (ia != ib) return Integer.compare(ia, ib);
            return a.getUniqueID().compareTo(b.getUniqueID());
        });

        Set<UUID> escortSet = new HashSet<>();
        List<EntityTravellerWorker> escortWorkers = new ArrayList<>(numEscorts);
        for (int i = 0; i < numEscorts; i++) {
            EntityTravellerWorker ew = byDist.get(i);
            escortWorkers.add(ew);
            escortSet.add(ew.getUniqueID());
        }
        escortWorkers.sort(new StableMemberComparator(prevOrdinals));

        List<EntityTravellerWorker> stableAll = new ArrayList<>(members);
        stableAll.sort(new StableMemberComparator(prevOrdinals));
        List<EntityTravellerWorker> formWorkers = new ArrayList<>(members.size() - numEscorts);
        for (EntityTravellerWorker w : stableAll) {
            if (!escortSet.contains(w.getUniqueID())) formWorkers.add(w);
        }

        List<EntityTravellerWorker> result = new ArrayList<>(members.size());
        result.addAll(escortWorkers);
        result.addAll(formWorkers);
        return result;
    }

    private static final class StableMemberComparator implements Comparator<EntityTravellerWorker> {
        private final Map<UUID, Integer> previousOrdinals;

        private StableMemberComparator(Map<UUID, Integer> previousOrdinals) {
            this.previousOrdinals = previousOrdinals;
        }

        @Override
        public int compare(EntityTravellerWorker a, EntityTravellerWorker b) {
            Integer pa = previousOrdinals.get(a.getUniqueID());
            Integer pb = previousOrdinals.get(b.getUniqueID());

            if (pa != null && pb != null && !pa.equals(pb)) return Integer.compare(pa, pb);
            if (pa != null && pb == null) return -1;
            if (pa == null && pb != null) return 1;

            int ha = a.getUniqueID().hashCode();
            int hb = b.getUniqueID().hashCode();
            if (ha != hb) return Integer.compare(ha, hb);

            return Integer.compare(a.getEntityId(), b.getEntityId());
        }
    }
}
