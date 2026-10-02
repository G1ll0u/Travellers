package com.jubitus.travellers.world.spawn;

import com.jubitus.travellers.config.TravellersConfig;

/** How many travellers, raiders and workers a village of a given population supports. */
public final class PopulationQuota {

    private PopulationQuota() {
    }

    public static int travellers(int villagers) {
        return scaled(villagers, TravellersConfig.SPAWN_TRAVELLERS.travellerCapPerVillager,
                TravellersConfig.SPAWN_TRAVELLERS.travellerCapMinPerVillage,
                TravellersConfig.SPAWN_TRAVELLERS.travellerCapMaxPerVillage);
    }

    public static int raiders(int villagers) {
        return scaled(villagers, TravellersConfig.SPAWN_RAIDERS.raiderCapPerVillager,
                TravellersConfig.SPAWN_RAIDERS.raiderCapMinPerVillage,
                TravellersConfig.SPAWN_RAIDERS.raiderCapMaxPerVillage);
    }

    public static int workers(int villagers) {
        return scaled(villagers, TravellersConfig.SPAWN_WORKERS.workerCapPerVillager,
                TravellersConfig.SPAWN_WORKERS.workerCapMinPerVillage,
                TravellersConfig.SPAWN_WORKERS.workerCapMaxPerVillage);
    }

    private static int scaled(int villagers, double perVillager, int min, int max) {
        int n = (int) Math.floor(villagers * perVillager);
        return Math.min(Math.max(n, min), max);
    }
}
