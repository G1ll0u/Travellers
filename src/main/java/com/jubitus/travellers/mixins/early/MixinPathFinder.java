package com.jubitus.travellers.mixins.early;

import com.jubitus.travellers.config.TravellersConfig;
import com.jubitus.travellers.pathing.TravellerNodeProcessor;
import net.minecraft.pathfinding.NodeProcessor;
import net.minecraft.pathfinding.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Raises vanilla's hard cap on how many nodes a single {@code PathFinder.findPath} search may
 * expand before giving up and returning its best-effort route - scoped to searches that actually
 * belong to a Traveller (mounted or on foot) via the node processor in flight, since
 * {@code PathFinder} is a shared vanilla class used by every entity in the world.
 *
 * <p><b>Why 200 traps a mounted rider specifically</b>: {@code findPath}'s open list is a
 * priority queue ordered by accumulated cost plus straight-line distance to the target. Next to a
 * concave obstacle (a wall the target sits behind), the nodes hugging the wall always score best
 * on that heuristic even though they lead nowhere, so the search keeps re-expanding that local
 * cluster instead of reaching a few blocks sideways where the real route is.
 * {@link TravellerNodeProcessor} generates more candidates per node for a mounted body
 * (diagonals, mount-hop candidates, gap-jump candidates) than a plain one-cell body does, so more
 * of that fixed 200-node budget gets spent circling the same obstacle before ever discovering the
 * detour - measured directly from a trace: the mounted search consistently ends at the same node
 * hugging a 2-block step, while the identical destination asked on foot (fewer candidates per
 * node, same budget) succeeds.
 */
@Mixin(PathFinder.class)
public abstract class MixinPathFinder {

    @Shadow
    private NodeProcessor nodeProcessor;

    @ModifyConstant(method = "findPath(Lnet/minecraft/pathfinding/PathPoint;Lnet/minecraft/pathfinding/PathPoint;F)Lnet/minecraft/pathfinding/Path;",
            constant = @Constant(intValue = 200))
    private int travellers$nodeBudget(int original) {
        return this.nodeProcessor instanceof TravellerNodeProcessor
                ? TravellersConfig.MOUNTS.pathfinderNodeBudget
                : original;
    }
}
