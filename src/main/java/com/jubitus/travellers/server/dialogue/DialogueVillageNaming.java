package com.jubitus.travellers.server.dialogue;

import com.jubitus.travellers.world.mill.MillVillageIndex;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The one shared "resolve a settlement from a Point" idiom used by every dialogue context provider
 * that needs a village's name/culture/kind — mirrors {@code EntityTraveller.announceHeadingTo}'s own
 * name resolution, factored out here instead of duplicated per provider (see the context-expansion
 * plan's E1). Covers villages and lone buildings alike ({@code MillVillageIndex.VillageInfo.isVillage}).
 */
final class DialogueVillageNaming {

    private DialogueVillageNaming() {
    }

    /** Nullable — no data found within range, or the found entry has no usable name. */
    static MillVillageIndex.VillageInfo resolveVillage(World world, BlockPos target) {
        if (target == null) return null;
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, target);
        if (vi == null) vi = MillVillageIndex.findNearest3D(world, target, 128);
        if (vi == null) vi = MillVillageIndex.findNearest2D(world, target, 160);
        return (vi != null && vi.name != null && !vi.name.isEmpty()) ? vi : null;
    }

    /** Nullable — convenience wrapper for callers that only ever wanted the name. */
    static String resolveVillageName(World world, BlockPos target) {
        MillVillageIndex.VillageInfo vi = resolveVillage(world, target);
        return vi != null ? vi.name : null;
    }
}
