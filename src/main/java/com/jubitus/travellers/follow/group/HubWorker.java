package com.jubitus.travellers.follow.group;

import com.jubitus.travellers.follow.net.GroupGearSummary;
import com.jubitus.travellers.zone.ZoneRef;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

/** One hired worker of the player, loaded or virtual, as the Formation Hub's single pass sees it. */
final class HubWorker {
    final UUID uuid;
    @Nullable final UUID escortLeader;
    final boolean followActive;
    @Nullable final ZoneRef zone;
    final int dim;
    final BlockPos pos;
    /** Adds this worker to a gear summary (loaded and virtual read different sources). */
    final Consumer<GroupGearSummary> addTo;

    HubWorker(UUID uuid, @Nullable UUID escortLeader, boolean followActive, @Nullable ZoneRef zone,
              int dim, BlockPos pos, Consumer<GroupGearSummary> addTo) {
        this.uuid = uuid;
        this.escortLeader = escortLeader;
        this.followActive = followActive;
        this.zone = zone;
        this.dim = dim;
        this.pos = pos;
        this.addTo = addTo;
    }
}
