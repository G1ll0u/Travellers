package com.jubitus.travellers.spawn;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.world.mill.MillSettlements;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Culture for a new traveller, weighted by the villages around its spawn point. */
public final class SpawnCulture {

    private SpawnCulture() {
    }

    /** A culture key drawn from nearby villages (closer and bigger weigh more), or null to fall back. */
    @Nullable
    public static String pickNear(World world, BlockPos pos, Random rand) {
        if (!TravellersConfig.SPAWN.travellerCultureWeightedSpawn) return null;

        long r2 = (long) TravellersConfig.SPAWN.travellerCultureScanRadius * TravellersConfig.SPAWN.travellerCultureScanRadius;
        Map<String, Double> weights = new HashMap<>();
        for (MillSettlements.Settlement s : MillSettlements.villages(world)) {
            if (s.cultureKey.isEmpty()) continue;
            long dx = s.center.getX() - pos.getX(), dz = s.center.getZ() - pos.getZ();
            long d2 = dx * dx + dz * dz;
            if (d2 > r2) continue;

            double w = 1.0D / (1.0D + Math.sqrt(d2));
            if (TravellersConfig.SPAWN.travellerCultureWeightUseVillagePopulation) {
                int villagers = s.population(world);
                if (villagers > 0) w *= 1.0D + villagers / 10.0D;
            }
            weights.merge(s.cultureKey, w, Double::sum);
        }
        if (weights.isEmpty()) return null;

        double total = 0.0D;
        for (double w : weights.values()) total += w;
        double r = rand.nextDouble() * total;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            r -= e.getValue();
            if (r <= 0.0D) return e.getKey();
        }
        return weights.keySet().iterator().next();
    }
}
