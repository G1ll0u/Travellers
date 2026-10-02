package com.jubitus.travellers.village;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.mixins.MixinResManagerPointsAccessor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.buildingmanagers.ResManager;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * What a settlement offers a visitor, read from Millénaire's resident building data (no block scan):
 * building entrances (where Millénaire paths start, street side), the buildings worth visiting with
 * the exact spot a villager would go to, and the information panels. Cached per settlement.
 */
public final class VillageVisitIndex {

    private static final Map<World, Map<Long, Entry>> CACHE = new WeakHashMap<>();

    private VillageVisitIndex() {
    }

    public static final class Target {
        /** Where to stand: the Millénaire leisure/selling point, or the panel block itself. */
        public final BlockPos point;
        /** The building's entrance, street side; null when the plan defines none. */
        @Nullable
        public final BlockPos entrance;
        public final String label;
        public final boolean panel;

        Target(BlockPos point, @Nullable BlockPos entrance, String label, boolean panel) {
            this.point = point;
            this.entrance = entrance;
            this.label = label;
            this.panel = panel;
        }
    }

    public static final class Settlement {
        public final BlockPos center;
        public final boolean lone;
        public final int radius;
        public final List<BlockPos> entrances;
        public final List<Target> buildings;
        public final List<Target> panels;
        /** The same panels, one list per building: a visitor picks a building first, then one of its panels. */
        public final List<List<Target>> panelGroups;

        Settlement(BlockPos center, boolean lone, int radius, List<BlockPos> entrances,
                   List<Target> buildings, List<Target> panels, List<List<Target>> panelGroups) {
            this.center = center;
            this.lone = lone;
            this.radius = radius;
            this.entrances = entrances;
            this.buildings = buildings;
            this.panels = panels;
            this.panelGroups = panelGroups;
        }
    }

    private static final class Entry {
        @Nullable
        final Settlement settlement;
        final long builtTick;

        Entry(@Nullable Settlement settlement, long builtTick) {
            this.settlement = settlement;
            this.builtTick = builtTick;
        }
    }

    /** The settlement whose town hall sits at {@code stop} (XZ), or null when Millénaire knows none. */
    @Nullable
    public static Settlement get(World world, BlockPos stop) {
        if (world == null || stop == null) return null;
        long now = world.getTotalWorldTime();
        long ttl = TravellersConfig.VILLAGE_VISIT.buildingIndexRefreshSeconds * 20L;
        Map<Long, Entry> perWorld = CACHE.computeIfAbsent(world, w -> new HashMap<>());
        long key = ((long) stop.getX() & 0xFFFFFFFFL) | (((long) stop.getZ() & 0xFFFFFFFFL) << 32);
        Entry e = perWorld.get(key);
        if (e != null && now >= e.builtTick && now - e.builtTick < ttl) return e.settlement;
        Settlement s = build(world, stop);
        if (perWorld.size() > 512) perWorld.clear();
        perWorld.put(key, new Entry(s, now));
        return s;
    }

    @Nullable
    private static Settlement build(World world, BlockPos stop) {
        MillSettlements.Settlement settlement = MillSettlements.at(world, stop);
        Building th = settlement == null ? null : settlement.townHall(world);
        if (th == null) return null;

        boolean lone = !settlement.isVillage;
        int radius = th.villageType != null && th.villageType.radius > 0 ? th.villageType.radius : 32;

        Set<Building> all = new LinkedHashSet<>();
        all.add(th);
        try {
            all.addAll(th.getBuildings());
        } catch (Throwable ignored) {
        }

        List<BlockPos> entrances = new ArrayList<>();
        List<Target> buildings = new ArrayList<>();
        List<Target> panels = new ArrayList<>();
        List<List<Target>> panelGroups = new ArrayList<>();
        for (Building b : all) {
            if (b == null || b.location == null) continue;
            ResManager res = b.getResManager();
            if (res == null) continue;
            MixinResManagerPointsAccessor raw = (MixinResManagerPointsAccessor) res;
            BlockPos entrance = toPos(raw.travellers$getPathStartPos());
            if (entrance != null) entrances.add(entrance);

            String plan = b.location.planKey != null ? b.location.planKey : "?";
            if (isVisitable(b, plan)) {
                BlockPos point = toPos(raw.travellers$getLeasurePos());
                if (point == null) point = toPos(raw.travellers$getSellingPos());
                if (point == null) point = entrance;
                if (point != null) buildings.add(new Target(point, entrance, plan, false));
            }
            if (res.signs != null) {
                List<Target> group = new ArrayList<>();
                for (Point p : res.signs) {
                    BlockPos panel = toPos(p);
                    if (panel != null) group.add(new Target(panel, entrance, plan, true));
                }
                if (!group.isEmpty()) {
                    panels.addAll(group);
                    panelGroups.add(Collections.unmodifiableList(group));
                }
            }
        }
        return new Settlement(new BlockPos(stop.getX(), th.getPos().getiY(), stop.getZ()), lone, radius,
                Collections.unmodifiableList(entrances), Collections.unmodifiableList(buildings),
                Collections.unmodifiableList(panels), Collections.unmodifiableList(panelGroups));
    }

    private static boolean isVisitable(Building b, String plan) {
        TravellersConfig.VillageVisit cfg = TravellersConfig.VILLAGE_VISIT;
        String planLower = plan.toLowerCase(Locale.ROOT);
        Set<String> tags;
        try {
            tags = b.getTags();
        } catch (Throwable t) {
            tags = Collections.emptySet();
        }
        if (anyTag(tags, cfg.buildingExcludeTags) || anyWord(planLower, cfg.buildingExcludePlans)) return false;
        if (b.isTownhall && cfg.visitTownHall) return true;
        return anyTag(tags, cfg.buildingIncludeTags) || anyWord(planLower, cfg.buildingIncludePlans);
    }

    private static boolean anyTag(Set<String> tags, String[] wanted) {
        if (tags == null || wanted == null) return false;
        for (String w : wanted) {
            if (w == null || w.isEmpty()) continue;
            for (String t : tags) {
                if (t != null && t.equalsIgnoreCase(w.trim())) return true;
            }
        }
        return false;
    }

    private static boolean anyWord(String planLower, String[] words) {
        if (words == null) return false;
        for (String w : words) {
            if (w != null && !w.trim().isEmpty() && planLower.contains(w.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    @Nullable
    private static BlockPos toPos(@Nullable Point p) {
        return p == null ? null : new BlockPos(p.getiX(), p.getiY(), p.getiZ());
    }
}
